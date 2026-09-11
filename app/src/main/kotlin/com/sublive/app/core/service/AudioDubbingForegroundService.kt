package com.sublive.app.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sublive.app.core.audio.AudioCaptureManager
import com.sublive.app.core.network.SubLiveWebSocketManager
import com.sublive.app.data.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import kotlin.math.sqrt

class AudioDubbingForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "alas_subtitle_channel"
        private const val NOTIFICATION_ID = 101
        
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val TAG = "SubtitleService"
        
        val isRunning = MutableStateFlow(false)
        val audioAmplitude = MutableStateFlow(0f)
        val liveSubtitleText = MutableStateFlow("")
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    
    private var mediaProjection: MediaProjection? = null
    private var audioCaptureManager: AudioCaptureManager? = null
    private var webSocketManager: SubLiveWebSocketManager? = null
    private var langObservationJob: Job? = null
    private var clearTextJob: Job? = null
    // Single-line subtitle: only the currently spoken sentence stays on
    // screen, everything already said is dropped immediately.
    private val partialSentence = StringBuilder()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        when (action) {
            ACTION_START -> {
                val notification = createNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                
                if (resultCode != 0 && data != null) {
                    startSubtitling(resultCode, data)
                }
            }
            ACTION_STOP -> {
                stopSubtitling()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startSubtitling(resultCode: Int, data: Intent) {
        isRunning.value = true
        liveSubtitleText.value = ""
        partialSentence.clear()
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        serviceScope.launch {
            val repository = UserPreferencesRepository(applicationContext)
            
            val apiKey = repository.apiKeyFlow.first()
            val targetLang = repository.targetLangFlow.first()
            
            webSocketManager = SubLiveWebSocketManager(OkHttpClient())
            
            webSocketManager?.onStatusChanged = { status ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(applicationContext, "Status: $status", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            
            webSocketManager?.onTextMessageReceived = { chunk ->
                // Keep the sentence accumulating until a punctuation mark clears it.
                partialSentence.append(chunk)
                var text = partialSentence.toString()
                
                val lastBoundary = text.indexOfLast { it == '.' || it == '!' || it == '?' || it == '؟' }
                if (lastBoundary >= 0 && lastBoundary < text.length - 1) {
                    text = text.substring(lastBoundary + 1).trimStart()
                    partialSentence.clear()
                    partialSentence.append(text)
                }
                
                // Show last 14-15 words so it doesn't chop words in half
                val words = text.trim().split(Regex("\\s+"))
                liveSubtitleText.value = if (words.size > 14) {
                    words.takeLast(14).joinToString(" ")
                } else {
                    text.trim()
                }

                // Auto clear after 7 seconds of silence/inactivity
                clearTextJob?.cancel()
                clearTextJob = serviceScope.launch {
                    kotlinx.coroutines.delay(7000)
                    partialSentence.clear()
                    liveSubtitleText.value = ""
                }
            }
            
            webSocketManager?.connect(apiKey, "", targetLang)
            
            langObservationJob?.cancel()
            langObservationJob = serviceScope.launch {
                var firstEmit = true
                repository.targetLangFlow.collect { newLang ->
                    if (firstEmit) {
                        firstEmit = false
                    } else {
                        val currentKey = repository.apiKeyFlow.first()
                        webSocketManager?.disconnect()
                        webSocketManager?.connect(currentKey, "", newLang)
                    }
                }
            }
            
            audioCaptureManager = AudioCaptureManager()
            val appUid = applicationInfo.uid
            
            mediaProjection?.let { projection ->
                audioCaptureManager?.startCapture(projection, appUid) { pcmData ->
                    webSocketManager?.sendAudioData(pcmData)
                    
                    var sum = 0.0
                    for (i in pcmData.indices step 2) {
                        if (i + 1 < pcmData.size) {
                            val sample = (pcmData[i].toInt() and 0xFF) or (pcmData[i+1].toInt() shl 8)
                            val signedSample = sample.toShort().toFloat()
                            sum += signedSample * signedSample
                        }
                    }
                    val rms = if (pcmData.isNotEmpty()) sqrt(sum / (pcmData.size / 2)).toFloat() else 0f
                    val normalized = (rms / 32767f * 3f).coerceIn(0f, 1f)
                    val current = audioAmplitude.value
                    audioAmplitude.value = current * 0.5f + normalized * 0.5f
                }
            }
        }
    }

    private fun stopSubtitling() {
        isRunning.value = false
        audioCaptureManager?.stopCapture()
        audioCaptureManager = null
        
        webSocketManager?.disconnect()
        webSocketManager = null
        
        audioAmplitude.value = 0f
        liveSubtitleText.value = ""
        
        clearTextJob?.cancel()
        clearTextJob = null
        
        langObservationJob?.cancel()
        langObservationJob = null
        
        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning.value) {
            stopSubtitling()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Live Subtitles Service"
            val descriptionText = "Translating audio to real-time subtitles"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, com.sublive.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, AudioDubbingForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 1, stopIntent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SubLive")
            .setContentText("Capturing and displaying real-time subtitles...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Subtitles", stopPendingIntent)
            .setOngoing(true)
            .build()
    }
}

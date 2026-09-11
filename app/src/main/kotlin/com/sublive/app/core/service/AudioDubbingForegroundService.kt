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
import com.sublive.app.core.network.GeminiTranslator
import com.sublive.app.core.network.GroqWhisperClient
import com.sublive.app.core.network.SubLiveWebSocketManager
import com.sublive.app.data.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean
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

        // 3 seconds of 16-bit mono @16kHz per Whisper window
        private const val WINDOW_BYTES = 16000 * 2 * 3
        // Mean |sample| below this counts as silence -> window is skipped
        private const val SILENCE_MEAN_ABS = 250f

        val isRunning = MutableStateFlow(false)
        val audioAmplitude = MutableStateFlow(0f)
        val liveSubtitleText = MutableStateFlow("")
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    private var mediaProjection: MediaProjection? = null
    private var audioCaptureManager: AudioCaptureManager? = null
    private var webSocketManager: SubLiveWebSocketManager? = null
    private var whisperClient: GroqWhisperClient? = null
    private var translator: GeminiTranslator? = null
    private var langObservationJob: Job? = null
    private var clearTextJob: Job? = null
    // Single-line subtitle: only the currently spoken sentence stays on
    // screen, everything already said is dropped immediately.
    private val partialSentence = StringBuilder()
    private val whisperInFlight = AtomicBoolean(false)
    private val windowBuffer = java.io.ByteArrayOutputStream(WINDOW_BYTES * 2)
    private val diagShown = mutableSetOf<String>()

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
        windowBuffer.reset()
        whisperInFlight.set(false)
        synchronized(diagShown) { diagShown.clear() }
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        serviceScope.launch {
            val repository = UserPreferencesRepository(applicationContext)

            val apiKey = repository.apiKeyFlow.first()
            val groqKey = repository.groqKeyFlow.first()
            val targetLang = repository.targetLangFlow.first()

            if (groqKey.isBlank()) {
                // No Groq key -> legacy Gemini Live path (unchanged, stable).
                startLegacySubtitling(repository, apiKey, targetLang)
            } else {
                startWhisperSubtitling(repository, apiKey, groqKey)
            }
        }
    }

    /**
     * Low-latency path: 3s audio windows -> Groq Whisper (text) ->
     * Gemini text translate (~1s) -> subtitle. Total ~2s vs ~4s before.
     */
    private suspend fun startWhisperSubtitling(
        repository: UserPreferencesRepository,
        geminiKey: String,
        groqKey: String
    ) {
        whisperClient = GroqWhisperClient()
        translator = GeminiTranslator()

        toast("Whisper mode: fast subtitles")

        audioCaptureManager = AudioCaptureManager()
        val appUid = applicationInfo.uid

        mediaProjection?.let { projection ->
            audioCaptureManager?.startCapture(projection, appUid) { pcmData ->
                updateAmplitude(pcmData)
                // Accumulate raw reads into fixed 3s windows.
                synchronized(windowBuffer) {
                    windowBuffer.write(pcmData, 0, pcmData.size)
                    while (windowBuffer.size() >= WINDOW_BYTES) {
                        val full = windowBuffer.toByteArray()
                        val window = full.copyOf(WINDOW_BYTES)
                        val rest = full.copyOfRange(WINDOW_BYTES, full.size)
                        windowBuffer.reset()
                        windowBuffer.write(rest, 0, rest.size)
                        processWindow(window, repository, geminiKey, groqKey)
                    }
                }
            }
        }
    }

    private fun processWindow(
        window: ByteArray,
        repository: UserPreferencesRepository,
        geminiKey: String,
        groqKey: String
    ) {
        if (isSilent(window)) {
            diagOnce("silence", "No audio detected — check volume")
            return
        }
        // Single-flight: drop the window if the previous one is still being
        // processed, so subtitles never pile up behind real time.
        if (!whisperInFlight.compareAndSet(false, true)) return
        serviceScope.launch {
            try {
                val text = whisperClient?.transcribe(window, groqKey.trim())
                if (text.isNullOrBlank()) {
                    diagOnce("whisper", "Whisper: no response — check Groq key/net")
                    return@launch
                }
                val target = repository.targetLangFlow.first()
                    .split("-")[0].ifEmpty { "fa" }
                val translated = translator?.translate(text, target, geminiKey.trim())
                if (translated.isNullOrBlank()) {
                    diagOnce("translate", "Translate: no response — check Gemini key")
                    return@launch
                }
                showSubtitle(translated.trim())
            } finally {
                whisperInFlight.set(false)
            }
        }
    }

    /** Shows a diagnostic toast only once per session. */
    private fun diagOnce(tag: String, msg: String) {
        synchronized(diagShown) {
            if (diagShown.add(tag)) toast(msg)
        }
    }

    private fun showSubtitle(line: String) {
        if (line.isNotEmpty()) {
            liveSubtitleText.value = line
        }
        clearTextJob?.cancel()
        clearTextJob = serviceScope.launch {
            // Whisper cadence is ~4-5s per subtitle; clear only after 9s of nothing.
            kotlinx.coroutines.delay(9000)
            partialSentence.clear()
            liveSubtitleText.value = ""
        }
    }

    private fun isSilent(pcm: ByteArray): Boolean {
        var sumAbs = 0L
        var n = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            sumAbs += kotlin.math.abs(sample)
            n++
            i += 2
        }
        if (n == 0) return true
        return (sumAbs.toFloat() / n) < SILENCE_MEAN_ABS
    }

    private fun updateAmplitude(pcmData: ByteArray) {
        var sum = 0.0
        for (i in pcmData.indices step 2) {
            if (i + 1 < pcmData.size) {
                val sample = (pcmData[i].toInt() and 0xFF) or (pcmData[i + 1].toInt() shl 8)
                val signedSample = sample.toShort().toFloat()
                sum += signedSample * signedSample
            }
        }
        val rms = if (pcmData.isNotEmpty()) sqrt(sum / (pcmData.size / 2)).toFloat() else 0f
        val normalized = (rms / 32767f * 3f).coerceIn(0f, 1f)
        val current = audioAmplitude.value
        audioAmplitude.value = current * 0.5f + normalized * 0.5f
    }

    private fun toast(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Legacy Gemini Live path (stable). Used when no Groq key is configured.
     */
    private suspend fun startLegacySubtitling(
        repository: UserPreferencesRepository,
        apiKey: String,
        targetLang: String
    ) {
        webSocketManager = SubLiveWebSocketManager(OkHttpClient())

        webSocketManager?.onStatusChanged = { status ->
            toast("Status: $status")
        }

        webSocketManager?.onTextMessageReceived = { chunk ->
            // Simplest single-line mode: every new chunk replaces the old one.
            // The stream from Gemini arrives phrase-by-phrase, so just show
            // the latest phrase — nothing accumulates, nothing piles up.
            val line = chunk.trim()
            if (line.isNotEmpty()) {
                liveSubtitleText.value = line
            }
            clearTextJob?.cancel()
            clearTextJob = serviceScope.launch {
                kotlinx.coroutines.delay(4000)
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
                updateAmplitude(pcmData)
            }
        }
    }

    private fun stopSubtitling() {
        isRunning.value = false
        whisperInFlight.set(false)
        synchronized(windowBuffer) { windowBuffer.reset() }
        audioCaptureManager?.stopCapture()
        audioCaptureManager = null

        webSocketManager?.disconnect()
        webSocketManager = null
        whisperClient = null
        translator = null

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

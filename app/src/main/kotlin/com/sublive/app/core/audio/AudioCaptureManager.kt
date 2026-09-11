package com.sublive.app.core.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import kotlin.math.sqrt

/**
 * Low-latency capture: raw reads are batched into fixed 200ms PCM chunks
 * (6400 bytes @ 16kHz mono 16-bit) and pure-silence chunks are dropped so
 * the Live API never wastes turn time on noise. After ~600ms of continuous
 * silence, onPauseDetected fires so the caller can force-close the turn
 * (commitTurn) and get the translation immediately instead of lagging
 * seconds behind the audio.
 */
class AudioCaptureManager {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    companion object {
        private const val TAG = "AudioCaptureManager"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        // 200ms of 16-bit mono @16kHz — small enough for near-simultaneous translation
        const val BATCH_BYTES = SAMPLE_RATE * 2 / 5
        // RMS below which a 200ms chunk counts as silence
        private const val SILENCE_RMS = 350f
        // 3 x 200ms = ~600ms of silence before the pause callback fires
        private const val SILENT_CHUNKS_FOR_PAUSE = 3
    }

    @SuppressLint("MissingPermission")
    fun startCapture(
        mediaProjection: MediaProjection,
        appUid: Int,
        onAudioData: (ByteArray) -> Unit,
        onPauseDetected: () -> Unit = {}
    ) {
        if (isRecording) return

        try {
            val configBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)

            if (appUid > 0) {
                configBuilder.excludeUid(appUid)
            }

            val config = configBuilder.build()

            val format = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(BUFFER_SIZE.coerceAtLeast(BATCH_BYTES * 2))
                .build()

            audioRecord?.startRecording()
            isRecording = true

            Thread {
                val readBuf = ByteArray(BUFFER_SIZE)
                val batch = java.io.ByteArrayOutputStream(BATCH_BYTES * 2)
                var silentChunks = 0
                var chunksRead = 0
                while (isRecording) {
                    val read = audioRecord?.read(readBuf, 0, readBuf.size) ?: 0
                    if (read > 0) {
                        chunksRead++
                        if (chunksRead % 250 == 0) {
                            Log.d(TAG, "Captured 250 reads")
                        }
                        batch.write(readBuf, 0, read)
                        while (batch.size >= BATCH_BYTES) {
                            val full = batch.toByteArray()
                            val chunk = full.copyOf(BATCH_BYTES)
                            val rest = full.copyOfRange(BATCH_BYTES, full.size)
                            batch.reset()
                            batch.write(rest)

                            if (rmsOf(chunk) < SILENCE_RMS) {
                                silentChunks++
                                if (silentChunks >= SILENT_CHUNKS_FOR_PAUSE) {
                                    silentChunks = 0
                                    try {
                                        onPauseDetected()
                                    } catch (_: Exception) {
                                    }
                                }
                                // Drop silent chunk: don't send noise to the API.
                            } else {
                                silentChunks = 0
                                try {
                                    onAudioData(chunk)
                                } catch (e: Exception) {
                                    Log.e(TAG, "onAudioData error", e)
                                }
                            }
                        }
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting capture", e)
        }
    }

    private fun rmsOf(pcm: ByteArray): Float {
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toFloat()
            sum += sample * sample
            n++
            i += 2
        }
        return if (n == 0) 0f else sqrt(sum / n).toFloat()
    }

    fun stopCapture() {
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
    }
}

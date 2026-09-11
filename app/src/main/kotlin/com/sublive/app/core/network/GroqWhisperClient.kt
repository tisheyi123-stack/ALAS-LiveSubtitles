package com.sublive.app.core.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fast speech-to-text via Groq-hosted Whisper (whisper-large-v3-turbo).
 * Input: 16kHz mono 16-bit PCM bytes. Output: transcribed text or null.
 */
class GroqWhisperClient(client: OkHttpClient? = null) {

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    companion object {
        private const val TAG = "GroqWhisper"
        private const val URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val MODEL = "whisper-large-v3-turbo"
    }

    suspend fun transcribe(pcm16kMono: ByteArray, apiKey: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val wav = pcmToWav(pcm16kMono)
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", "chunk.wav",
                        wav.toRequestBody("audio/wav".toMediaType())
                    )
                    .addFormDataPart("model", MODEL)
                    .addFormDataPart("response_format", "json")
                    .build()
                val req = Request.Builder()
                    .url(URL)
                    .header("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: return@withContext null
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "Whisper HTTP ${resp.code}: ${raw.take(200)}")
                        return@withContext null
                    }
                    val text = JSONObject(raw).optString("text", "").trim()
                    if (text.isEmpty()) null else text
                }
            } catch (e: Exception) {
                Log.e(TAG, "Whisper error", e)
                null
            }
        }

    /** Wraps raw 16kHz mono 16-bit PCM in a 44-byte WAV header. */
    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val header = ByteArray(44)
        val totalLen = (36 + pcm.size).toLong()
        fun setInt(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun setShort(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        setInt(4, totalLen.toInt())
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        setInt(16, 16)
        setShort(20, 1) // PCM
        setShort(22, 1) // mono
        setInt(24, 16000)
        setInt(28, 16000 * 2) // byte rate
        setShort(32, 2) // block align
        setShort(34, 16) // bits per sample
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        setInt(40, pcm.size)
        return header + pcm
    }
}

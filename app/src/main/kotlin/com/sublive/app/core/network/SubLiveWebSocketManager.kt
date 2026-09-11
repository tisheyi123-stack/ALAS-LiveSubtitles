package com.sublive.app.core.network

import android.util.Base64
import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

class SubLiveWebSocketManager(private val client: OkHttpClient) {
    private var webSocket: WebSocket? = null
    var onTextMessageReceived: ((String) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "SubLiveWebSocketManager"
        private const val GEMINI_WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        // Same translation model as the original working app.
        // Translation models only accept AUDIO modality, so we keep AUDIO
        // and read the live subtitle text from outputAudioTranscription.
        private const val MODEL = "models/gemini-3.5-live-translate-preview"
    }

    private var isSetupComplete = false

    fun connect(apiKey: String, sourceLang: String, targetLang: String) {
        val finalUrl = "$GEMINI_WS_URL?key=$apiKey"
        val request = Request.Builder().url(finalUrl).build()
        isSetupComplete = false

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to Gemini Live API")
                onStatusChanged?.invoke("Connected")
                sendGeminiSetup(targetLang)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Raw message: $text")
                try {
                    val json = JSONObject(text)

                    if (json.has("serverContent") || json.has("server_content")) {
                        val serverContent = json.optJSONObject("serverContent") ?: json.optJSONObject("server_content")

                        // 1) Live subtitle text: transcript of the translated audio output.
                        // The AUDIO modality keeps working (translation path unchanged),
                        // we just display its transcript instead of playing the sound.
                        val outputTranscription = serverContent?.optJSONObject("outputTranscription")
                            ?: serverContent?.optJSONObject("output_transcription")
                        val transcriptText = outputTranscription?.optString("text", "") ?: ""
                        if (transcriptText.isNotEmpty()) {
                            onTextMessageReceived?.invoke(transcriptText)
                        }

                        // 2) Translated AUDIO parts still arrive (inlineData) — ignored on purpose:
                        // this is the subtitle app, original sound keeps playing untouched.
                    } else if (json.has("setupComplete") || json.has("setup_complete")) {
                        isSetupComplete = true
                        onStatusChanged?.invoke("Gemini Ready")
                        pendingAudio.forEach { sendAudioNow(it) }
                        pendingAudio.clear()
                    } else if (json.has("error")) {
                        val errMessage = json.getJSONObject("error").optString("message", "Unknown error")
                        Log.e(TAG, "API Error: $errMessage")
                        onStatusChanged?.invoke("Error: $errMessage")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val text = bytes.utf8()
                onMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isSetupComplete = false
                Log.d(TAG, "Closing: $code $reason")
                onStatusChanged?.invoke("Disconnected: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isSetupComplete = false
                Log.e(TAG, "WebSocket error", t)
                onStatusChanged?.invoke("Error: ${t.message}")
            }
        })
    }

    private fun sendGeminiSetup(targetLang: String) {
        val targetLangCode = targetLang.split("-")[0]
        val setupPayload = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", MODEL)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("translationConfig", JSONObject().apply {
                        put("targetLanguageCode", targetLangCode)
                        put("echoTargetLanguage", true)
                    })
                })
                // Ask the server to also send a text transcript of its spoken
                // translation — this transcript IS our live subtitle stream.
                put("outputAudioTranscription", JSONObject())
                put("inputAudioTranscription", JSONObject())
                put("sessionResumption", JSONObject().apply {
                    put("handle", JSONObject.NULL)
                })
            })
        }
        webSocket?.send(setupPayload.toString())
    }

    private val pendingAudio = mutableListOf<String>()

    fun sendAudioData(pcmData: ByteArray) {
        val base64Audio = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        if (!isSetupComplete) {
            pendingAudio.add(base64Audio)
            if (pendingAudio.size > 8) pendingAudio.removeAt(0)
            return
        }
        sendAudioNow(base64Audio)
    }

    private fun sendAudioNow(base64Audio: String) {
        // Same realtimeInput format as the original working app.
        val inputPayload = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", base64Audio)
                })
            })
        }
        webSocket?.send(inputPayload.toString())
    }

    fun disconnect() {
        pendingAudio.clear()
        webSocket?.close(1000, "User requested stop")
        webSocket = null
        onStatusChanged?.invoke("Disconnected")
    }
}

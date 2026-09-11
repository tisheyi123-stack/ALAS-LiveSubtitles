package com.sublive.app.core.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal text translation via Gemini REST (generateContent).
 * Fast (~1s) because it is plain text-in/text-out, no audio rendering.
 */
class GeminiTranslator(client: OkHttpClient? = null) {

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    companion object {
        private const val TAG = "GeminiTranslator"
        private const val MODEL = "gemini-2.0-flash"
    }

    suspend fun translate(text: String, targetLangCode: String, apiKey: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject()
                    .put(
                        "contents", JSONArray().put(
                            JSONObject().put(
                                "parts", JSONArray().put(
                                    JSONObject().put(
                                        "text",
                                        "Translate the following speech transcript into language code " +
                                            "'$targetLangCode'. Reply with ONLY the translated text, " +
                                            "no explanations, no quotes:\n$text"
                                    )
                                )
                            )
                        )
                    )
                    .toString()
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: return@withContext null
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "Translate HTTP ${resp.code}: ${raw.take(200)}")
                        return@withContext null
                    }
                    val parts = JSONObject(raw)
                        .optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                    val out = parts?.optJSONObject(0)?.optString("text", "")?.trim() ?: ""
                    if (out.isEmpty()) null else out
                }
            } catch (e: Exception) {
                Log.e(TAG, "Translate error", e)
                null
            }
        }
}

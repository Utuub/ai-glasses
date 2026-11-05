package com.example.vuzixem

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Sends one question to Claude and returns the text answer (or an "Error: ..." string). */
object ClaudeClient {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    // Haiku = fastest, helps the 5-second target. Swap to claude-sonnet-5-5 if answers are weak.
    private const val MODEL = "claude-haiku-5-5"

    private const val SYSTEM = "You are a field assistant for licensed electricians wearing smart glasses. " +
        "Answer NEC questions in 2 to 3 short sentences. Always cite the NEC section. " +
        "If you are not sure, say so. Plain text only, no markdown."

    suspend fun ask(question: String): String = withContext(Dispatchers.IO) {
        val key = BuildConfig.CLAUDE_KEY
        if (key.isBlank()) return@withContext "Error: no CLAUDE_KEY in local.properties"

        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 300)
            // Haiku 5.5 thinks by default, which ate all 300 tokens and returned no answer.
            // Disabled = ~1.3 s answers. (Allowed on Haiku 5.5 at effort high or below; default is medium.)
            put("thinking", JSONObject().put("type", "disabled"))
            put("system", SYSTEM)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", question)))
        }.toString()

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            http.newCall(req).execute().use { res ->
                val json = JSONObject(res.body?.string() ?: "{}")
                if (!res.isSuccessful) {
                    return@use "Error ${res.code}: " + (json.optJSONObject("error")?.optString("message") ?: "unknown")
                }
                val content = json.getJSONArray("content")
                (0 until content.length())
                    .map { content.getJSONObject(it) }
                    .filter { it.optString("type") == "text" }
                    .joinToString("") { it.getString("text") }
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

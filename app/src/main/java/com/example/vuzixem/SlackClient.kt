package com.example.vuzixem

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Posts text to the team Slack channel through an incoming webhook. Returns true if Slack accepted it. */
object SlackClient {
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    suspend fun send(text: String): Boolean = withContext(Dispatchers.IO) {
        val url = BuildConfig.SLACK_WEBHOOK
        if (url.isBlank()) return@withContext false
        val req = Request.Builder()
            .url(url)
            .post(JSONObject().put("text", text).toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}

package com.digimax.transcriber

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Posts the finished transcript to the PWA's /api/summarize (Cloudflare Workers
 * AI) and returns a short summary + key points + action items, or null on any
 * failure. Fail-soft by design — the caller falls back to emailing the raw
 * transcript. Only the transcript TEXT leaves the device; the audio never does.
 */
object Summarizer {

    // The PWA project subdomain is stable and not behind Access; /api/* is open.
    private const val ENDPOINT = "https://mic-transcriber.pages.dev/api/summarize"

    suspend fun summarise(text: String): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        runCatching {
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            JSONObject(body).optString("summary", "").trim().ifBlank { null }
        }.getOrNull()
    }
}

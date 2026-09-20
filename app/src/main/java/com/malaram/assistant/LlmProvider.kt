package com.malaram.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenDroid-inspired provider boundary:
 * the agent does not care where the model runs.
 * A provider can be cloud, self-hosted, or local.
 */
interface LlmProvider {
    fun complete(context: Context, systemPrompt: String, userPrompt: String, maxTokens: Int): String
}

class OpenAiCompatibleProvider(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String
) : LlmProvider {

    override fun complete(
        context: Context,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int
    ): String {
        val connection = (URL(normalizeEndpoint(endpoint)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }

        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.1)
            .put("max_tokens", maxTokens)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userPrompt))
            )

        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                throw IllegalStateException("AI server HTTP " + code + ": " + responseBody.take(300))
            }

            val choices = JSONObject(responseBody).optJSONArray("choices")
                ?: throw IllegalStateException("AI response में choices नहीं मिला")
            val message = choices.optJSONObject(0)?.optJSONObject("message")
                ?: throw IllegalStateException("AI response में message नहीं मिला")

            return message.optString("content").trim().ifBlank {
                throw IllegalStateException("AI ने खाली जवाब दिया")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeEndpoint(raw: String): String {
        val value = raw.trim().trimEnd('/')
        return if (value.endsWith("/chat/completions")) value else value + "/v1/chat/completions"
    }
}

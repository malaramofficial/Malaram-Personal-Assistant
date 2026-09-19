package com.malaram.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object AiBrain {
    private const val PREFS = "assistant_ai"
    private const val KEY_API = "gemini_api_key"
    private const val MODEL = "gemini-3.8-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent"

    private val executor = Executors.newSingleThreadExecutor()

    fun hasApiKey(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API, null)?.isNotBlank() == true

    fun setApiKey(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_API, apiKey.trim()).apply()
    }

    fun clearApiKey(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_API).apply()
    }

    fun ask(context: Context, userText: String, callback: (String) -> Unit) {
        val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API, null)?.trim()

        if (key.isNullOrBlank()) {
            callback("AI Brain अभी सेट नहीं है। पहले AI Brain में Gemini API key जोड़ें।")
            return
        }

        executor.execute {
            val answer = try {
                request(key, userText)
            } catch (e: Exception) {
                "AI से जवाब नहीं मिल पाया: " + (e.message ?: "network error")
            }

            Handler(Looper.getMainLooper()).post { callback(answer) }
        }
    }

    private fun request(apiKey: String, userText: String): String {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }

        val system = """
            तुम Malaram Personal Assistant के AI Brain हो।
            उपयोगकर्ता मुख्यतः हिंदी में बात करता है।
            सरल और प्राकृतिक हिंदी में उत्तर दो।
            उपयोगी उत्तर दो, अनावश्यक लंबाई मत बढ़ाओ।
            फोन action के लिए अनुमान लगाकर संवेदनशील काम मत करो।
            भुगतान, OTP, पासवर्ड, खरीद, deletion और account changes में पुष्टि जरूरी है।
        """.trimIndent()

        val body = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", system))
                )
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", userText))
                        )
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("maxOutputTokens", 700)
                    .put(
                        "thinkingConfig",
                        JSONObject().put("thinkingLevel", "low")
                    )
            )

        connection.outputStream.use {
            it.write(body.toString().toByteArray(Charsets.UTF_8))
        }

        val code = connection.responseCode
        val input = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = BufferedReader(
            InputStreamReader(input, Charsets.UTF_8)
        ).use { it.readText() }
        connection.disconnect()

        if (code !in 200..299) {
            throw IllegalStateException("HTTP " + code)
        }

        val candidates = JSONObject(response).optJSONArray("candidates")
            ?: throw IllegalStateException("AI ने कोई उत्तर नहीं दिया")

        val parts = candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw IllegalStateException("AI response खाली है")

        val out = StringBuilder()
        for (i in 0 until parts.length()) {
            val text = parts.optJSONObject(i)?.optString("text").orEmpty()
            if (text.isNotBlank()) out.append(text)
        }

        return out.toString().trim().ifBlank {
            "AI ने कोई स्पष्ट उत्तर नहीं दिया।"
        }
    }
}

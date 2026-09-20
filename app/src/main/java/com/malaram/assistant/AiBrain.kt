package com.malaram.assistant

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import java.io.File
import java.util.concurrent.Executors
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** OpenDroid-inspired AI layer: provider-pluggable, no mandatory giant model. */
object AiBrain {
    private val executor = Executors.newSingleThreadExecutor()
    private var cachedModel: dev.ffmpegkit.llama.LlamaModel? = null

    const val MODEL_FILE = "Qwen3-0.6B-Q4_K_M.gguf"
    const val MODEL_MIN_BYTES = 350_000_000L
    private const val MODEL_URL = "https://huggingface.co/tensorblock/Qwen_Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf?download=true"

    private const val PREFS = "ai_brain"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_MODEL = "model"
    private const val KEY_API = "api_key"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val PROVIDER_REMOTE = "remote"
    private const val PROVIDER_LOCAL = "local"
    private const val DEFAULT_ENDPOINT = "https://api.openai.com"
    private const val DEFAULT_MODEL = "gpt-4o-mini"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val API_KEY_ALIAS = "malaram_ai_api_key_v1"

    private const val SYSTEM_PROMPT = """
        तुम Malaram Personal Assistant के AI Brain हो।
        उपयोगकर्ता मुख्यतः हिंदी में बात करता है।
        सरल, प्राकृतिक और संक्षिप्त हिंदी में उत्तर दो।
        फोन action के लिए अनुमान लगाकर संवेदनशील काम मत करो।
        भुगतान, OTP, पासवर्ड, खरीद, deletion और account changes में पुष्टि जरूरी है।
    """

    fun configureRemote(context: Context, endpoint: String, model: String, apiKey: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROVIDER, PROVIDER_REMOTE)
            .putString(KEY_ENDPOINT, endpoint.trim().ifBlank { DEFAULT_ENDPOINT })
            .putString(KEY_MODEL, model.trim().ifBlank { DEFAULT_MODEL })
        if (apiKey.isNotBlank()) saveApiKey(apiKey.trim())
        pendingApiKey?.let { prefs.putString(KEY_API, it)\n        pendingApiKey = null\n        prefs.apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = ks.getKey(API_KEY_ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance("AES", KEYSTORE)
        generator.init(256)
        return generator.generateKey().also { /* generated key is stored by AndroidKeyStore */ }
    }

    private fun saveApiKey(value: String) {
        val secret = key()
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        // Store only ciphertext + IV in app prefs; the AES key remains in Android Keystore.
        val encoded = Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        // This method has no Context by design; key alias is stable and prefs are supplied by caller below.
        pendingApiKey = encoded
    }

    @Volatile private var pendingApiKey: String? = null

    private fun readApiKey(context: Context): String {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API, "").orEmpty()
        if (encoded.isBlank()) return ""
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    fun useLocal(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PROVIDER, PROVIDER_LOCAL).apply()
    }

    fun providerStatus(context: Context): String {
        val p = prefs(context).getString(KEY_PROVIDER, PROVIDER_REMOTE) ?: PROVIDER_REMOTE
        return if (p == PROVIDER_LOCAL) {
            if (isModelReady(context)) "Local AI: Qwen3 0.6B तैयार है।" else "Local AI चुना है, लेकिन model डाउनलोड नहीं हुआ।"
        } else {
            val endpoint = prefs(context).getString(KEY_ENDPOINT, DEFAULT_ENDPOINT).orEmpty()
            val model = prefs(context).getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()
            val key = readApiKey(context)
            if (key.isBlank()) "Remote AI configured नहीं है। API key सेट करें।" else "Remote AI: $model • $endpoint"
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun modelFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, MODEL_FILE)
    }

    fun isModelReady(context: Context): Boolean = modelFile(context).length() >= MODEL_MIN_BYTES

    fun startModelDownload(context: Context): Long {
        if (isModelReady(context)) return 0L
        val oldId = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)
        if (oldId != -1L) {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.query(DownloadManager.Query().setFilterById(oldId)).use { cursor ->
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING) return oldId
                }
            }
        }
        val file = modelFile(context)
        if (file.exists() && file.length() < MODEL_MIN_BYTES) file.delete()
        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("Malaram Assistant • Optional Local AI")
            .setDescription("Qwen3 0.6B Q4_K_M • लगभग 484 MB")
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, MODEL_FILE)
        val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        prefs(context).edit().putLong(KEY_DOWNLOAD_ID, id).apply()
        return id
    }

    fun downloadStatus(context: Context): String {
        if (isModelReady(context)) return "Local Qwen3 0.6B तैयार है।"
        val id = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)
        if (id == -1L) return "Local AI model अभी डाउनलोड नहीं हुआ है।"
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return "Local AI download शुरू नहीं हुआ।"
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return when (status) {
                DownloadManager.STATUS_PENDING -> "Local AI download queue में है…"
                DownloadManager.STATUS_RUNNING -> if (total > 0) "Local AI डाउनलोड हो रहा है… " + (downloaded / 1_000_000) + " / " + (total / 1_000_000) + " MB" else "Local AI डाउनलोड हो रहा है…"
                DownloadManager.STATUS_SUCCESSFUL -> if (isModelReady(context)) "Local Qwen3 0.6B तैयार है।" else "Download पूरा हुआ, model verify नहीं हुआ।"
                DownloadManager.STATUS_FAILED -> "Local AI download fail हुआ।"
                else -> "Local AI download स्थिति: " + status
            }
        }
    }

    private fun localProvider(context: Context): LlmProvider {
        if (!isModelReady(context)) throw IllegalStateException("Local Qwen3 0.6B अभी तैयार नहीं है।")
        val model = cachedModel ?: runBlocking {
            Llama.loadModel(
                modelPath = modelFile(context).absolutePath,
                config = LlamaConfig(contextSize = 2048, threads = Runtime.getRuntime().availableProcessors().coerceIn(4, 8), gpuLayers = 0, temperature = 0.1f, topP = 0.8f, topK = 20)
            )
        }.also { cachedModel = it }
        return object : LlmProvider {
            override fun complete(context: Context, systemPrompt: String, userPrompt: String, maxTokens: Int): String =
                runBlocking { Llama.complete(model, prompt = userPrompt, systemPrompt = systemPrompt, maxTokens = maxTokens).text }
        }
    }

    private fun provider(context: Context): LlmProvider {
        val p = prefs(context).getString(KEY_PROVIDER, PROVIDER_REMOTE) ?: PROVIDER_REMOTE
        if (p == PROVIDER_LOCAL) return localProvider(context)
        val key = readApiKey(context)
        if (key.isBlank()) throw IllegalStateException("AI provider सेट नहीं है। AI Settings में API key डालें।")
        return OpenAiCompatibleProvider(prefs(context).getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT, key, prefs(context).getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL)
    }

    fun releaseModel() {
        cachedModel?.let { try { Llama.releaseModel(it) } catch (_: Exception) {} }
        cachedModel = null
    }

    fun ask(context: Context, userText: String, callback: (String) -> Unit) {
        executor.execute {
            val answer = try { provider(context).complete(context, SYSTEM_PROMPT, userText, 160) } catch (e: Exception) {
                "AI से जवाब नहीं मिला: " + (e.message ?: "अज्ञात त्रुटि")
            }
            Handler(Looper.getMainLooper()).post { callback(answer) }
        }
    }

    private data class AgentAction(val action: String, val argument: String)

    private fun parseAgentAction(raw: String): AgentAction? {
        val cleaned = raw.trim()
        try {
            val json = JSONObject(cleaned.substringAfter("{", cleaned).substringBeforeLast("}", cleaned))
            val action = json.optString("action").trim().lowercase()
            if (action.isNotBlank()) return AgentAction(action, json.optString("argument").trim())
        } catch (_: Exception) { }
        val action = Regex("ACTION\\s*=\\s*([^\\n|]+)", RegexOption.IGNORE_CASE).find(cleaned)?.groupValues?.getOrNull(1)?.trim()?.lowercase() ?: return null
        val argument = Regex("(?:ARG|ARGUMENT)\\s*=\\s*(.*)", RegexOption.IGNORE_CASE).find(cleaned)?.groupValues?.getOrNull(1)?.trim() ?: ""
        return AgentAction(action, argument)
    }

    fun runAgent(context: Context, task: String, callback: (String) -> Unit) {
        executor.execute {
            var finalText = "मैं यह काम पूरा नहीं कर पाया।"
            try {
                for (stepIndex in 0 until 6) {
                    val screen = AssistantAccessibilityService.readScreen().replace(Regex("\\s+"), " ").take(3500)
                    val prompt = "/no_think\nकाम: " + task + "\nस्क्रीन: " + screen.ifBlank { "(खाली)" } +
                        "\nस्टेप: " + stepIndex + "\nकेवल अगला atomic action दो। JSON या ACTION=... format स्वीकार है। " +
                        "Allowed: open_app, click, type, enter, back, swipe_up, swipe_down, wait, done, answer. " +
                        "पूरा लक्ष्य पूरा होने तक काम करो; ऐप खुलना अकेले सफलता नहीं है।"
                    val raw = provider(context).complete(context, SYSTEM_PROMPT, prompt, 90)
                    val parsed = parseAgentAction(raw) ?: break
                    when (parsed.action) {
                        "done" -> { finalText = parsed.argument.ifBlank { "काम पूरा हो गया।" }; break }
                        "answer" -> { finalText = parsed.argument.ifBlank { "मैं यह काम पूरा नहीं कर पाया।" }; break }
                        "wait" -> Thread.sleep(250)
                        else -> finalText = CommandEngine.executeAgentAction(context, parsed.action, parsed.argument)
                    }
                }
            } catch (e: Exception) { finalText = "AI Agent रुक गया: " + (e.message ?: "अज्ञात त्रुटि") }
            Handler(Looper.getMainLooper()).post { callback(finalText) }
        }
    }
}

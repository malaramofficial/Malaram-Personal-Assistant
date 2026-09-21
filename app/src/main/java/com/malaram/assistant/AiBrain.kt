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
    @Volatile private var agentRunning = false
    @Volatile private var agentCancelled = false
    private const val AGENT_MAX_STEPS = 20
    private const val AGENT_TIMEOUT_MS = 90_000L

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
    private const val PROVIDER_TERMUX = "termux"
    private const val DEFAULT_ENDPOINT = "https://api.openai.com"
    private const val DEFAULT_MODEL = "gpt-4o-mini"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val API_KEY_ALIAS = "malaram_ai_api_key_v1"

    private const val SYSTEM_PROMPT = """
        तुम Malaram Personal Assistant के AI Brain हो।
        उपयोगकर्ता मुख्यतः हिंदी में बात करता है।
        सरल, प्राकृतिक और संक्षिप्त हिंदी में उत्तर दो।
        तुम किसी एक ऐप तक सीमित assistant नहीं हो। उपलब्ध Android UI, Accessibility Service, intents और installed apps के आधार पर सामान्य काम करने की कोशिश करो।
        पहले स्क्रीन की वास्तविक स्थिति पढ़ो, फिर एक छोटा atomic action करो, फिर नई स्क्रीन देखकर आगे बढ़ो।
        किसी खास ऐप के लिए hard-coded workflow मानकर मत चलो; स्क्रीन पर मिले text, content-description, view-id और layout के आधार पर निर्णय लो।
        भुगतान, OTP, पासवर्ड, खरीद, deletion, account changes और दूसरे high-impact काम बिना user confirmation के मत करो।
    """

    fun configureRemote(context: Context, endpoint: String, model: String, apiKey: String) {
        val cleanEndpoint = endpoint.trim().ifBlank { DEFAULT_ENDPOINT }.trimEnd('/')
        val uri = Uri.parse(cleanEndpoint)
        require(uri.scheme.equals("https", true) || uri.host == "localhost" || uri.host == "127.0.0.1") {
            "Remote AI endpoint के लिए HTTPS जरूरी है।"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROVIDER, PROVIDER_REMOTE)
            .putString(KEY_ENDPOINT, cleanEndpoint)
            .putString(KEY_MODEL, model.trim().ifBlank { DEFAULT_MODEL })

        if (apiKey.isNotBlank()) {
            editor.putString(KEY_API, encryptApiKey(apiKey.trim()))
        }

        editor.apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = ks.getKey(API_KEY_ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance("AES", KEYSTORE)
        generator.init(256)
        return generator.generateKey()
    }

    private fun encryptApiKey(value: String): String {
        val secret = key()
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

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

    fun configuredEndpoint(context: Context): String =
        prefs(context).getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT

    fun configuredModel(context: Context): String =
        prefs(context).getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun configureTermux(context: Context, endpoint: String = "http://127.0.0.1:8080", model: String = "/data/data/com.termux/files/home/models/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf") {
        val cleanEndpoint = endpoint.trim().ifBlank { "http://127.0.0.1:8080" }.trimEnd('/')
        val uri = Uri.parse(cleanEndpoint)
        require(uri.host == "127.0.0.1" || uri.host == "localhost") {
            "Termux Local AI के लिए अभी केवल इसी फोन का 127.0.0.1/localhost server स्वीकार है।"
        }
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "Termux AI endpoint http/https होना चाहिए।"
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROVIDER, PROVIDER_TERMUX)
            .putString(KEY_ENDPOINT, cleanEndpoint)
            .putString(KEY_MODEL, model.trim().ifBlank { "/data/data/com.termux/files/home/models/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf" })
            .apply()
    }

    fun useLocal(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PROVIDER, PROVIDER_LOCAL).apply()
    }

    fun providerStatus(context: Context): String {
        val p = prefs(context).getString(KEY_PROVIDER, PROVIDER_REMOTE) ?: PROVIDER_REMOTE
        return when (p) {
            PROVIDER_LOCAL -> {
                if (isModelReady(context)) "Local AI: Qwen3 0.6B तैयार है।" else "Local AI चुना है, लेकिन model डाउनलोड नहीं हुआ।"
            }
            PROVIDER_TERMUX -> {
                val endpoint = prefs(context).getString(KEY_ENDPOINT, "http://127.0.0.1:8080").orEmpty()
                val model = prefs(context).getString(KEY_MODEL, "/data/data/com.termux/files/home/models/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf").orEmpty()
                "Termux Local AI: $model • $endpoint"
            }
            else -> {
                val endpoint = prefs(context).getString(KEY_ENDPOINT, DEFAULT_ENDPOINT).orEmpty()
                val model = prefs(context).getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()
                val key = readApiKey(context)
                if (key.isBlank()) "Remote AI configured नहीं है। API key सेट करें।" else "Remote AI: $model • $endpoint"
            }
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
        if (p == PROVIDER_TERMUX) {
            val endpoint = prefs(context).getString(KEY_ENDPOINT, "http://127.0.0.1:8080") ?: "http://127.0.0.1:8080"
            val model = prefs(context).getString(KEY_MODEL, "/data/data/com.termux/files/home/models/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf") ?: "/data/data/com.termux/files/home/models/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
            return OpenAiCompatibleProvider(endpoint, "", model)
        }
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
        if (cleaned.isBlank()) return null

        try {
            val jsonText = cleaned.substringAfter("{", "").substringBeforeLast("}", "")
            if (jsonText.isNotBlank()) {
                val json = JSONObject("{$jsonText}")
                val action = json.optString("action").trim().lowercase()
                val argument = when {
                    json.has("argument") -> json.optString("argument").trim()
                    json.has("value") -> json.optString("value").trim()
                    json.has("text") -> json.optString("text").trim()
                    json.has("query") -> json.optString("query").trim()
                    else -> ""
                }
                if (action.isNotBlank()) return AgentAction(action, argument)
            }
        } catch (_: Exception) { }

        val actionMatch = Regex("ACTION\\s*[:=]\\s*([^\\n|]+)", RegexOption.IGNORE_CASE).find(cleaned)
        if (actionMatch != null) {
            val action = actionMatch.groupValues[1].trim().lowercase()
            val argument = Regex("(?:ARG|ARGUMENT|VALUE|TEXT)\\s*[:=]\\s*(.*)", RegexOption.IGNORE_CASE)
                .find(cleaned)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            return AgentAction(action, argument)
        }

        // Small-model natural-language fallback.
        val oneLine = cleaned.replace(Regex("\\s+"), " ").trim()
        val lower = oneLine.lowercase()
        fun afterAny(vararg prefixes: String): String =
            prefixes.firstNotNullOfOrNull { p ->
                if (lower.startsWith(p)) oneLine.substring(p.length).trim() else null
            }.orEmpty()

        when {
            lower == "done" || lower.contains("काम पूरा") || lower.contains("कार्य पूरा") ->
                return AgentAction("done", "")
            lower == "back" || lower.contains("वापस जाए") || lower.contains("वापस जाओ") ->
                return AgentAction("back", "")
            lower == "enter" || lower == "send" || lower == "भेजो" || lower.contains("एंटर") ->
                return AgentAction("enter", "")
            lower.startsWith("open_app") || lower.startsWith("open app") ->
                return AgentAction("open_app", afterAny("open_app", "open app"))
            lower.startsWith("click ") || lower.startsWith("क्लिक ") ->
                return AgentAction("click", afterAny("click ", "क्लिक "))
            lower.startsWith("click_text ") || lower.startsWith("click text ") ->
                return AgentAction("click_text", afterAny("click_text ", "click text "))
            lower.startsWith("click_desc ") || lower.startsWith("click description ") ->
                return AgentAction("click_desc", afterAny("click_desc ", "click description "))
            lower.startsWith("click_id ") || lower.startsWith("click id ") ->
                return AgentAction("click_id", afterAny("click_id ", "click id "))
            lower.startsWith("long_click ") || lower.startsWith("long click ") || lower.startsWith("लंबा क्लिक ") ->
                return AgentAction("long_click", afterAny("long_click ", "long click ", "लंबा क्लिक "))
            lower.startsWith("tap ") || lower.startsWith("टैप ") ->
                return AgentAction("tap", afterAny("tap ", "टैप "))
            lower.startsWith("long_tap ") || lower.startsWith("long tap ") ->
                return AgentAction("long_tap", afterAny("long_tap ", "long tap "))
            lower.startsWith("type ") || lower.startsWith("लिखो ") ->
                return AgentAction("type", afterAny("type ", "लिखो "))
            lower == "clear" || lower.contains("टेक्स्ट साफ") ->
                return AgentAction("clear", "")
            lower.startsWith("open_url ") || lower.startsWith("open url ") ->
                return AgentAction("open_url", afterAny("open_url ", "open url "))
            lower.startsWith("dial ") || lower.startsWith("डायल ") ->
                return AgentAction("dial", afterAny("dial ", "डायल "))
            lower.startsWith("share ") || lower.startsWith("शेयर ") ->
                return AgentAction("share", afterAny("share ", "शेयर "))
            lower.startsWith("settings ") || lower.startsWith("सेटिंग ") ->
                return AgentAction("settings", afterAny("settings ", "सेटिंग "))
            lower == "home" || lower == "होम" -> return AgentAction("home", "")
            lower == "recents" || lower.contains("हाल के ऐप") -> return AgentAction("recents", "")
            lower.contains("notifications") || lower.contains("नोटिफिकेशन") -> return AgentAction("notifications", "")
            lower.contains("quick settings") || lower.contains("क्विक सेटिंग") -> return AgentAction("quick_settings", "")
            lower.startsWith("wait") || lower.startsWith("रुको") ->
                return AgentAction("wait", "")
            lower.contains("open whatsapp") || lower.contains("व्हाट्सऐप खोल") || lower.contains("व्हाट्सएप खोल") ->
                return AgentAction("open_app", "WhatsApp")
        }
        return null
    }

    fun cancelAgent() {
        agentCancelled = true
    }

    fun runAgent(context: Context, task: String, callback: (String) -> Unit) {
        if (agentRunning) {
            callback("एक AI काम पहले से चल रहा है।")
            return
        }
        agentRunning = true
        agentCancelled = false
        executor.execute {
            var finalText = "मैं यह काम पूरा नहीं कर पाया।"
            var actionsTaken = 0
            var lastState = ""
            var repeatedStateCount = 0
            val startedAt = System.currentTimeMillis()
            try {
                for (stepIndex in 0 until AGENT_MAX_STEPS) {
                    if (agentCancelled) {
                        finalText = "AI Agent रोक दिया गया।"
                        break
                    }
                    if (System.currentTimeMillis() - startedAt > AGENT_TIMEOUT_MS) {
                        finalText = "AI Agent समय सीमा पर रुक गया।"
                        break
                    }
                    val screen = AssistantAccessibilityService.readScreen().replace(Regex("\\s+"), " ").take(5000)
                    val hash = AssistantAccessibilityService.screenshotHash()
                    val activePackage = AssistantAccessibilityService.activePackageName()
                    val state = (hash.ifBlank { screen.ifBlank { activePackage } }).take(512)
                    if (state.isNotBlank() && state == lastState) repeatedStateCount++ else repeatedStateCount = 0
                    if (repeatedStateCount >= 3) {
                        finalText = "स्क्रीन लगातार नहीं बदल रही थी, इसलिए Agent को सुरक्षित रूप से रोक दिया।"
                        break
                    }
                    lastState = state
                    val prompt = "/no_think\nकाम: " + task + "\nस्क्रीन टेक्स्ट: " + screen.ifBlank { "(खाली)" } +
                        "\nस्क्रीन hash: " + hash.ifBlank { "(उपलब्ध नहीं)" } +
                        "\nActive package: " + activePackage.ifBlank { "(उपलब्ध नहीं)" } +
                        "\nस्टेप: " + stepIndex + "\nकेवल अगला atomic action दो। JSON या ACTION=... format स्वीकार है। " +
                        "Allowed: open_app, click, click_text, click_desc, click_id, long_click, tap, long_tap, type, clear, enter, back, home, recents, notifications, quick_settings, " +
                        "swipe_up, swipe_down, wait, open_url, dial, share, settings, read_screen, screenshot, done, answer. " +
                        "tap/long_tap में argument 'x,y' हो सकता है; x,y 0..1 normalized या screen pixels हो सकते हैं। " +
                        "हर action के बाद नई स्क्रीन देखकर ही अगला निर्णय लो। बिना evidence के done मत दो। सिर्फ ऐप खोलने का आदेश हो तो ऐप launch होना ही पर्याप्त सफलता है।"
                    val raw = provider(context).complete(context, SYSTEM_PROMPT, prompt, 90)
                    val parsed = parseAgentAction(raw)
                    if (parsed == null) {
                        finalText = "Agent ने वैध action नहीं दिया।"
                        break
                    }
                    when (parsed.action) {
                        "done" -> {
                            finalText = parsed.argument.ifBlank { "काम पूरा हो गया।" }
                            if (actionsTaken == 0) finalText = "Agent ने कोई action किए बिना काम पूरा बताया, इसलिए सफलता नहीं मानी गई।"
                            else break
                        }
                        "answer" -> {
                            finalText = parsed.argument.ifBlank { "मैं यह काम पूरा नहीं कर पाया।" }
                            break
                        }
                        "wait" -> Thread.sleep(350)
                        else -> {
                            val before = state
                            finalText = CommandEngine.executeAgentAction(context, parsed.action, parsed.argument)
                            actionsTaken++
                            var afterHash = ""
                            var afterScreen = ""
                            var afterPackage = ""
                            repeat(5) { attempt ->
                                Thread.sleep(if (attempt == 0) 300L else 400L)
                                afterHash = AssistantAccessibilityService.screenshotHash()
                                afterScreen = AssistantAccessibilityService.readScreen().replace(Regex("\\s+"), " ").take(1200)
                                afterPackage = AssistantAccessibilityService.activePackageName()
                                if (afterScreen.isNotBlank() || afterHash.isNotBlank() || afterPackage.isNotBlank()) return@repeat
                            }
                            val failed = finalText.contains("नहीं") || finalText.contains("उपलब्ध नहीं") || finalText.contains("अज्ञात")
                            if (failed) {
                                finalText = "Action '" + parsed.action + "' सफल नहीं हुआ: " + finalText
                                break
                            }
                            if (afterScreen.isBlank() && afterHash.isBlank() && afterPackage.isBlank()) {
                                // Accessibility hierarchy can be temporarily unavailable while an app/window is changing.
                                // Do not falsely report failure; the next agent step will retry reading the active screen.
                                Thread.sleep(500)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                finalText = "AI Agent रुक गया: " + (e.message ?: "अज्ञात त्रुटि")
            } finally {
                agentRunning = false
            }
            Handler(Looper.getMainLooper()).post { callback(finalText) }
        }
    }
}

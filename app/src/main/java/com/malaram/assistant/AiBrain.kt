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
import kotlinx.coroutines.runBlocking

object AiBrain {
    private val executor = Executors.newSingleThreadExecutor()
    private var cachedModel: dev.ffmpegkit.llama.LlamaModel? = null

    const val MODEL_FILE = "Qwen3-1.7B-Q4_K_M.gguf"
    const val MODEL_SIZE_BYTES = 1_282_439_264L

    private const val MODEL_URL =
        "https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf?download=true"

    private const val PREFS = "local_ai"
    private const val DOWNLOAD_ID = "download_id"

    private const val SYSTEM_PROMPT = """
        तुम Malaram Personal Assistant के Local AI Brain हो।
        उपयोगकर्ता मुख्यतः हिंदी में बात करता है।
        सरल, प्राकृतिक और संक्षिप्त हिंदी में उत्तर दो।
        फोन action के लिए अनुमान लगाकर संवेदनशील काम मत करो।
        भुगतान, OTP, पासवर्ड, खरीद, deletion और account changes में पुष्टि जरूरी है।
    """

    fun modelFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, MODEL_FILE)
    }

    fun isModelReady(context: Context): Boolean {
        val file = modelFile(context)
        return file.exists() && file.length() == MODEL_SIZE_BYTES
    }

    fun startModelDownload(context: Context): Long {
        if (isModelReady(context)) return 0L

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldId = prefs.getLong(DOWNLOAD_ID, -1L)

        if (oldId != -1L) {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.query(DownloadManager.Query().setFilterById(oldId)).use { cursor ->
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )
                    if (status == DownloadManager.STATUS_PENDING ||
                        status == DownloadManager.STATUS_RUNNING
                    ) return oldId
                }
            }
        }

        val file = modelFile(context)
        if (file.exists() && file.length() != MODEL_SIZE_BYTES) file.delete()

        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("Malaram Assistant • Local AI")
            .setDescription("Qwen3 1.7B model • लगभग 1.28 GB")
            .setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or
                    DownloadManager.Request.NETWORK_MOBILE
            )
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                MODEL_FILE
            )

        val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
            .enqueue(request)

        prefs.edit().putLong(DOWNLOAD_ID, id).apply()
        return id
    }

    fun downloadStatus(context: Context): String {
        if (isModelReady(context)) return "Local Qwen3 AI तैयार है।"

        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(DOWNLOAD_ID, -1L)
        if (id == -1L) return "Local AI model अभी डाउनलोड नहीं हुआ है।"

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return "Local AI download शुरू नहीं हुआ।"

            val status = cursor.getInt(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            )
            val downloaded = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            )
            val total = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            )

            return when (status) {
                DownloadManager.STATUS_PENDING -> "Local AI download queue में है…"
                DownloadManager.STATUS_RUNNING -> {
                    if (total > 0) {
                        "Local AI डाउनलोड हो रहा है… " +
                            (downloaded / 1_000_000) + " / " +
                            (total / 1_000_000) + " MB"
                    } else {
                        "Local AI डाउनलोड हो रहा है…"
                    }
                }
                DownloadManager.STATUS_SUCCESSFUL ->
                    if (isModelReady(context)) "Local Qwen3 AI तैयार है।"
                    else "Download पूरा हुआ, लेकिन model verify नहीं हुआ।"
                DownloadManager.STATUS_FAILED ->
                    "Local AI download fail हुआ। फिर से डाउनलोड करें।"
                else -> "Local AI download स्थिति: " + status
            }
        }
    }

    private suspend fun loadModel(context: Context): dev.ffmpegkit.llama.LlamaModel {
        if (!isModelReady(context)) {
            throw IllegalStateException(
                "Local Qwen3 model अभी तैयार नहीं है। पहले Local AI model डाउनलोड करें।"
            )
        }

        cachedModel?.let { return it }
        return Llama.loadModel(
            modelPath = modelFile(context).absolutePath,
            config = LlamaConfig(
                contextSize = 2048,
                threads = Runtime.getRuntime().availableProcessors().coerceIn(4, 8),
                gpuLayers = 0,
                temperature = 0.1f,
                topP = 0.8f,
                topK = 20
            )
        ).also { cachedModel = it }
    }

    fun releaseModel() {
        cachedModel?.let { try { Llama.releaseModel(it) } catch (_: Exception) {} }
        cachedModel = null
    }

    fun ask(context: Context, userText: String, callback: (String) -> Unit) {
        executor.execute {
            val answer = try {
                runBlocking {
                    val model = loadModel(context)
                    try {
                        Llama.complete(
                            model,
                            prompt = userText,
                            systemPrompt = SYSTEM_PROMPT,
                            maxTokens = 128
                        ).text
                    } finally {
                        Llama.releaseModel(model)
                    }
                }
            } catch (e: Exception) {
                "Local AI से जवाब नहीं मिल पाया: " + (e.message ?: "अज्ञात त्रुटि")
            }

            Handler(Looper.getMainLooper()).post { callback(answer) }
        }
    }

    private data class AgentAction(val action: String, val argument: String)

    private fun parseAgentAction(raw: String): AgentAction? {
        val cleaned = raw.trim()
        val actionMatch = Regex("""["']?action["']?\\s*[:=]\\s*["']([^"'\\n]+)["']""", RegexOption.IGNORE_CASE).find(cleaned)
        val argMatch = Regex("""["']?argument["']?\\s*[:=]\\s*["']([^"'\\n]*)["']""", RegexOption.IGNORE_CASE).find(cleaned)
        val action = actionMatch?.groupValues?.getOrNull(1)?.trim()?.lowercase()
            ?: Regex("""ACTION\\s*=\\s*([^\\n|]+)""", RegexOption.IGNORE_CASE).find(cleaned)?.groupValues?.getOrNull(1)?.trim()?.lowercase()
            ?: return null
        val argument = argMatch?.groupValues?.getOrNull(1)?.trim()
            ?: Regex("""(?:ARG|ARGUMENT)\\s*=\\s*(.*)""", RegexOption.IGNORE_CASE).find(cleaned)?.groupValues?.getOrNull(1)?.trim()
            ?: ""
        return AgentAction(action, argument)
    }

    fun runAgent(context: Context, task: String, callback: (String) -> Unit) {
        executor.execute {
            var finalText = "मैं यह काम पूरा नहीं कर पाया।"
            try {
                runBlocking {
                    val model = loadModel(context)
                    val history = mutableListOf<String>()
                    for (stepIndex in 0 until 4) {
                        val screen = AssistantAccessibilityService.readScreen()
                            .replace(Regex("\\s+"), " ")
                            .take(2500)
                        val prompt = "/no_think\nकाम: " + task +
                            "\nस्क्रीन: " + screen.ifBlank { "(खाली)" } +
                            "\nपिछले कदम: " + history.joinToString(" | ").ifBlank { "(पहला कदम)" } +
                            "\nकेवल अगला action दो। JSON या ACTION=... format स्वीकार है। " +
                            "उदाहरण: {\"action\":\"click\",\"argument\":\"Search\"}. " +
                            "Allowed: open_app, click, type, enter, back, swipe_up, swipe_down, wait, done, answer. " +
                            "ऐप खुलना लक्ष्य नहीं है; पूरा काम करो। done तभी दो जब काम पूरा हो।"
                        val raw = Llama.complete(model, prompt = prompt, systemPrompt = SYSTEM_PROMPT, maxTokens = 80).text
                        val parsed = parseAgentAction(raw)
                        if (parsed == null) {
                            finalText = "Local Agent ने action नहीं समझा।"
                            break
                        }
                        when (parsed.action) {
                            "done" -> {
                                finalText = parsed.argument.ifBlank { "काम पूरा हो गया।" }
                                break
                            }
                            "answer" -> {
                                finalText = parsed.argument.ifBlank { "मैं यह काम पूरा नहीं कर पाया।" }
                                break
                            }
                            "wait" -> {
                                Thread.sleep(250)
                                history += "wait"
                            }
                            else -> {
                                val result = CommandEngine.executeAgentAction(context, parsed.action, parsed.argument)
                                history += parsed.action + " -> " + result
                                finalText = result
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                finalText = "Local Agent रुक गया: " + (e.message ?: "अज्ञात त्रुटि")
            }
            Handler(Looper.getMainLooper()).post { callback(finalText) }
        }
    }
}

package com.malaram.assistant

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

object AiBrain {
    private val executor = Executors.newSingleThreadExecutor()

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

        return Llama.loadModel(
            modelPath = modelFile(context).absolutePath,
            config = LlamaConfig(
                contextSize = 4096,
                threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
                gpuLayers = 0,
                temperature = 0.2f,
                topP = 0.9f,
                topK = 20
            )
        )
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
                            maxTokens = 256
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

    fun runAgent(context: Context, task: String, callback: (String) -> Unit) {
        executor.execute {
            var finalText = "मैं यह काम पूरा नहीं कर पाया।"

            try {
                runBlocking {
                    val model = loadModel(context)
                    try {
                        val history = mutableListOf<String>()

                        for (stepIndex in 0 until 8) {
                            val screen = AssistantAccessibilityService.readScreen()
                            val prompt = """
                                /no_think
                                उपयोगकर्ता का पूरा काम:
                                """ + task + """

                                अभी फोन की स्क्रीन Accessibility tree से:
                                """ + screen.ifBlank {
                                    "(स्क्रीन का टेक्स्ट उपलब्ध नहीं है)"
                                } + """

                                अब तक के कदम:
                                """ + history.joinToString("\n").ifBlank {
                                    "(कोई कदम नहीं)"
                                } + """

                                तुम फोन-agent हो। केवल अगला एक atomic action चुनो।
                                JSON के अलावा कुछ मत लिखो।
                                Schema:
                                {"action":"open_app|click|type|enter|back|swipe_up|swipe_down|wait|done|answer","argument":"..."}

                                नियम:
                                - ऐप का नाम देखकर केवल ऐप खोलकर मत रुकना; पूरा लक्ष्य पूरा करो।
                                - स्क्रीन में दिख रहे text/content-description को click के argument में इस्तेमाल करो।
                                - search के लिए click, फिर type, फिर enter जैसे छोटे कदम दो।
                                - लक्ष्य वास्तव में पूरा हो जाए तभी done दो।
                                - Accessibility उपलब्ध नहीं है या स्क्रीन पढ़ी नहीं जा रही है तो answer में स्पष्ट कारण दो।
                                - action हमेशा JSON string होना चाहिए।
                            """.trimIndent()

                            val raw = Llama.complete(
                                model,
                                prompt = prompt,
                                systemPrompt = SYSTEM_PROMPT,
                                maxTokens = 180
                            ).text

                            val jsonText = raw
                                .substringAfter("{", raw)
                                .substringBeforeLast("}", raw)
                                .trim()

                            val obj = JSONObject(jsonText)
                            val actionValue = obj.opt("action")
                            val action = when (actionValue) {
                                is String -> actionValue.trim().lowercase()
                                is JSONObject -> actionValue
                                    .optString("value", actionValue.optString("name"))
                                    .trim().lowercase()
                                else -> ""
                            }

                            val argumentValue = obj.opt("argument")
                            val argument = when (argumentValue) {
                                is String -> argumentValue.trim()
                                JSONObject.NULL -> ""
                                null -> ""
                                else -> argumentValue.toString().trim()
                            }

                            if (action.isBlank()) {
                                finalText = "Local Agent ने सही action format नहीं दिया।"
                                break
                            }

                            when (action) {
                                "done" -> {
                                    finalText = argument.ifBlank { "काम पूरा हो गया।" }
                                    break
                                }
                                "answer" -> {
                                    finalText = argument.ifBlank {
                                        "मैं यह काम पूरा नहीं कर पाया।"
                                    }
                                    break
                                }
                                "wait" -> {
                                    Thread.sleep(900)
                                    history += "wait"
                                }
                                else -> {
                                    val result = CommandEngine.executeAgentAction(
                                        context,
                                        action,
                                        argument
                                    )
                                    history += action + " -> " + result
                                    finalText = result
                                    Thread.sleep(900)
                                }
                            }
                        }
                    } finally {
                        Llama.releaseModel(model)
                    }
                }
            } catch (e: Exception) {
                finalText = "Local Agent रुक गया: " + (e.message ?: "अज्ञात त्रुटि")
            }

            Handler(Looper.getMainLooper()).post { callback(finalText) }
        }
    }
}

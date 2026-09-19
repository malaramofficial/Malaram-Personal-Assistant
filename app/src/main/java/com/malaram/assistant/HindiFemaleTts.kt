package com.malaram.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class HindiFemaleTts(private val context: Context) {
    companion object {
        private const val MODEL_DIR_NAME = "vits-piper-hi_IN-priyamvada-medium"
        private const val MODEL_FILE_NAME = "hi_IN-priyamvada-medium.onnx"
        private const val MODEL_ARCHIVE = "$MODEL_DIR_NAME.tar.bz2"
        private const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$MODEL_ARCHIVE"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: OfflineTts? = null

    fun prepare(onStatus: (String) -> Unit, onReady: (Boolean) -> Unit) {
        executor.execute {
            try {
                val root = File(context.filesDir, MODEL_DIR_NAME)
                val modelFile = File(root, MODEL_FILE_NAME)
                if (!modelFile.exists()) {
                    mainHandler.post { onStatus("पहली बार महिला आवाज डाउनलोड हो रही है…") }
                    val archive = File(context.cacheDir, MODEL_ARCHIVE)
                    download(MODEL_URL, archive)
                    mainHandler.post { onStatus("महिला आवाज तैयार की जा रही है…") }
                    extractTarBz2(archive, context.filesDir)
                    archive.delete()
                }

                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = modelFile.absolutePath,
                            tokens = File(root, "tokens.txt").absolutePath,
                            dataDir = File(root, "espeak-ng-data").absolutePath
                        ),
                        numThreads = 2,
                        debug = false,
                        provider = "cpu"
                    ),
                    maxNumSentences = 1
                )
                tts?.release()
                tts = OfflineTts(config = config)
                mainHandler.post {
                    onStatus("महिला हिंदी आवाज तैयार है")
                    onReady(true)
                }
            } catch (e: Exception) {
                val error = e.message ?: "अज्ञात त्रुटि"
                mainHandler.post {
                    onStatus("महिला आवाज तैयार नहीं हो सकी: " + error)
                    onReady(false)
                }
            }
        }
    }

    fun speak(text: String, speed: Float = 1.0f) {
        executor.execute {
            try {
                val engine = tts ?: return@execute
                val audio = engine.generateWithConfig(text, GenerationConfig(sid = 0, speed = speed))
                play(audio.samples, audio.sampleRate)
            } catch (_: Exception) { }
        }
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val bufferSize = maxOf(minBuffer, samples.size * 4)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            Thread.sleep((samples.size * 1000L / sampleRate) + 100L)
        } finally {
            track.stop()
            track.release()
        }
    }

    private fun download(urlString: String, destination: File) {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP " + connection.responseCode)
            destination.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally { connection.disconnect() }
    }

    private fun extractTarBz2(archive: File, destinationRoot: File) {
        BZip2CompressorInputStream(FileInputStream(archive)).use { bz2 ->
            TarArchiveInputStream(bz2).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val output = File(destinationRoot, entry.name)
                    val canonicalRoot = destinationRoot.canonicalFile
                    val canonicalOutput = output.canonicalFile
                    if (!canonicalOutput.path.startsWith(canonicalRoot.path + File.separator)) throw SecurityException("Unsafe archive path")
                    if (entry.isDirectory) canonicalOutput.mkdirs()
                    else {
                        canonicalOutput.parentFile?.mkdirs()
                        FileOutputStream(canonicalOutput).use { out -> tar.copyTo(out) }
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
    }

    fun shutdown() {
        executor.execute { tts?.release() }
        executor.shutdown()
    }
}
package com.malaram.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.ServiceCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

class WakeWordService : Service() {
    companion object {
        const val ACTION_START = "com.malaram.assistant.action.START_WAKE_WORD"
        const val ACTION_STOP = "com.malaram.assistant.action.STOP_WAKE_WORD"
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 1101
        private const val SAMPLE_RATE = 16000
        private const val KEYWORD_FILE = "wakeword/keywords.txt"
        private const val TOKENS_FILE = "wakeword/tokens.txt"
        private const val ENCODER_FILE = "wakeword/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val DECODER_FILE = "wakeword/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val JOINER_FILE = "wakeword/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var kws: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var recorder: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var wakeListening = false
    @Volatile private var waitingCommand = false
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var tts: HindiFemaleTts
    private lateinit var fallbackSpeaker: AssistantSpeaker
    private var ttsReady = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Assistant शुरू हो रहा है…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        tts = HindiFemaleTts(this)
        fallbackSpeaker = AssistantSpeaker(this)
        tts.prepare(
            onStatus = { updateNotification(it) },
            onReady = { ready ->
                ttsReady = ready
                updateNotification(if (ready) "“Hello Assistant” सुन रहा हूँ।" else "Fallback आवाज तैयार है।")
            }
        )
        initKws()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START || intent == null) {
            startWakeListening()
        }
        return START_STICKY
    }

    private fun initKws() {
        try {
            kws = KeywordSpotter(
                assetManager = assets,
                config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = ENCODER_FILE, decoder = DECODER_FILE, joiner = JOINER_FILE
                        ),
                        tokens = TOKENS_FILE, numThreads = 1, debug = false,
                        provider = "cpu", modelType = "zipformer"
                    ),
                    keywordsFile = KEYWORD_FILE, keywordsScore = 1.0f,
                    keywordsThreshold = 0.25f, numTrailingBlanks = 1
                )
            )
            updateNotification("Wake word model तैयार है।")
        } catch (e: Exception) {
            Log.e("MalaramWakeWord", "KWS init failed", e)
            updateNotification("Wake word model लोड नहीं हुआ।")
        }
    }

    private fun startWakeListening() {
        if (wakeListening || waitingCommand) return
        if (kws == null) {
            updateNotification("Wake word model उपलब्ध नहीं है।")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Microphone permission जरूरी है।")
            return
        }
        val spotter = kws ?: return
        try {
            val min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (min <= 0) return
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, SAMPLE_RATE / 2)
            )
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                cleanupAudio()
                updateNotification("Microphone शुरू नहीं हो सका।")
                return
            }
            stream?.release()
            stream = spotter.createStream()
            recorder?.startRecording()
            if (recorder?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                cleanupAudio()
                updateNotification("Microphone recording शुरू नहीं हुई।")
                return
            }
            wakeListening = true
            updateNotification("“Hello Assistant” standby में है।")
            recordingThread = Thread {
                val localStream = stream ?: return@Thread
                val buffer = ShortArray(SAMPLE_RATE / 10)
                while (wakeListening && !waitingCommand) {
                    val r = recorder?.read(buffer, 0, buffer.size) ?: break
                    if (r <= 0) continue
                    localStream.acceptWaveform(FloatArray(r) { buffer[it] / 32768.0f }, SAMPLE_RATE)
                    while (spotter.isReady(localStream) && wakeListening && !waitingCommand) {
                        spotter.decode(localStream)
                        if (spotter.getResult(localStream).keyword.isNotBlank()) {
                            spotter.reset(localStream)
                            onWakeWord()
                            return@Thread
                        }
                    }
                }
            }.also { it.start() }
        } catch (e: Exception) {
            Log.e("MalaramWakeWord", "Wake listening failed", e)
            cleanupAudio()
            updateNotification("Wake word शुरू नहीं हो सका।")
        }
    }

    private fun onWakeWord() {
        wakeListening = false
        waitingCommand = true
        cleanupAudio()
        updateNotification("जाग गया। आदेश सुन रहा हूँ…")
        handler.postDelayed({ startCommandRecognition() }, 700L)
    }

    private fun startCommandRecognition() {
        if (!waitingCommand) return
        try {
            speechRecognizer?.destroy()
            speechRecognizer = if (
                Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
            ) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            else SpeechRecognizer.createSpeechRecognizer(this)

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { updateNotification("आदेश सुन रहा हूँ…") }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { updateNotification("आदेश समझ रहा हूँ…") }
                override fun onError(error: Int) { finishCommand("आवाज़ साफ़ नहीं मिली।") }
                override fun onResults(results: Bundle?) {
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    if (heard.isNullOrBlank()) finishCommand("मैं आदेश समझ नहीं पाया।") else executeCommand(heard)
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            speechRecognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
        } catch (e: Exception) {
            Log.e("MalaramWakeWord", "Recognizer start failed", e)
            finishCommand("आवाज़ पहचान सेवा उपलब्ध नहीं है।")
        }
    }

    private fun executeCommand(heard: String) {
        val result = try {
            CommandEngine.executeResult(this, heard)
        } catch (e: Exception) {
            Log.e("MalaramWakeWord", "Command failed", e)
            CommandEngine.Result("इस आदेश को पूरा करते समय त्रुटि हुई।")
        }
        CommandHistory(this).add(heard, result.text)

        when {
            result.needsAgent -> {
                updateNotification("AI agent स्क्रीन समझ रहा है…")
                AiBrain.runAgent(this, heard) { answer ->
                    CommandHistory(this).add(heard, answer)
                    finishCommand(answer)
                }
            }
            result.needsAi -> {
                updateNotification("AI सोच रहा है…")
                AiBrain.ask(this, heard) { answer ->
                    CommandHistory(this).add(heard, answer)
                    finishCommand(answer)
                }
            }
            else -> finishCommand(result.text)
        }
    }

    private fun finishCommand(response: String) {
        speechRecognizer?.destroy()
        speechRecognizer = null
        waitingCommand = false
        if (response.isNotBlank()) {
            if (ttsReady) tts.speak(response) else fallbackSpeaker.speak(response)
        }
        updateNotification("“Hello Assistant” standby में है।")
        handler.postDelayed({ if (!waitingCommand) startWakeListening() }, 1800L)
    }

    private fun cleanupAudio() {
        wakeListening = false
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { stream?.release() } catch (_: Exception) {}
        stream = null
        recordingThread = null
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Malaram Personal Assistant")
            .setContentText(text).setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE).build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Assistant Wake Word", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        wakeListening = false
        waitingCommand = false
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        cleanupAudio()
        try { kws?.release() } catch (_: Exception) {}
        kws = null
        tts.shutdown()
        fallbackSpeaker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

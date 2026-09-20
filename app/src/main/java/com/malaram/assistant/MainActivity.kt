package com.malaram.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var femaleTts: HindiFemaleTts
    private lateinit var speaker: AssistantSpeaker
    private lateinit var history: CommandHistory
    private lateinit var wakeWordButton: Button
    private lateinit var localAiButton: Button
    private var ttsReady = false

    companion object {
        private const val REQUEST_RECORD_AUDIO = 21
        private const val PREFS = "assistant_settings"
        private const val WAKE_ENABLED = "wake_word_enabled"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        femaleTts = HindiFemaleTts(this)
        speaker = AssistantSpeaker(this)
        history = CommandHistory(this)
        wakeWordButton = findViewById(R.id.wakeWordButton)
        localAiButton = findViewById(R.id.localAiButton)

        findViewById<Button>(R.id.listenButton).setOnClickListener { listen() }
        findViewById<Button>(R.id.testVoiceButton).setOnClickListener { speak("नमस्ते माला राम जी, मैं आपकी पर्सनल असिस्टेंट हूँ। बताइए, मैं आपके लिए क्या करूँ?") }
        findViewById<Button>(R.id.aiSettingsButton).setOnClickListener { showAiSettings() }
        localAiButton.setOnClickListener { downloadOrCheckLocalAi() }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        findViewById<Button>(R.id.notificationButton).setOnClickListener { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        findViewById<Button>(R.id.voiceAssistantButton).setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            catch (_: Exception) { Toast.makeText(this, "Voice Assistant settings उपलब्ध नहीं हैं।", Toast.LENGTH_SHORT).show() }
        }
        wakeWordButton.setOnClickListener { toggleWakeWord() }
        NotificationReplyStore.restore(this)

        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        status.text = "महिला हिंदी आवाज तैयार की जा रही है…"
        femaleTts.prepare(onStatus = { status.text = it }, onReady = {
            ttsReady = it
            if (it) speak("नमस्ते माला राम जी, मैं आपकी पर्सनल असिस्टेंट हूँ।")
        })
        updateWakeWordButton()
        updateLocalAiButton()
    }

    override fun onResume() {
        super.onResume()
        updateLocalAiButton()
    }

    private fun downloadOrCheckLocalAi() {
        if (AiBrain.isModelReady(this)) {
            AiBrain.useLocal(this)
            status.text = "Optional Local AI तैयार है। अब Local AI चुना गया है।"
            updateLocalAiButton()
            return
        }
        try {
            AiBrain.startModelDownload(this)
            status.text = "Optional Local AI download शुरू हो गया। लगभग 484 MB है।"
            Toast.makeText(this, "Qwen3 0.6B download शुरू हो गया।", Toast.LENGTH_LONG).show()
            updateLocalAiButton()
        } catch (e: Exception) {
            status.text = "Local AI download शुरू नहीं हुआ: " + (e.message ?: "अज्ञात त्रुटि")
        }
    }

    private fun updateLocalAiButton() {
        localAiButton.text = if (AiBrain.isModelReady(this)) "✅ OPTIONAL LOCAL AI • 0.6B तैयार" else "🤖 OPTIONAL LOCAL AI डाउनलोड • ~484 MB"
    }

    private fun showAiSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 10, 40, 0)
        }
        val endpoint = EditText(this).apply {
            hint = "OpenAI-compatible endpoint"
            setText("https://api.openai.com")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val model = EditText(this).apply {
            hint = "Model name"
            setText("gpt-4o-mini")
        }
        val apiKey = EditText(this).apply {
            hint = "API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(endpoint)
        box.addView(model)
        box.addView(apiKey)
        box.addView(TextView(this).apply {
            text = "\nRemote AI default है। Local AI अलग से optional है और अब 1.28 GB वाला model नहीं है।"
        })

        AlertDialog.Builder(this)
            .setTitle("🧠 AI Brain Settings")
            .setView(box)
            .setPositiveButton("Remote AI सेव करें") { _, _ ->
                AiBrain.configureRemote(this, endpoint.text.toString(), model.text.toString(), apiKey.text.toString())
                status.text = AiBrain.providerStatus(this)
            }
            .setNeutralButton("Local AI चुनें") { _, _ ->
                if (AiBrain.isModelReady(this)) {
                    AiBrain.useLocal(this)
                    status.text = AiBrain.providerStatus(this)
                } else {
                    Toast.makeText(this, "पहले Optional Local AI download करें।", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("बंद", null)
            .show()
    }

    private fun toggleWakeWord() {
        if (isWakeWordEnabled()) {
            stopService(Intent(this, WakeWordService::class.java).apply { action = WakeWordService.ACTION_STOP })
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(WAKE_ENABLED, false).apply()
            status.text = "Hello Assistant बंद है."
            updateWakeWordButton()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            status.text = "पहले Microphone permission दें।"
            return
        }
        startWakeWordService()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(WAKE_ENABLED, true).apply()
        status.text = "Hello Assistant wake word चालू है।"
        updateWakeWordButton()
    }

    private fun startWakeWordService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java).apply { action = WakeWordService.ACTION_START })
        } catch (e: Exception) { status.text = "Hands-free mode शुरू नहीं हुआ: " + (e.message ?: "अज्ञात त्रुटि") }
    }

    private fun isWakeWordEnabled() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(WAKE_ENABLED, false)

    private fun updateWakeWordButton() {
        wakeWordButton.text = if (isWakeWordEnabled()) "🟢 Hello Assistant बंद करें" else "🎙️ Hello Assistant चालू करें"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleWakeWord()
    }

    private fun listen() {
        try {
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "माला राम जी, आदेश बोलिए")
            }, 100)
        } catch (_: Exception) { speak("आवाज़ पहचान सेवा उपलब्ध नहीं है।") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100 || resultCode != RESULT_OK) return
        val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim() ?: return
        handleHeard(heard)
    }

    private fun handleHeard(heard: String) {
        status.text = "सुना: $heard"
        val result = CommandEngine.executeResult(this, heard)
        history.add(heard, result.text)
        if (result.needsAgent) {
            status.text = "AI agent स्क्रीन समझ रहा है…"
            speak(result.text)
            AiBrain.runAgent(this, heard) { answer -> history.add(heard, answer); speak(answer) }
        } else if (result.needsAi) {
            status.text = "AI सोच रहा है…"
            AiBrain.ask(this, heard) { answer -> history.add(heard, answer); speak(answer) }
        } else speak(result.text)
    }

    private fun speak(message: String) {
        status.text = message
        if (ttsReady) femaleTts.speak(message) else speaker.speak(message)
    }

    override fun onDestroy() {
        femaleTts.shutdown()
        speaker.shutdown()
        super.onDestroy()
    }
}

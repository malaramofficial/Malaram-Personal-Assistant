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

        findViewById<Button>(R.id.listenButton).setOnClickListener { listen() }
        findViewById<Button>(R.id.testVoiceButton).setOnClickListener {
            speak("नमस्ते माला राम जी, मैं आपकी पर्सनल असिस्टेंट हूँ। बताइए, मैं आपके लिए क्या करूँ?")
        }
        findViewById<Button>(R.id.aiSettingsButton).setOnClickListener { showAiSettings() }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.notificationButton).setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        findViewById<Button>(R.id.voiceAssistantButton).setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            catch (_: Exception) { Toast.makeText(this, "Voice Assistant settings उपलब्ध नहीं हैं।", Toast.LENGTH_SHORT).show() }
        }
        wakeWordButton.setOnClickListener { toggleWakeWord() }
        NotificationReplyStore.restore(this)

        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)

        status.text = "महिला हिंदी आवाज तैयार की जा रही है…"
        femaleTts.prepare(
            onStatus = { status.text = it },
            onReady = {
                ttsReady = it
                if (it) speak("नमस्ते माला राम जी, मैं आपकी पर्सनल असिस्टेंट हूँ।")
            }
        )
        updateWakeWordButton()
    }

    override fun onResume() {
        super.onResume()
        // Diagnostic safety: do not auto-restart the microphone/native wake-word
        // service when the Activity is reopened. If the service crashes, an
        // auto-restart here would create an endless crash loop. Wake word is
        // started only by an explicit user action until the startup path is
        // validated on-device.
    }

    private fun toggleWakeWord() {
        if (isWakeWordEnabled()) {
            stopService(Intent(this, WakeWordService::class.java).apply { action = WakeWordService.ACTION_STOP })
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(WAKE_ENABLED, false).apply()
            status.text = "Hello Assistant बंद है।"
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
            ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_START
            })
        } catch (e: Exception) {
            status.text = "Hands-free mode शुरू नहीं हुआ: " + (e.message ?: "अज्ञात त्रुटि")
        }
    }

    private fun showAiSettings() {
        val input = EditText(this).apply {
            hint = "Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(getSharedPreferences("assistant_ai", MODE_PRIVATE).getString("gemini_api_key", ""))
        }
        AlertDialog.Builder(this)
            .setTitle("🧠 Assistant AI Brain")
            .setMessage("API key डालने पर सामान्य सवालों के लिए AI Brain जवाब देगा। संवेदनशील phone actions अलग safety layer से गुजरेंगे।")
            .setView(input)
            .setPositiveButton("सेव करें") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isBlank()) { AiBrain.clearApiKey(this); Toast.makeText(this, "AI key हटा दी गई।", Toast.LENGTH_SHORT).show() }
                else { AiBrain.setApiKey(this, key); Toast.makeText(this, "AI Brain तैयार है।", Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton("रद्द", null).show()
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
            AiBrain.runAgent(this, heard) { answer ->
                history.add(heard, answer)
                speak(answer)
            }
        } else if (result.needsAi) {
            status.text = "AI सोच रहा है…"
            AiBrain.ask(this, heard) { answer ->
                history.add(heard, answer)
                speak(answer)
            }
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
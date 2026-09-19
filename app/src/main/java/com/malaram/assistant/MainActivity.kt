package com.malaram.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.TextView
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var femaleTts: HindiFemaleTts
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
        wakeWordButton.setOnClickListener { toggleWakeWord() }

        NotificationReplyStore.restore(this)

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }

        status.text = "महिला हिंदी आवाज तैयार की जा रही है…"
        femaleTts.prepare(
            onStatus = { message -> status.text = message },
            onReady = { ready ->
                ttsReady = ready
                if (ready) {
                    speak("नमस्ते माला राम जी, मैं आपकी पर्सनल असिस्टेंट हूँ।")
                }
            }
        )

        updateWakeWordButton()
    }

    override fun onResume() {
        super.onResume()
        if (isWakeWordEnabled() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startWakeWordService()
        }
    }

    private fun toggleWakeWord() {
        if (isWakeWordEnabled()) {
            stopService(Intent(this, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_STOP
            })
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(WAKE_ENABLED, false)
                .apply()
            status.text = "Wake word बंद है।"
            updateWakeWordButton()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            status.text = "पहले Microphone permission दें।"
            return
        }

        try {
            startWakeWordService()
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(WAKE_ENABLED, true)
                .apply()
            status.text = "“Hello Assistant” wake word चालू है।"
            updateWakeWordButton()
        } catch (e: Exception) {
            status.text = "Wake word शुरू नहीं हो सका: " + (e.message ?: "अज्ञात त्रुटि")
        }
    }

    private fun startWakeWordService() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, WakeWordService::class.java).apply {
                    action = WakeWordService.ACTION_START
                }
            )
        } catch (e: Exception) {
            status.text = "Hands-free mode शुरू नहीं हुआ: " + (e.message ?: "अज्ञात त्रुटि")
        }
    }

    private fun showAiSettings() {
        val input = EditText(this).apply {
            hint = "Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(
                getSharedPreferences("assistant_ai", MODE_PRIVATE)
                    .getString("gemini_api_key", "")
            )
        }

        AlertDialog.Builder(this)
            .setTitle("🧠 Assistant AI Brain")
            .setMessage("Gemini API key डालने के बाद Assistant सामान्य सवालों का AI जवाब देगा।")
            .setView(input)
            .setPositiveButton("सेव करें") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isBlank()) {
                    AiBrain.clearApiKey(this)
                    Toast.makeText(this, "AI key हटा दी गई।", Toast.LENGTH_SHORT).show()
                } else {
                    AiBrain.setApiKey(this, key)
                    Toast.makeText(this, "AI Brain तैयार है।", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("रद्द", null)
            .show()
    }

    private fun isWakeWordEnabled(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(WAKE_ENABLED, false)

    private fun updateWakeWordButton() {
        wakeWordButton.text = if (isWakeWordEnabled()) {
            "🟢 Hello Assistant बंद करें"
        } else {
            "🎙️ Hello Assistant चालू करें"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            toggleWakeWord()
        }
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "माला राम जी, आदेश बोलिए")
        }
        try {
            startActivityForResult(intent, 100)
        } catch (_: Exception) {
            speak("आवाज़ पहचान सेवा उपलब्ध नहीं है।")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100 || resultCode != RESULT_OK) return

        val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull() ?: return

        status.text = "सुना: $heard"
        val response = CommandEngine.execute(this, heard)
        history.add(heard, response)
        if (response.startsWith("मैंने सुना:")) {
            status.text = "AI सोच रहा है…"
            AiBrain.ask(this, heard) { answer ->
                history.add(heard, answer)
                speak(answer)
            }
        } else {
            speak(response)
        }
    }

    private fun speak(message: String) {
        status.text = message
        if (ttsReady) femaleTts.speak(message)
    }

    override fun onDestroy() {
        femaleTts.shutdown()
        super.onDestroy()
    }
}

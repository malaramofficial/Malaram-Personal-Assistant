package com.malaram.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var femaleTts: HindiFemaleTts
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        femaleTts = HindiFemaleTts(this)

        findViewById<Button>(R.id.listenButton).setOnClickListener { listen() }
        findViewById<Button>(R.id.testVoiceButton).setOnClickListener {
            speak("नमस्ते माला राम जी, मैं आपकी पर्सनल असिस्टेंट हूँ। बताइए, मैं आपके लिए क्या करूँ?")
        }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

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
        speak(CommandEngine.execute(this, heard))
    }

    private fun speak(message: String) {
        status.text = message
        if (ttsReady) {
            femaleTts.speak(message)
        }
    }

    override fun onDestroy() {
        femaleTts.shutdown()
        super.onDestroy()
    }
}

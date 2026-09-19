package com.malaram.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AssistantSpeaker(context: Context) {
    private val ready = AtomicBoolean(false)
    private val tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts.setLanguage(Locale("hi", "IN"))
                ready.set(
                    result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                )
                tts.setSpeechRate(0.95f)
            }
        }
    }

    fun speak(text: String) {
        if (ready.get()) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "malaram")
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}

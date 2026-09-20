package com.malaram.assistant

import android.service.voice.VoiceInteractionService

class MalaramVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // Diagnostic safety: do not start the microphone wake-word service
        // automatically from VoiceInteractionService. During startup testing
        // this could hide the real crash by immediately restarting it.
    }

    override fun onShutdown() {
        super.onShutdown()
    }
}

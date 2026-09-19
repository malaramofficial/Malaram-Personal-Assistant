package com.malaram.assistant

import android.content.Intent
import android.service.voice.VoiceInteractionService

class MalaramVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        try {
            startService(Intent(this, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_START
            })
        } catch (_: Exception) { }
    }
    override fun onShutdown() {
        super.onShutdown()
    }
}
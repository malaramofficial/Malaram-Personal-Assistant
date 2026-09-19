package com.malaram.assistant

import android.content.Intent
import android.os.Build
import android.service.voice.VoiceInteractionService

class MalaramVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        try {
            val intent = Intent(this, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {
            // Android may reject background microphone-service startup depending
            // on assistant/default-role and OEM policy. WakeWordService itself
            // remains independently startable from the app.
        }
    }

    override fun onShutdown() {
        try {
            stopService(Intent(this, WakeWordService::class.java))
        } catch (_: Exception) {}
        super.onShutdown()
    }
}

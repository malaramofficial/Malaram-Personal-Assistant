package com.malaram.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri

object CommandEngine {
    fun execute(context: Context, raw: String): String {
        val text = raw.lowercase()
        return when {
            "youtube" in text || "यूट्यूब" in text -> launch(context, "https://www.youtube.com", "YouTube खोल रहा हूँ।")
            "whatsapp" in text || "व्हाट्सऐप" in text || "व्हाट्सएप" in text -> launch(context, "https://wa.me/", "WhatsApp खोल रहा हूँ।")
            "chrome" in text || "ब्राउज़र" in text || "ब्राउजर" in text -> launch(context, "https://www.google.com", "ब्राउज़र खोल रहा हूँ।")
            "google" in text || "गूगल" in text || "सर्च" in text -> launch(context, "https://www.google.com", "गूगल खोल रहा हूँ।")
            "सेटिंग" in text || "settings" in text -> { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "सेटिंग खोल रहा हूँ।" }
            else -> "मैंने सुना: $raw। अभी इस आदेश का action नहीं जुड़ा है।"
        }
    }
    private fun launch(context: Context, url: String, message: String): String {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return message
    }
}

package com.malaram.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object CommandEngine {

    fun execute(context: Context, raw: String): String {
        val text = raw.trim().lowercase()

        return when {
            isSensitive(text) ->
                "यह संवेदनशील आदेश है। सुरक्षा के लिए इसकी पुष्टि पहले जरूरी होगी।"

            text.contains("वापस") || text == "back" ->
                if (AssistantAccessibilityService.goBack()) "वापस जा रहा हूँ।"
                else "Accessibility Service चालू नहीं है।"

            text.contains("होम") || text == "home" ->
                if (AssistantAccessibilityService.goHome()) "होम स्क्रीन खोल रहा हूँ।"
                else "Accessibility Service चालू नहीं है।"

            text.contains("हाल के ऐप") || text.contains("recent") ->
                if (AssistantAccessibilityService.openRecents()) "हाल के ऐप खोल रहा हूँ।"
                else "Accessibility Service चालू नहीं है।"

            text.contains("ऊपर स्क्रोल") || text.contains("ऊपर स्क्रॉल") ->
                if (AssistantAccessibilityService.instance?.swipeUp() == true) "ऊपर स्क्रोल कर रहा हूँ।"
                else "Accessibility Service चालू नहीं है।"

            text.contains("नीचे स्क्रोल") || text.contains("नीचे स्क्रॉल") ->
                if (AssistantAccessibilityService.instance?.swipeDown() == true) "नीचे स्क्रोल कर रहा हूँ।"
                else "Accessibility Service चालू नहीं है।"

            text.startsWith("क्लिक ") || text.startsWith("click ") -> {
                val target = raw.substringAfter(" ").trim()
                if (target.isBlank()) "किस चीज़ पर क्लिक करना है?"
                else if (AssistantAccessibilityService.clickText(target)) "क्लिक कर दिया।"
                else "स्क्रीन पर $target नहीं मिला।"
            }

            text.contains("youtube") || text.contains("यूट्यूब") ->
                openAppOrUrl(context, "com.google.android.youtube", "https://www.youtube.com", "YouTube खोल रहा हूँ।")

            text.contains("whatsapp") || text.contains("व्हाट्सऐप") || text.contains("व्हाट्सएप") ->
                openAppOrUrl(context, "com.whatsapp", "https://wa.me/", "WhatsApp खोल रहा हूँ।")

            text.contains("chrome") || text.contains("ब्राउज़र") || text.contains("ब्राउजर") ->
                openAppOrUrl(context, "com.android.chrome", "https://www.google.com", "Chrome खोल रहा हूँ।")

            text.contains("सेटिंग") || text.contains("settings") ->
                openSettings(context)

            text.contains("गूगल") || text.contains("google") || text.startsWith("सर्च") ->
                searchGoogle(context, raw)

            else ->
                "मैंने सुना: $raw। अभी इस आदेश का action नहीं जुड़ा है।"
        }
    }

    private fun isSensitive(text: String): Boolean {
        val sensitiveWords = listOf(
            "पैसे भेज", "पेमेंट", "भुगतान", "upi", "ओटीपी", "otp",
            "पासवर्ड", "खाता हटाओ", "अकाउंट हटाओ", "डिलीट सब",
            "खरीद", "ऑर्डर करो"
        )
        return sensitiveWords.any { text.contains(it) }
    }

    private fun openAppOrUrl(
        context: Context,
        packageName: String,
        fallbackUrl: String,
        message: String
    ): String {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        } else {
            launchUrl(context, fallbackUrl)
        }
        return message
    }

    private fun openSettings(context: Context): String {
        context.startActivity(
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return "सेटिंग खोल रहा हूँ।"
    }

    private fun searchGoogle(context: Context, raw: String): String {
        val query = raw
            .replace("गूगल", "", ignoreCase = true)
            .replace("google", "", ignoreCase = true)
            .replace("सर्च", "", ignoreCase = true)
            .trim()

        if (query.isEmpty()) {
            launchUrl(context, "https://www.google.com")
        } else {
            launchUrl(
                context,
                "https://www.google.com/search?q=" + Uri.encode(query)
            )
        }
        return if (query.isEmpty()) "गूगल खोल रहा हूँ।" else "$query खोज रहा हूँ।"
    }

    private fun launchUrl(context: Context, url: String) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

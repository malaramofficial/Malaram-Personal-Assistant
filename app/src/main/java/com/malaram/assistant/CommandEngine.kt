package com.malaram.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object CommandEngine {
    data class Result(val text: String, val needsAi: Boolean = false)

    fun executeResult(context: Context, raw: String): Result {
        val text = raw.trim().lowercase()
        val pending = ConfirmationManager.getPending(context)
        if (pending != null) {
            if (ConfirmationManager.isConfirm(text)) {
                ConfirmationManager.clear(context)
                return Result("पुष्टि मिल गई। सुरक्षा के कारण संवेदनशील काम अभी स्वतः execute नहीं किया गया।")
            }
            if (ConfirmationManager.isCancel(text)) {
                ConfirmationManager.clear(context)
                return Result("ठीक है, मैंने वह काम रद्द कर दिया।")
            }
            return Result("एक संवेदनशील काम लंबित है। “हाँ” कहकर पुष्टि करें या “रद्द” कहें।")
        }
        if (isSensitive(text)) {
            ConfirmationManager.setPending(context, raw)
            return Result("यह संवेदनशील आदेश है। “हाँ” कहकर पुष्टि करें; “रद्द” कहें तो नहीं होगा।")
        }

        val plan = AgentPlanner.plan(raw)
        if (plan.isNotEmpty()) return Result(executePlan(plan))

        return when {
            text.contains("youtube") || text.contains("यूट्यूब") ->
                Result(openAppOrUrl(context, "com.google.android.youtube", "https://www.youtube.com", "YouTube खोल रहा हूँ।"))
            text.contains("whatsapp") || text.contains("व्हाट्सऐप") || text.contains("व्हाट्सएप") ->
                Result(openAppOrUrl(context, "com.whatsapp", "https://wa.me/", "WhatsApp खोल रहा हूँ।"))
            text.contains("chrome") || text.contains("ब्राउज़र") || text.contains("ब्राउजर") ->
                Result(openAppOrUrl(context, "com.android.chrome", "https://www.google.com", "Chrome खोल रहा हूँ।"))
            text.contains("सेटिंग") || text.contains("settings") ->
                Result(openSettings(context))
            text.contains("रिप्लाई क्या आया") || text.contains("रिप्लाई बताओ") ||
                text.contains("जवाब क्या आया") || text.contains("मैसेज क्या आया") ->
                Result(NotificationReplyStore.describeLatest())
            text.startsWith("जवाब लिखो ") || text.startsWith("reply लिखो ") ->
                typeReply(raw)
            text.contains("गूगल") || text.contains("google") || text.startsWith("सर्च") ->
                Result(searchGoogle(context, raw))
            text == "रुको" || text == "रुक जाओ" || text == "stop" || text == "cancel" ->
                Result("ठीक है, रुक गया।")
            else -> Result("मैंने सुना: $raw।", needsAi = true)
        }
    }

    fun execute(context: Context, raw: String): String = executeResult(context, raw).text

    private fun executePlan(plan: List<ActionPlan>): String =
        plan.joinToString(" ") { step ->
            when (step.action) {
                "back" -> if (AssistantAccessibilityService.goBack()) "वापस जा रहा हूँ।" else "Accessibility Service चालू नहीं है।"
                "home" -> if (AssistantAccessibilityService.goHome()) "होम स्क्रीन खोल रहा हूँ।" else "Accessibility Service चालू नहीं है।"
                "recents" -> if (AssistantAccessibilityService.openRecents()) "हाल के ऐप खोल रहा हूँ।" else "Accessibility Service चालू नहीं है।"
                "swipe_up" -> if (AssistantAccessibilityService.instance?.swipeUp() == true) "ऊपर स्क्रोल कर रहा हूँ।" else "Accessibility Service चालू नहीं है।"
                "swipe_down" -> if (AssistantAccessibilityService.instance?.swipeDown() == true) "नीचे स्क्रोल कर रहा हूँ।" else "Accessibility Service चालू नहीं है।"
                "click" -> if (AssistantAccessibilityService.clickText(step.argument)) "क्लिक कर दिया।" else "“${step.argument}” नहीं मिला।"
                "long_click" -> if (AssistantAccessibilityService.longClickText(step.argument)) "लंबा क्लिक कर दिया।" else "“${step.argument}” नहीं मिला।"
                "type" -> if (AssistantAccessibilityService.setFocusedText(step.argument)) "टेक्स्ट लिख दिया है।" else "लिखने वाला बॉक्स नहीं मिला।"
                "enter" -> if (AssistantAccessibilityService.pressEnter()) "एंटर/भेजने का प्रयास किया।" else "एंटर बटन नहीं मिला।"
                "read_screen" -> AssistantAccessibilityService.readScreen().ifBlank { "स्क्रीन का टेक्स्ट नहीं पढ़ पाया।" }
                else -> "यह action अभी उपलब्ध नहीं है।"
            }
        }

    private fun typeReply(raw: String): Result {
        val lower = raw.lowercase()
        val prefix = if (lower.startsWith("जवाब लिखो ")) "जवाब लिखो " else "reply लिखो "
        val draft = raw.substring(prefix.length).trim()
        if (draft.isBlank()) return Result("क्या जवाब लिखना है?")
        return Result(if (AssistantAccessibilityService.setFocusedText(draft))
            "जवाब लिख दिया है। भेजने से पहले आपकी पुष्टि जरूरी है।"
        else "चैट का लिखने वाला बॉक्स नहीं मिला।")
    }

    private fun isSensitive(text: String) =
        listOf("पैसे भेज", "पेमेंट", "भुगतान", "upi", "ओटीपी", "otp", "पासवर्ड",
            "खाता हटाओ", "अकाउंट हटाओ", "डिलीट सब", "खरीद", "ऑर्डर करो", "भेज दो")
            .any { text.contains(it) }

    private fun openAppOrUrl(context: Context, packageName: String, fallbackUrl: String, message: String): String {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else launchUrl(context, fallbackUrl)
        return message
    }

    private fun openSettings(context: Context): String {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "सेटिंग खोल रहा हूँ।"
    }

    private fun searchGoogle(context: Context, raw: String): String {
        val query = raw.replace("गूगल", "", true).replace("google", "", true).replace("सर्च", "", true).trim()
        if (query.isEmpty()) launchUrl(context, "https://www.google.com")
        else launchUrl(context, "https://www.google.com/search?q=" + Uri.encode(query))
        return if (query.isEmpty()) "गूगल खोल रहा हूँ।" else "$query खोज रहा हूँ।"
    }

    private fun launchUrl(context: Context, url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
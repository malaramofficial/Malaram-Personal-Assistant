package com.malaram.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object CommandEngine {
    data class Result(val text: String, val needsAi: Boolean = false, val needsAgent: Boolean = false, val agentTask: String? = null)

    fun executeResult(context: Context, raw: String): Result {
        return executeResultInternal(context, raw, allowConfirmedSensitive = false)
    }

    private fun executeResultInternal(context: Context, raw: String, allowConfirmedSensitive: Boolean): Result {
        val text = raw.trim().lowercase()
        val pending = ConfirmationManager.getPending(context)
        if (pending != null && !allowConfirmedSensitive) {
            if (ConfirmationManager.isCancel(text)) {
                ConfirmationManager.clear(context)
                return Result("ठीक है, मैंने वह संवेदनशील काम रद्द कर दिया।")
            }
            if (ConfirmationManager.isConfirm(text)) {
                ConfirmationManager.clear(context)
                return Result(
                    "पुष्टि मिल गई। अब उसी संवेदनशील आदेश को execute करने की कोशिश कर रहा हूँ।",
                    needsAgent = true,
                    agentTask = pending
                )
            }
            if (ConfirmationManager.isCancel(text)) {
                ConfirmationManager.clear(context)
                return Result("ठीक है, मैंने वह काम रद्द कर दिया।")
            }
            return Result("एक संवेदनशील काम लंबित है। “हाँ” कहकर पुष्टि करें या “रद्द” कहें।")
        }
        if (isSensitive(text) && !allowConfirmedSensitive) {
            ConfirmationManager.setPending(context, raw)
            return Result("यह संवेदनशील आदेश है। “हाँ” कहकर पुष्टि करें; “रद्द” कहें तो नहीं होगा।")
        }

        val plan = AgentPlanner.plan(raw)
        if (plan.isNotEmpty()) return Result(executePlan(plan))

        if (isWhatsAppMessageIntent(text) && extractWhatsAppMessage(raw).isBlank()) {
            return Result("मोनिका को क्या संदेश भेजना है?")
        }

        // Common searches are deterministic; do them immediately without the local LLM.
        if ((text.contains("youtube") || text.contains("यूट्यूब")) && looksLikeSearch(text)) {
            return Result(searchYouTube(context, raw))
        }
        if (text.contains("गूगल") || text.contains("google") || text.startsWith("सर्च") || text.contains("खोजो")) {
            val query = extractSearchQuery(raw)
            if (query.isNotBlank()) return Result(searchGoogle(context, query))
        }

        if (looksLikeActionRequest(text)) {
            return Result("ठीक है, मैं यह काम करने की कोशिश कर रहा हूँ।", needsAgent = true, agentTask = raw)
        }

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
            text == "रुको" || text == "रुक जाओ" || text == "stop" || text == "cancel" -> {
                AiBrain.cancelAgent()
                Result("ठीक है, चल रहा AI काम रोक दिया।")
            }
            else -> Result("मैंने सुना: $raw।", needsAi = true)
        }
    }

    private fun isWhatsAppMessageIntent(text: String): Boolean {
        val hasWhatsApp = listOf("whatsapp", "व्हाट्सऐप", "व्हाट्सएप").any { text.contains(it) }
        val hasMessageVerb = listOf("मैसेज", "संदेश", "message", "msg").any { text.contains(it) }
        return hasWhatsApp && hasMessageVerb
    }

    private fun extractWhatsAppMessage(raw: String): String {
        val s = raw.trim()
        val lower = s.lowercase()
        for (marker in listOf("मैसेज", "संदेश", "message", "msg")) {
            val idx = lower.indexOf(marker.lowercase())
            if (idx >= 0) {
                val after = s.substring(idx + marker.length).trim().removePrefix(":").trim()
                val cleaned = Regex("^(करो|करना|भेजो|भेजना|लिखो|लिखना)\\s+", RegexOption.IGNORE_CASE)
                    .replace(after, "")
                    .trim()
                if (cleaned.isNotBlank()) return cleaned
            }
        }
        return ""
    }

    private fun looksLikeSearch(text: String): Boolean =
        listOf("खोज", "सर्च", "ढूंढ", "ढूँढ", "search", "वीडियो").any { text.contains(it) }

    private fun extractSearchQuery(raw: String): String {
        var q = raw
        listOf("गूगल", "google", "सर्च", "search", "खोजो", "खोज", "ढूंढो", "ढूँढो", "ढूंढ", "ढूँढ", "पर", "में")
            .forEach { q = q.replace(it, " ", ignoreCase = true) }
        return q.replace(Regex("\\s+"), " ").trim()
    }

    private fun searchYouTube(context: Context, raw: String): String {
        var query = extractSearchQuery(raw)
            .replace("youtube", "", true)
            .replace("यूट्यूब", "")
            .trim()
        if (query.isBlank()) return openAppOrUrl(context, "com.google.android.youtube", "https://www.youtube.com", "YouTube खोल रहा हूँ।")
        val launch = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
        if (launch != null) {
            launch.action = Intent.ACTION_SEARCH
            launch.putExtra("query", query)
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        } else {
            launchUrl(context, "https://www.youtube.com/results?search_query=" + Uri.encode(query))
        }
        return "YouTube पर $query खोज रहा हूँ।"
    }

    private fun isCompoundAppTask(text: String): Boolean {
        val hasApp = listOf("youtube", "यूट्यूब", "whatsapp", "व्हाट्सऐप", "व्हाट्सएप", "chrome", "ब्राउज़र", "ब्राउजर")
            .any { text.contains(it) }
        if (!hasApp) return false
        val withoutApp = text
            .replace("youtube", "").replace("यूट्यूब", "")
            .replace("whatsapp", "").replace("व्हाट्सऐप", "").replace("व्हाट्सएप", "")
            .replace("chrome", "").replace("ब्राउज़र", "").replace("ब्राउजर", "").trim()
        val launchOnly = listOf("खोलो", "खोल", "खोल दो", "open", "start", "चालू")
        return withoutApp.isNotBlank() && launchOnly.none { withoutApp == it }
    }

    fun executeAgentAction(context: Context, action: String, argument: String): String {
        return when (action.lowercase()) {
            "open_app" -> openNamedApp(context, argument)
            "click" -> if (AssistantAccessibilityService.clickText(argument)) "क्लिक किया" else "लक्ष्य नहीं मिला"
            "type" -> if (AssistantAccessibilityService.setFocusedText(argument)) "टेक्स्ट लिखा" else "टेक्स्ट बॉक्स नहीं मिला"
            "enter", "send" -> if (AssistantAccessibilityService.pressEnter()) "एंटर/भेजने का प्रयास किया" else "एंटर उपलब्ध नहीं"
            "back" -> if (AssistantAccessibilityService.goBack()) "वापस गया" else "Back उपलब्ध नहीं"
            "home" -> if (AssistantAccessibilityService.goHome()) "होम खोला" else "Home उपलब्ध नहीं"
            "recents" -> if (AssistantAccessibilityService.openRecents()) "हाल के ऐप खोले" else "Recents उपलब्ध नहीं"
            "notifications" -> if (AssistantAccessibilityService.openNotifications()) "नोटिफिकेशन खोले" else "नोटिफिकेशन उपलब्ध नहीं"
            "quick_settings" -> if (AssistantAccessibilityService.openQuickSettings()) "Quick Settings खोली" else "Quick Settings उपलब्ध नहीं"
            "swipe_up", "scroll_up" -> if (AssistantAccessibilityService.instance?.swipeUp() == true) "ऊपर स्क्रोल किया" else "स्क्रोल नहीं हुआ"
            "swipe_down", "scroll_down" -> if (AssistantAccessibilityService.instance?.swipeDown() == true) "नीचे स्क्रोल किया" else "स्क्रोल नहीं हुआ"
            "long_click" -> if (AssistantAccessibilityService.longClickText(argument)) "लंबा क्लिक किया" else "लक्ष्य नहीं मिला"
            "click_desc", "click_text" -> if (AssistantAccessibilityService.clickText(argument)) "क्लिक किया" else "लक्ष्य नहीं मिला"
            "click_id" -> if (AssistantAccessibilityService.clickViewId(argument)) "क्लिक किया" else "View ID नहीं मिला"
            "clear" -> if (AssistantAccessibilityService.setFocusedText("")) "टेक्स्ट साफ किया" else "टेक्स्ट बॉक्स नहीं मिला"
            "open_url", "url", "browser" -> if (argument.isBlank()) "URL खाली है" else try { launchUrl(context, argument); "URL खोला" } catch (_: Exception) { "URL नहीं खुला" }
            "dial" -> try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(argument))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "डायलर खोला" } catch (_: Exception) { "डायलर नहीं खुला" }
            "share" -> try {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, argument)
                }, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Share खोला"
            } catch (_: Exception) { "Share उपलब्ध नहीं है" }
            "settings", "open_settings" -> openSettingsAction(context, argument)
            "read_screen" -> AssistantAccessibilityService.readScreen().ifBlank { "स्क्रीन का टेक्स्ट नहीं पढ़ पाया" }
            "screenshot" -> if (AssistantAccessibilityService.screenshotHash().isNotBlank()) "स्क्रीन की स्थिति पढ़ी" else "स्क्रीन उपलब्ध नहीं"
            "tap" -> {
                val p = argument.split(",").mapNotNull { it.trim().toFloatOrNull() }
                if (p.size == 2 && AssistantAccessibilityService.tap(p[0], p[1])) "स्क्रीन पर टैप किया" else "टैप के निर्देश गलत हैं"
            }
            "long_tap" -> {
                val p = argument.split(",").mapNotNull { it.trim().toFloatOrNull() }
                if (p.size == 2 && AssistantAccessibilityService.longTap(p[0], p[1])) "स्क्रीन पर लंबा टैप किया" else "लंबा टैप नहीं हुआ"
            }
            "wait" -> { Thread.sleep(argument.toLongOrNull()?.coerceIn(100L, 5000L) ?: 500L); "थोड़ा इंतजार किया" }
            else -> "अज्ञात action"
        }
    }

    private fun openNamedApp(context: Context, name: String): String {
        val n = name.trim().lowercase()
        val knownPackage = when {
            n.contains("youtube") || n.contains("यूट्यूब") -> "com.google.android.youtube"
            n.contains("whatsapp") || n.contains("व्हाट्सऐप") || n.contains("व्हाट्सएप") -> "com.whatsapp"
            n.contains("chrome") || n.contains("ब्राउज़र") || n.contains("ब्राउजर") -> "com.android.chrome"
            else -> null
        }
        val packageName = knownPackage ?: context.packageManager.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            .firstOrNull { app ->
                val label = context.packageManager.getApplicationLabel(app).toString().lowercase()
                label == n || label.contains(n) || n.contains(label)
            }?.packageName
            ?: return "ऐप नहीं मिला"

        // Android 13+ can block package visibility from getLaunchIntentForPackage().
        // getLaunchIntentSenderForPackage() is the visibility-safe API on API 33+.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return try {
                val sender = context.packageManager.getLaunchIntentSenderForPackage(packageName)
                sender.sendIntent(context, 0, null, null, null)
                "ऐप खोला"
            } catch (_: Exception) {
                "ऐप फोन में launch नहीं हो सका"
            }
        }

        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return "ऐप फोन में launch नहीं हो सका"
        return try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "ऐप खोला"
        } catch (_: Exception) {
            "ऐप फोन में launch नहीं हो सका"
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

    private fun looksLikeActionRequest(text: String): Boolean {
        val verbs = listOf(
            "खोल", "खोलो", "खोलना", "चलाओ", "चालू", "बंद", "बन्द", "करो", "करना",
            "भेज", "लिख", "लिखो", "डाल", "हटा", "डिलीट", "मिटा", "डाउनलोड", "अपलोड",
            "इंस्टॉल", "अनइंस्टॉल", "कॉल", "फोन", "डायल", "सर्च", "खोज", "ढूंढ", "ढूँढ",
            "स्क्रोल", "स्क्रॉल", "क्लिक", "टैप", "दबा", "सेलेक्ट", "चुन", "शेयर",
            "फोटो", "वीडियो", "प्ले", "रोक", "पॉज", "पॉज़", "कैमरा", "स्क्रीनशॉट",
            "सेट", "बदल", "बनाओ", "बनाना", "सेव", "सहेज", "कॉपी", "पेस्ट", "पढ़",
            "read", "open", "start", "stop", "send", "type", "write", "delete", "remove",
            "download", "upload", "install", "uninstall", "call", "dial", "search", "scroll",
            "click", "tap", "select", "share", "play", "pause", "screenshot", "set", "change",
            "save", "copy", "paste", "enable", "disable", "turn on", "turn off"
        )
        return verbs.any { text.contains(it) }
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

    private fun searchGoogle(context: Context, query: String): String {
        if (query.isEmpty()) launchUrl(context, "https://www.google.com")
        else launchUrl(context, "https://www.google.com/search?q=" + Uri.encode(query))
        return if (query.isEmpty()) "गूगल खोल रहा हूँ।" else "$query खोज रहा हूँ।"
    }

    private fun launchUrl(context: Context, url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
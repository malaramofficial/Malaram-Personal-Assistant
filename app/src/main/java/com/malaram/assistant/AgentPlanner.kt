package com.malaram.assistant

data class ActionPlan(val action: String, val argument: String = "")

object AgentPlanner {
    fun plan(raw: String): List<ActionPlan> {
        val original = raw.trim()
        if (original.isBlank()) return emptyList()
        val t = original.lowercase()

        val hasCompoundSeparator = Regex("\\s+(?:और फिर|फिर|और)\\s+", RegexOption.IGNORE_CASE).containsMatchIn(original)
        if (!hasCompoundSeparator) {
            val direct = when {
                t.contains("वापस") || t == "back" -> ActionPlan("back")
                t.contains("होम") || t == "home" -> ActionPlan("home")
                t.contains("हाल के ऐप") || t.contains("recent") -> ActionPlan("recents")
                (t.contains("ऊपर") || t.contains("up")) && (t.contains("स्क्रोल") || t.contains("स्क्रॉल") || t.contains("scroll")) -> ActionPlan("swipe_up")
                (t.contains("नीचे") || t.contains("down")) && (t.contains("स्क्रोल") || t.contains("स्क्रॉल") || t.contains("scroll")) -> ActionPlan("swipe_down")
                t.contains("स्क्रीन पढ़") || t.contains("स्क्रीन पर क्या है") || t.contains("read screen") -> ActionPlan("read_screen")
                t.startsWith("क्लिक ") || t.startsWith("click ") -> ActionPlan("click", original.substringAfter(" ").trim())
                t.startsWith("लंबा क्लिक ") || t.startsWith("long click ") -> ActionPlan("long_click", original.substringAfter(" ").trim())
                t.startsWith("लिखो ") || t.startsWith("type ") -> ActionPlan("type", original.substringAfter(" ").trim())
                t == "भेजो" || t == "send" || t.contains("एंटर") || t == "enter" -> ActionPlan("enter")
                else -> null
            }
            if (direct != null) return listOf(direct)
        }

        val parts = original.split(Regex("\\s+(?:और फिर|फिर|और)\\s+"), limit = 8)
            .map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size > 1) {
            val result = mutableListOf<ActionPlan>()
            for (part in parts) {
                val p = plan(part)
                if (p.isEmpty()) return emptyList()
                result += p
            }
            return result
        }
        return emptyList()
    }
}

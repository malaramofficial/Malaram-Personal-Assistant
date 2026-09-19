package com.malaram.assistant

data class ActionPlan(val action: String, val argument: String = "")

object AgentPlanner {
    fun plan(raw: String): List<ActionPlan> {
        val t = raw.trim().lowercase()
        return when {
            t.contains("वापस") || t == "back" -> listOf(ActionPlan("back"))
            t.contains("होम") || t == "home" -> listOf(ActionPlan("home"))
            t.contains("हाल के ऐप") || t.contains("recent") -> listOf(ActionPlan("recents"))
            t.contains("ऊपर") && (t.contains("स्क्रोल") || t.contains("स्क्रॉल")) -> listOf(ActionPlan("swipe_up"))
            t.contains("नीचे") && (t.contains("स्क्रोल") || t.contains("स्क्रॉल")) -> listOf(ActionPlan("swipe_down"))
            t.startsWith("क्लिक ") || t.startsWith("click ") -> listOf(ActionPlan("click", raw.substringAfter(" ").trim()))
            t.startsWith("लंबा क्लिक ") -> listOf(ActionPlan("long_click", raw.substringAfter(" ").trim()))
            t.startsWith("लिखो ") -> listOf(ActionPlan("type", raw.substringAfter(" ").trim()))
            t == "भेजो" || t == "send" || t.contains("एंटर") -> listOf(ActionPlan("enter"))
            t.contains("स्क्रीन पढ़") || t.contains("स्क्रीन पर क्या है") -> listOf(ActionPlan("read_screen"))
            else -> emptyList()
        }
    }
}
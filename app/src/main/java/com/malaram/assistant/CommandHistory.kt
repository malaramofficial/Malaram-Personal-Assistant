package com.malaram.assistant

import android.content.Context

class CommandHistory(context: Context) {
    private val prefs = context.getSharedPreferences("command_history", Context.MODE_PRIVATE)

    fun add(command: String, response: String) {
        val old = prefs.getString("items", "") ?: ""
        val line = command.replace("|", " ") + "|" + response.replace("|", " ")
        val updated = (old.lines().filter { it.isNotBlank() } + line).takeLast(50)
        prefs.edit().putString("items", updated.joinToString("\n")).apply()
    }

    fun latest(limit: Int = 10): List<Pair<String, String>> {
        return (prefs.getString("items", "") ?: "")
            .lines()
            .filter { it.contains("|") }
            .takeLast(limit)
            .mapNotNull {
                val p = it.indexOf("|")
                if (p <= 0) null else it.substring(0, p) to it.substring(p + 1)
            }
    }
}

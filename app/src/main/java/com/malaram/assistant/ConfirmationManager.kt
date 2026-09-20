package com.malaram.assistant

import android.content.Context

object ConfirmationManager {
    private const val PREFS = "assistant_confirmation"
    private const val KEY_PENDING = "pending"
    private const val KEY_CREATED = "created_at"
    private const val TTL_MS = 2 * 60 * 1000L

    fun setPending(context: Context, action: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PENDING, action)
            .putLong(KEY_CREATED, System.currentTimeMillis())
            .apply()

    fun getPending(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val action = prefs.getString(KEY_PENDING, null) ?: return null
        val created = prefs.getLong(KEY_CREATED, 0L)
        if (created <= 0L || System.currentTimeMillis() - created > TTL_MS) {
            clear(context)
            return null
        }
        return action
    }

    fun clear(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PENDING).remove(KEY_CREATED).apply()

    fun isConfirm(text: String): Boolean {
        val value = text.trim().lowercase()
        if (value.contains("नहीं") || value.contains("मत ")) return false
        return listOf("हाँ", "हां", "yes", "कर दो", "भेज दो", "पक्का", "confirm", "कन्फर्म")
            .any { value == it || value.startsWith("$it ") || value.endsWith(" $it") }
    }

    fun isCancel(text: String) =
        listOf("नहीं", "रद्द", "cancel", "मत करो", "छोड़ो", "stop", "बंद")
            .any { text.trim().lowercase().contains(it) }
}

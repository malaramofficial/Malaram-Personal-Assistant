package com.malaram.assistant

import android.content.Context

object ConfirmationManager {
    private const val PREFS = "assistant_confirmation"
    private const val KEY_PENDING = "pending"
    fun setPending(context: Context, action: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PENDING, action).apply()
    fun getPending(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PENDING, null)
    fun clear(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PENDING).apply()
    fun isConfirm(text: String) = listOf("हाँ","हां","yes","कर दो","भेज दो","पक्का","confirm","कन्फर्म").any { text.trim().lowercase().contains(it) }
    fun isCancel(text: String) = listOf("नहीं","रद्द","cancel","मत करो","छोड़ो","stop","बंद").any { text.trim().lowercase().contains(it) }
}
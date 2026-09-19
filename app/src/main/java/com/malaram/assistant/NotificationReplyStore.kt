package com.malaram.assistant

import android.content.Context
import java.util.concurrent.atomic.AtomicReference

data class ReplyInfo(
    val app: String,
    val sender: String,
    val text: String,
    val timestamp: Long
)

object NotificationReplyStore {
    private val latest = AtomicReference<ReplyInfo?>(null)

    fun update(info: ReplyInfo) { latest.set(info) }
    fun getLatest(): ReplyInfo? = latest.get()

    fun describeLatest(): String {
        val r = latest.get() ?: return "अभी कोई नया चैट रिप्लाई उपलब्ध नहीं है।"
        return r.sender + " ने " + r.text + " लिखा है।"
    }

    fun persist(context: Context) {
        val r = latest.get() ?: return
        context.getSharedPreferences("reply_store", Context.MODE_PRIVATE).edit()
            .putString("app", r.app)
            .putString("sender", r.sender)
            .putString("text", r.text)
            .putLong("timestamp", r.timestamp)
            .apply()
    }

    fun restore(context: Context) {
        val p = context.getSharedPreferences("reply_store", Context.MODE_PRIVATE)
        val text = p.getString("text", null) ?: return
        latest.set(
            ReplyInfo(
                app = p.getString("app", "unknown") ?: "unknown",
                sender = p.getString("sender", "unknown") ?: "unknown",
                text = text,
                timestamp = p.getLong("timestamp", 0L)
            )
        )
    }
}

package com.malaram.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class AssistantNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationReplyStore.restore(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank() || text.isBlank()) return

        val packageName = sbn.packageName.lowercase()
        val isChatApp = packageName.contains("whatsapp") ||
            packageName.contains("telegram") ||
            packageName.contains("messaging") ||
            packageName.contains("signal")
        if (!isChatApp) return

        NotificationReplyStore.update(
            ReplyInfo(sbn.packageName, title, text, System.currentTimeMillis())
        )
        NotificationReplyStore.persist(this)
    }
}

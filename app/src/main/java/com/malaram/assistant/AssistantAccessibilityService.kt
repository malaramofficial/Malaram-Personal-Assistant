package com.malaram.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AssistantAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AssistantAccessibilityService? = null
            private set

        fun goBack() = instance?.performGlobalAction(GLOBAL_ACTION_BACK) == true
        fun goHome() = instance?.performGlobalAction(GLOBAL_ACTION_HOME) == true
        fun openRecents() = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) == true

        fun findText(text: String): AccessibilityNodeInfo? {
            val root = instance?.rootInActiveWindow ?: return null
            return root.findAccessibilityNodeInfosByText(text).firstOrNull()
        }

        fun clickText(text: String): Boolean {
            val node = findText(text) ?: return false
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        fun setText(text: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(text)
            return nodes.firstOrNull()?.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT
            ) == true
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun swipeUp(): Boolean = swipe(540f, 1500f, 540f, 500f)
    fun swipeDown(): Boolean = swipe(540f, 500f, 540f, 1500f)

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 450))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}

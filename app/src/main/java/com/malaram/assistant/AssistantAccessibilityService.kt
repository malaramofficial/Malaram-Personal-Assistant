package com.malaram.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class AssistantAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AssistantAccessibilityService? = null
            private set

        fun goBack(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_BACK) == true
        fun goHome(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_HOME) == true
        fun openRecents(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) == true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Screen observation is intentionally kept lightweight for now.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun swipeUp(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false

        val path = Path().apply {
            moveTo(540f, 1500f)
            lineTo(540f, 500f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 450))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    fun swipeDown(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false

        val path = Path().apply {
            moveTo(540f, 500f)
            lineTo(540f, 1500f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 450))
            .build()

        return dispatchGesture(gesture, null, null)
    }
}

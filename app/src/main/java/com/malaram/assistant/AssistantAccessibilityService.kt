package com.malaram.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
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
                ?: findByContentDescription(root, text)
        }

        fun clickText(text: String): Boolean = findText(text)?.let { clickNodeOrParent(it) } == true

        fun longClickText(text: String): Boolean {
            val node = findText(text) ?: return false
            return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) ||
                (node.parent?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true)
        }

        fun setFocusedText(text: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val field = findEditable(root) ?: return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        fun pressEnter(): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val field = findEditable(root) ?: return false
            return field.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
        }

        fun readScreen(): String {
            val root = instance?.rootInActiveWindow ?: return ""
            val out = StringBuilder()
            collectText(root, out)
            return out.toString().trim().take(6000)
        }

        private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder) {
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
                if (out.length < 6000) out.append(it).append('\n')
            }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
                if (out.length < 6000 && !out.contains(it)) out.append(it).append('\n')
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { collectText(it, out) }
        }

        private fun findByContentDescription(node: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
            val wanted = target.trim().lowercase()
            if (node.contentDescription?.toString()?.lowercase()?.contains(wanted) == true && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { found ->
                    findByContentDescription(found, target)?.let { return it }
                }
            }
            return null
        }

        private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable && node.isEnabled && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { found -> findEditable(found)?.let { return it } }
            }
            return null
        }

        private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
            if (node.isClickable && node.isEnabled) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return node.parent?.let { clickNodeOrParent(it) } ?: false
        }
    }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun swipeUp(): Boolean = swipe(0.5f, 0.85f, 0.5f, 0.25f)
    fun swipeDown(): Boolean = swipe(0.5f, 0.25f, 0.5f, 0.85f)

    private fun swipe(x1n: Float, y1n: Float, x2n: Float, y2n: Float): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false
        val dm = resources.displayMetrics
        val path = Path().apply {
            moveTo(dm.widthPixels * x1n, dm.heightPixels * y1n)
            lineTo(dm.widthPixels * x2n, dm.heightPixels * y2n)
        }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 450)).build(),
            null, null
        )
    }

    override fun onDestroy() { instance = null; super.onDestroy() }
}
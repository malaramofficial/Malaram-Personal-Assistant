package com.malaram.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.os.Build
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
            val wanted = text.trim().lowercase()
            root.findAccessibilityNodeInfosByText(text).firstOrNull()?.let { return it }
            return findNode(root) { node ->
                node.isVisibleToUser && (
                    node.text?.toString()?.lowercase()?.contains(wanted) == true ||
                    node.contentDescription?.toString()?.lowercase()?.contains(wanted) == true
                )
            }
        }

        fun clickText(text: String): Boolean = findText(text)?.let { clickNodeOrParent(it) } == true

        fun longClickText(text: String): Boolean {
            val node = findText(text) ?: return false
            return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) ||
                (node.parent?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true)
        }

        fun setFocusedText(text: String, target: String? = null): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val field = if (!target.isNullOrBlank()) findEditableNear(root, target) else findFocusedEditable(root)
            val chosen = field ?: findEditable(root) ?: return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            chosen.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            return chosen.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        fun pressEnter(): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val field = findFocusedEditable(root) ?: findEditable(root) ?: return false
            if (Build.VERSION.SDK_INT >= 30 &&
                field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            ) return true
            return clickText("भेजें") || clickText("भेजो") || clickText("Send") || clickText("Enter") || clickText("Submit") || clickText("ओके") || clickText("OK")
        }

        fun screenshotHash(): String {
            val service = instance ?: return ""
            if (Build.VERSION.SDK_INT < 30) return ""\n            val latch = CountDownLatch(1)\n            var hash = ""\n            service.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {\n                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {\n                    try {\n                        val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)\n                        if (bitmap != null) {\n                            var h = 17L\n                            val stepX = maxOf(1, bitmap.width / 32)\n                            val stepY = maxOf(1, bitmap.height / 32)\n                            var y = 0\n                            while (y < bitmap.height) {\n                                var x = 0\n                                while (x < bitmap.width) {\n                                    h = h * 31 + bitmap.getPixel(x, y)\n                                    x += stepX\n                                }\n                                y += stepY\n                            }\n                            hash = h.toString(16)\n                            bitmap.recycle()\n                        }\n                    } catch (_: Exception) { }\n                    try { result.hardwareBuffer.close() } catch (_: Exception) { }\n                    latch.countDown()\n                }\n                override fun onFailure(errorCode: Int) { latch.countDown() }\n            })\n            latch.await(1500, TimeUnit.MILLISECONDS)\n            return hash\n        }\n\n        fun readScreen(): String {
            val root = instance?.rootInActiveWindow ?: return ""
            val out = LinkedHashSet<String>()
            collectText(root, out)
            return out.joinToString("\n").take(6000)
        }

        private fun collectText(node: AccessibilityNodeInfo, out: MutableSet<String>) {
            if (out.size >= 500) return
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
            for (i in 0 until node.childCount) node.getChild(i)?.let { collectText(it, out) }
        }

        private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isFocused && node.isEditable && node.isEnabled && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { findFocusedEditable(it)?.let { found -> return found } }
            return null
        }

        private fun findEditableNear(node: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
            val wanted = target.lowercase()
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            collectEditables(node, candidates)
            return candidates.minByOrNull { distanceToText(it, wanted) }
        }

        private fun distanceToText(field: AccessibilityNodeInfo, wanted: String): Int {
            val hint = (field.hintText?.toString() ?: "") .lowercase()
            val desc = (field.contentDescription?.toString() ?: "").lowercase()
            val label = "$hint $desc"
            return if (label.contains(wanted)) 0 else 1
        }

        private fun collectEditables(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
            if (node.isEditable && node.isEnabled && node.isVisibleToUser) out.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { collectEditables(it, out) }
        }

        private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable && node.isEnabled && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { findEditable(it)?.let { found -> return found } }
            return null
        }

        private fun findNode(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { findNode(it, predicate)?.let { found -> return found } }
            return null
        }

        private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
            if (node.isClickable && node.isEnabled) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return node.parent?.let(::clickNodeOrParent) ?: false
        }
    }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun swipeUp(): Boolean = swipe(0.5f, 0.85f, 0.5f, 0.25f)
    fun swipeDown(): Boolean = swipe(0.5f, 0.25f, 0.5f, 0.85f)

    private fun swipe(x1n: Float, y1n: Float, x2n: Float, y2n: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
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

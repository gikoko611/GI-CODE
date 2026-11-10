package com.gikoko.gicode

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Explicitly enabled Android accessibility bridge.
 * This first version exposes no remote command channel and performs no background actions.
 */
class GICodeAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // UI inspection and automation are invoked only by a future foreground user action.
    }

    override fun onInterrupt() = Unit

    fun visibleTextSnapshot(): List<String> {
        val output = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || output.size >= 250) return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(output::add)
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(rootInActiveWindow)
        return output
    }

    fun tapAt(x: Float, y: Float, done: (() -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { done?.invoke() }
            }, null)
    }
}

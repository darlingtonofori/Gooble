package com.example.gooble

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GoobleAccessibilityService : AccessibilityService() {

    companion object {
        var instance: GoobleAccessibilityService? = null

        fun tap(x: Float, y: Float) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0, 100)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                instance?.dispatchGesture(gesture, null, null)
            }
        }

        fun findAndClick(text: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            return searchAndClick(root, text)
        }

        private fun searchAndClick(node: AccessibilityNodeInfo, text: String): Boolean {
            val nodeText = node.text?.toString()?.lowercase() ?: ""
            val nodeDesc = node.contentDescription?.toString()?.lowercase() ?: ""
            if ((nodeText.contains(text.lowercase()) || nodeDesc.contains(text.lowercase())) && node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (searchAndClick(child, text)) return true
            }
            return false
        }

        fun getScreenText(): String {
            val root = instance?.rootInActiveWindow ?: return ""
            val sb = StringBuilder()
            extractText(root, sb)
            return sb.toString()
        }

        private fun extractText(node: AccessibilityNodeInfo, sb: StringBuilder) {
            node.text?.let { sb.append(it).append(" ") }
            node.contentDescription?.let { sb.append(it).append(" ") }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                extractText(child, sb)
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}

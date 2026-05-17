package com.example.gooble

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GoobleAccessibilityService : AccessibilityService() {

    companion object {
        var instance: GoobleAccessibilityService? = null

        fun findNodePosition(text: String): FloatArray? {
            val root = instance?.rootInActiveWindow ?: return null
            return searchNodePosition(root, text)
        }

        private fun searchNodePosition(node: AccessibilityNodeInfo, text: String): FloatArray? {
            val t = node.text?.toString()?.lowercase() ?: ""
            val d = node.contentDescription?.toString()?.lowercase() ?: ""
            if (t.contains(text.lowercase()) || d.contains(text.lowercase())) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0)
                    return floatArrayOf(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = searchNodePosition(child, text)
                if (result != null) return result
            }
            return null
        }

        fun findAndClick(text: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            return searchAndClick(root, text)
        }

        private fun searchAndClick(node: AccessibilityNodeInfo, text: String): Boolean {
            val t = node.text?.toString()?.lowercase() ?: ""
            val d = node.contentDescription?.toString()?.lowercase() ?: ""
            if ((t.contains(text.lowercase()) || d.contains(text.lowercase())) && node.isClickable) {
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
            return sb.toString().take(800)
        }

        private fun extractText(node: AccessibilityNodeInfo, sb: StringBuilder) {
            node.text?.let { sb.append(it).append(" ") }
            node.contentDescription?.let { sb.append(it).append(" ") }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                extractText(child, sb)
            }
        }

        fun tap(x: Float, y: Float) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0, 100)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                instance?.dispatchGesture(gesture, null, null)
            }
        }

        fun typeText(text: String) {
            val root = instance?.rootInActiveWindow ?: return
            findFocusedInput(root)?.let {
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }

        private fun findFocusedInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                val result = findFocusedInput(node.getChild(i) ?: continue)
                if (result != null) return result
            }
            return null
        }
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }
}

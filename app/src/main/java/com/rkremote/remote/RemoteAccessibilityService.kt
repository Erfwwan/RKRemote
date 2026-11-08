package com.rkremote.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Explicitly user-enabled accessibility bridge.
 * It does not observe or record accessibility events.
 */
class RemoteAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (activeService === this) activeService = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    private fun dispatchNormalizedGesture(
        startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long
    ): Boolean {
        if (listOf(startX, startY, endX, endY).any { !it.isFinite() || it !in 0f..1f }) return false
        if (durationMs !in MIN_GESTURE_MS..MAX_GESTURE_MS) return false
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - 1).coerceAtLeast(1)
        val maxY = (metrics.heightPixels - 1).coerceAtLeast(1)
        val path = Path().apply {
            moveTo(startX * maxX, startY * maxY)
            if (startX != endX || startY != endY) lineTo(endX * maxX, endY * maxY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun setFocusedText(value: String): Boolean {
        if (value.length > MAX_TEXT_LENGTH) return false
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!focused.isEditable) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    companion object {
        private const val MIN_GESTURE_MS = 40L
        private const val MAX_GESTURE_MS = 1500L
        private const val MAX_TEXT_LENGTH = 4096
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var activeService: RemoteAccessibilityService? = null

        fun dispatchGestureNormalized(
            startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long
        ): Boolean {
            if (listOf(startX, startY, endX, endY).any { !it.isFinite() || it !in 0f..1f }) return false
            if (durationMs !in MIN_GESTURE_MS..MAX_GESTURE_MS) return false
            val service = activeService ?: return false
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return service.dispatchNormalizedGesture(startX, startY, endX, endY, durationMs)
            }
            mainHandler.post {
                if (activeService === service) {
                    service.dispatchNormalizedGesture(startX, startY, endX, endY, durationMs)
                }
            }
            return true
        }

        fun setFocusedText(value: String): Boolean {
            if (value.length > MAX_TEXT_LENGTH) return false
            val service = activeService ?: return false
            if (Looper.myLooper() == Looper.getMainLooper()) return service.setFocusedText(value)
            mainHandler.post { if (activeService === service) service.setFocusedText(value) }
            return true
        }
    }
}

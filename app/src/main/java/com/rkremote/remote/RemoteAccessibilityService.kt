package com.rkremote.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Explicitly user-enabled accessibility bridge for remote taps.
 * No events are observed or recorded; only authenticated tap commands are dispatched.
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

    private fun dispatchNormalizedTap(x: Float, y: Float): Boolean {
        if (x !in 0f..1f || y !in 0f..1f) return false
        val metrics = resources.displayMetrics
        val px = (x * (metrics.widthPixels - 1).coerceAtLeast(1)).coerceIn(0f, (metrics.widthPixels - 1).coerceAtLeast(1).toFloat())
        val py = (y * (metrics.heightPixels - 1).coerceAtLeast(1)).coerceIn(0f, (metrics.heightPixels - 1).coerceAtLeast(1).toFloat())
        val path = Path().apply { moveTo(px, py) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val TAP_DURATION_MS = 80L
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var activeService: RemoteAccessibilityService? = null

        fun dispatchTapNormalized(x: Float, y: Float): Boolean {
            if (x !in 0f..1f || y !in 0f..1f) return false
            val service = activeService ?: return false
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return service.dispatchNormalizedTap(x, y)
            }
            mainHandler.post { if (activeService === service) service.dispatchNormalizedTap(x, y) }
            return true
        }
    }
}

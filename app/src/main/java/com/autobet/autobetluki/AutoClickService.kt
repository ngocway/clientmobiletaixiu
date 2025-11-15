package com.autobet.autobetluki

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AutoClickService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoClickService"
        private var instance: AutoClickService? = null

        fun requestClick(x: Int, y: Int) {
            val service = instance
            if (service != null) {
                Log.i(TAG, "Requesting click at ($x, $y)")
                service.performClick(x, y)
            } else {
                Log.e(TAG, "Service not running or not bound! Cannot perform click at ($x, $y)")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used
    }

    override fun onInterrupt() {
        // Not used
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility Service connected.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "Accessibility Service disconnected.")
        return super.onUnbind(intent)
    }

    private fun performClick(x: Int, y: Int) {
        try {
            val path = Path()
            path.moveTo(x.toFloat(), y.toFloat())
            // Increase gesture duration from 1ms to 50ms for more reliable clicks
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()

            Log.d(TAG, "Dispatching gesture at ($x, $y)")
            val result = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.i(TAG, "Click successful at ($x, $y).")
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Click cancelled at ($x, $y).")
                }
            }, null)
            
            if (result) {
                Log.d(TAG, "Gesture dispatched successfully at ($x, $y)")
            } else {
                Log.e(TAG, "Failed to dispatch gesture at ($x, $y) - dispatchGesture returned false")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception while performing click at ($x, $y)", e)
        }
    }
}

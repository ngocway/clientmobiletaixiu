package com.autobet.autobetluki

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AutoClickService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoClickService"
        private var instance: AutoClickService? = null
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 200L
        private const val GESTURE_DURATION_MS = 150L // Increased for more reliable clicks

        fun requestClick(x: Int, y: Int, label: String = "") {
            val service = instance
            if (service != null) {
                Log.i(TAG, "Requesting click at ($x, $y)${if (label.isNotEmpty()) " - $label" else ""}")
                service.performClickWithRetry(x, y, 0, label)
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
        ClickVisualFeedback.initialize(this)
        Log.i(TAG, "Accessibility Service connected.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "Accessibility Service disconnected.")
        return super.onUnbind(intent)
    }

    private fun performClickWithRetry(x: Int, y: Int, retryCount: Int, label: String = "") {
        // Check if service is still connected
        if (instance == null) {
            Log.e(TAG, "Service instance is null, cannot perform click at ($x, $y)")
            return
        }

        try {
            val path = Path()
            path.moveTo(x.toFloat(), y.toFloat())
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, GESTURE_DURATION_MS))
                .build()

            val attemptInfo = if (retryCount > 0) " (retry $retryCount/$MAX_RETRY_ATTEMPTS)" else ""
            Log.d(TAG, "Dispatching gesture at ($x, $y)$attemptInfo")
            
            val result = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.i(TAG, "Click successful at ($x, $y)$attemptInfo")
                    // Show visual feedback with label
                    ClickVisualFeedback.showClickFeedback(x, y, label, 2000)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Click cancelled at ($x, $y)$attemptInfo")
                    
                    // Retry if we haven't exceeded max attempts
                    if (retryCount < MAX_RETRY_ATTEMPTS) {
                        Log.i(TAG, "Retrying click at ($x, $y) in ${RETRY_DELAY_MS}ms...")
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (instance != null) {
                                performClickWithRetry(x, y, retryCount + 1, label)
                            } else {
                                Log.e(TAG, "Service disconnected, cannot retry click at ($x, $y)")
                            }
                        }, RETRY_DELAY_MS)
                    } else {
                        Log.e(TAG, "Max retry attempts ($MAX_RETRY_ATTEMPTS) reached for click at ($x, $y). Giving up.")
                    }
                }
            }, null)
            
            if (result) {
                Log.d(TAG, "Gesture dispatched successfully at ($x, $y)$attemptInfo")
            } else {
                Log.e(TAG, "Failed to dispatch gesture at ($x, $y)$attemptInfo - dispatchGesture returned false")
                
                // Retry if dispatch failed and we haven't exceeded max attempts
                if (retryCount < MAX_RETRY_ATTEMPTS) {
                    Log.i(TAG, "Retrying click at ($x, $y) in ${RETRY_DELAY_MS}ms due to dispatch failure...")
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (instance != null) {
                            performClickWithRetry(x, y, retryCount + 1, label)
                        } else {
                            Log.e(TAG, "Service disconnected, cannot retry click at ($x, $y)")
                        }
                    }, RETRY_DELAY_MS)
                } else {
                    Log.e(TAG, "Max retry attempts ($MAX_RETRY_ATTEMPTS) reached for click at ($x, $y). Giving up.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception while performing click at ($x, $y)", e)
            
            // Retry on exception if we haven't exceeded max attempts
            if (retryCount < MAX_RETRY_ATTEMPTS) {
                Log.i(TAG, "Retrying click at ($x, $y) in ${RETRY_DELAY_MS}ms after exception...")
                Handler(Looper.getMainLooper()).postDelayed({
                    if (instance != null) {
                        performClickWithRetry(x, y, retryCount + 1, label)
                    } else {
                        Log.e(TAG, "Service disconnected, cannot retry click at ($x, $y)")
                    }
                }, RETRY_DELAY_MS)
            } else {
                Log.e(TAG, "Max retry attempts ($MAX_RETRY_ATTEMPTS) reached for click at ($x, $y). Giving up.")
            }
        }
    }
}

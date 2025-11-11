
package com.autobet.autobetluki

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.Toast

class TouchCoordinateService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()

        floatingView = View(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutParams = WindowManager.LayoutParams(
            1, // Width = 1 pixel
            1, // Height = 1 pixel
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // Key flags: Not focusable, not touchable itself, but watches for outside touches
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSPARENT
        ).apply {
            // Position the view at the top-left corner
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        // The listener will now receive ACTION_OUTSIDE events for all touches on the screen
        floatingView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                // Use rawX and rawY for absolute screen coordinates
                val x = event.rawX.toInt()
                val y = event.rawY.toInt()
                Toast.makeText(this@TouchCoordinateService, "x:$x ; y:$y", Toast.LENGTH_SHORT).show()
            }
            // Returning false is good practice but has little effect here
            false
        }

        try {
            windowManager.addView(floatingView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}

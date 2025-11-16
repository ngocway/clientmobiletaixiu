package com.autobet.autobetluki

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

object ClickVisualFeedback {
    private var windowManager: WindowManager? = null
    private val activeViews = mutableSetOf<View>()
    private val handler = Handler(Looper.getMainLooper())
    
    @SuppressLint("StaticFieldLeak")
    private var context: Context? = null
    
    fun initialize(ctx: Context) {
        context = ctx.applicationContext
        windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }
    
    fun showClickFeedback(x: Int, y: Int, label: String = "", durationMs: Long = 2000) {
        val ctx = context ?: return
        val wm = windowManager ?: return
        
        handler.post {
            val circleView = object : View(ctx) {
                private val paint = Paint().apply {
                    color = Color.parseColor("#2196F3") // Blue color
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }
                
                private val textPaint = Paint().apply {
                    color = Color.WHITE
                    textSize = 36f
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                }
                
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val centerX = width / 2f
                    val centerY = height / 2f
                    val radius = 30f // Radius of the circle
                    
                    // Draw circle
                    canvas.drawCircle(centerX, centerY, radius, paint)
                    
                    // Draw label text if provided
                    if (label.isNotEmpty()) {
                        canvas.drawText(label, centerX, centerY + radius + 50, textPaint)
                    }
                }
            }
            
            val layoutParams = WindowManager.LayoutParams(
                200, // Width
                200, // Height
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x - 100 // Center the view on click position
                this.y = y - 100
            }
            
            try {
                wm.addView(circleView, layoutParams)
                activeViews.add(circleView)
                
                // Remove view after duration
                handler.postDelayed({
                    try {
                        if (activeViews.contains(circleView)) {
                            wm.removeView(circleView)
                            activeViews.remove(circleView)
                        }
                    } catch (e: Exception) {
                        // View might already be removed
                    }
                }, durationMs)
            } catch (e: Exception) {
                android.util.Log.e("ClickVisualFeedback", "Failed to show click feedback", e)
            }
        }
    }
    
    fun clearAll() {
        val wm = windowManager ?: return
        handler.post {
            activeViews.forEach { view ->
                try {
                    wm.removeView(view)
                } catch (e: Exception) {
                    // Ignore
                }
            }
            activeViews.clear()
        }
    }
}


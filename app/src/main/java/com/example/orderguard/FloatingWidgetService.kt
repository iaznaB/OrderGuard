package com.example.orderguard

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import android.widget.ImageButton

class FloatingWidgetService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        floatingView = LayoutInflater.from(this).inflate(R.layout.widget_floating, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START

        val prefs = getSharedPreferences("OrderGuardPrefs", MODE_PRIVATE)

        params.x = prefs.getInt("WIDGET_X", 50)
        params.y = prefs.getInt("WIDGET_Y", 300)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(floatingView, params)

        val button = floatingView.findViewById<ImageButton>(R.id.floatingToggle)

        button.setOnTouchListener(object : View.OnTouchListener {

            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            private var isDragging = false
            private val CLICK_DRAG_TOLERANCE = 10

            private val LONG_PRESS_TIME = 1500L
            private val handler = android.os.Handler(android.os.Looper.getMainLooper())

            private val longPressRunnable = Runnable {
                stopSelf()
            }

            override fun onTouch(v: View?, event: MotionEvent): Boolean {

                when (event.action) {

                    MotionEvent.ACTION_DOWN -> {
                        isDragging = false
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY

                        handler.postDelayed(longPressRunnable, LONG_PRESS_TIME)

                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {

                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()

                        if (Math.abs(dx) > CLICK_DRAG_TOLERANCE || Math.abs(dy) > CLICK_DRAG_TOLERANCE) {
                            isDragging = true
                            handler.removeCallbacks(longPressRunnable)
                        }

                        params.x = initialX + dx
                        params.y = initialY + dy

                        windowManager.updateViewLayout(floatingView, params)

                        val prefs = getSharedPreferences("OrderGuardPrefs", MODE_PRIVATE)
                        prefs.edit()
                            .putInt("WIDGET_X", params.x)
                            .putInt("WIDGET_Y", params.y)
                            .apply()
                        return true
                    }

                    MotionEvent.ACTION_UP -> {

                        handler.removeCallbacks(longPressRunnable)

                        if (!isDragging) {
                            v?.performClick()
                        }

                        return true
                    }
                }

                return false
            }
        })

        button.setOnClickListener {

            val prefs = getSharedPreferences("OrderGuardPrefs", MODE_PRIVATE)

            val monitoring = prefs.getBoolean("IS_MONITORING", false)

            prefs.edit().putBoolean("IS_MONITORING", !monitoring).apply()

            updateIcon(button, !monitoring)
        }

        updateIcon(
            button,
            getSharedPreferences("OrderGuardPrefs", MODE_PRIVATE)
                .getBoolean("IS_MONITORING", false)
        )
    }

    private fun updateIcon(button: ImageButton, monitoring: Boolean) {

        if (monitoring) {
            button.setImageResource(R.drawable.floating_off)
        } else {
            button.setImageResource(R.drawable.floating_on)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        windowManager.removeView(floatingView)
    }
}
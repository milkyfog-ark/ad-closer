package jp.milkyfog.adcloser

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout

/**
 * Small floating control shown over the game: a manual "ad started" arm
 * button (for ads the resident watcher fails to detect) and an emergency
 * stop for the current session.
 */
class OverlayService : Service() {

    companion object {
        @Volatile var isRunning: Boolean = false
    }

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var armButton: Button? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6, 6, 6, 6)
            setBackgroundColor(Color.argb(140, 30, 30, 40))
        }
        armButton = Button(this).apply {
            text = "広告開始"
            setOnClickListener {
                Control.manualArmAt = SystemClock.elapsedRealtime()
                Control.emergencySuspend = false
                text = "監視中…"
                LogStore.add(this@OverlayService, "フローティングボタンで手動開始")
            }
        }
        val stopButton = Button(this).apply {
            text = "停止"
            setOnClickListener {
                Control.emergencySuspend = true
                Control.manualArmAt = 0L
                armButton?.text = "広告開始"
            }
        }
        layout.addView(armButton)
        layout.addView(stopButton)
        rootView = layout

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 8
        }
        windowManager?.addView(layout, params)
    }

    override fun onDestroy() {
        isRunning = false
        rootView?.let { windowManager?.removeView(it) }
        rootView = null
        super.onDestroy()
    }
}

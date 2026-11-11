package jp.milkyfog.adcloser

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small floating control shown over the game: a manual "ad started" arm
 * button (for ads the resident watcher fails to detect) and an emergency
 * stop for the current session.
 *
 * v0.5 (user feedback 2026-11-11): the panel can be dragged by its grip
 * bar to any position (remembered across restarts), has its own close
 * button, and disappears when the app is swiped away from Recents —
 * before, it stayed on screen forever with no way to move or dismiss it.
 */
class OverlayService : Service() {

    companion object {
        @Volatile var isRunning: Boolean = false
    }

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var armButton: Button? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Do not resurrect the panel after the process is killed.
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away from Recents should take the panel with it.
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6, 4, 6, 6)
            setBackgroundColor(Color.argb(140, 30, 30, 40))
        }

        // Header row: drag grip + close button.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val grip = TextView(this).apply {
            text = "≡ 移動"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(10, 6, 10, 6)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeButton = Button(this).apply {
            text = "✕"
            minWidth = 0
            setPadding(14, 2, 14, 2)
            setOnClickListener {
                LogStore.add(this@OverlayService, "フローティングボタンを閉じました")
                stopSelf()
            }
        }
        header.addView(grip)
        header.addView(closeButton)
        layout.addView(header)

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

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        params = lp
        wm.addView(layout, lp)

        // Initial position: saved one, else right edge / vertical centre.
        val prefs = getSharedPreferences("overlay", MODE_PRIVATE)
        layout.post {
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            if (prefs.contains("x") && prefs.contains("y")) {
                lp.x = prefs.getInt("x", 0).coerceIn(0, screenW - layout.width)
                lp.y = prefs.getInt("y", 0).coerceIn(0, screenH - layout.height)
            } else {
                lp.x = screenW - layout.width - 8
                lp.y = screenH / 2 - layout.height / 2
            }
            runCatching { wm.updateViewLayout(layout, lp) }
        }

        // Drag by the grip bar.
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        grip.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val screenW = resources.displayMetrics.widthPixels
                    val screenH = resources.displayMetrics.heightPixels
                    lp.x = (startX + (ev.rawX - downRawX).toInt())
                        .coerceIn(0, screenW - layout.width)
                    lp.y = (startY + (ev.rawY - downRawY).toInt())
                        .coerceIn(0, screenH - layout.height)
                    runCatching { wm.updateViewLayout(layout, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        rootView?.let { runCatching { windowManager?.removeView(it) } }
        rootView = null
        super.onDestroy()
    }
}

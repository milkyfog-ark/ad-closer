package jp.milkyfog.adcloser

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
    private var farmButton: Button? = null
    private var targetButton: Button? = null
    private var clickButton: Button? = null
    private var targetView: TargetView? = null
    private var targetParams: WindowManager.LayoutParams? = null
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var consumedNudgeAt = 0L
    private val labelRefresh = object : Runnable {
        override fun run() {
            farmButton?.text = farmLabel()
            syncClickerUi()
            publishPanelRect()
            if (Control.panelNudgeAt != consumedNudgeAt) {
                consumedNudgeAt = Control.panelNudgeAt
                nudgePanel()
            }
            refreshHandler.postDelayed(this, 1000)
        }
    }

    /** Keep the accessibility service aware of where the panel is, so
     *  its taps never land on the panel itself (v2.7). */
    private fun publishPanelRect() {
        val view = rootView ?: return
        val lp = params ?: return
        if (view.width > 0 && view.height > 0) {
            Control.panelRect = android.graphics.Rect(
                lp.x, lp.y, lp.x + view.width, lp.y + view.height
            )
        }
    }

    /**
     * Move the panel to a screen corner away from the blocked tap
     * point (v2.7). Prefers the top corner on the opposite side; if
     * that still covers the point (point near the top), uses the
     * bottom corner on the opposite side instead.
     */
    private fun nudgePanel() {
        val wm = windowManager ?: return
        val view = rootView ?: return
        val lp = params ?: return
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val w = view.width
        val h = view.height
        if (w == 0 || h == 0) return
        val bx = (Control.blockedTapFx * screenW).toInt()
        val by = (Control.blockedTapFy * screenH).toInt()
        val left = Control.blockedTapFx >= 0.5f
        var nx = if (left) 8 else screenW - w - 8
        var ny = 8
        if (bx in nx until nx + w && by in ny until ny + h) {
            ny = screenH - h - 8
        }
        lp.x = nx.coerceIn(0, screenW - w)
        lp.y = ny.coerceIn(0, screenH - h)
        runCatching { wm.updateViewLayout(view, lp) }
        getSharedPreferences("overlay", MODE_PRIVATE).edit()
            .putInt("x", lp.x).putInt("y", lp.y).apply()
        publishPanelRect()
        LogStore.add(this, "フローティングパネルを退避しました")
    }

    /**
     * Clamp a panel position (v2.36, user request 2026-11-14): the
     * panel may be dragged off the screen edges to get it out of
     * the game's way, but the drag grip ("≡ 移動") must never
     * disappear — at least ~48dp of it stays visible horizontally,
     * and its row (the header) always stays on screen vertically
     * (never above the top; the panel may sink until only the
     * header shows at the bottom). The automatic nudge still
     * returns the panel fully on screen, so the emergency stop
     * stays reachable after an evacuation. When the grip has not
     * been measured yet, fall back to the old full-panel clamp.
     */
    private fun clampPanel(
        x: Int, y: Int, screenW: Int, screenH: Int,
        panelW: Int, panelH: Int, gripW: Int, headerH: Int
    ): Pair<Int, Int> {
        if (gripW <= 0 || headerH <= 0) {
            return x.coerceIn(0, (screenW - panelW).coerceAtLeast(0)) to
                y.coerceIn(0, (screenH - panelH).coerceAtLeast(0))
        }
        val keep = (48 * resources.displayMetrics.density).toInt()
        val gripLeft = 6 // layout padding-left (px); the grip starts there
        val minX = keep - gripLeft - gripW
        val maxX = screenW - gripLeft - keep
        val maxY = (screenH - headerH).coerceAtLeast(0)
        return x.coerceIn(minX, maxX) to y.coerceIn(0, maxY)
    }

    private fun farmLabel(): String =
        if (Prefs.farm(this)) "周回停止" else "周回開始"

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
        farmButton = Button(this).apply {
            text = farmLabel()
            setOnClickListener {
                val next = !Prefs.farm(this@OverlayService)
                Prefs.setFarm(this@OverlayService, next)
                text = farmLabel()
                LogStore.add(
                    this@OverlayService,
                    if (next) "フローティングボタンで周回モードを開始" else "フローティングボタンで周回モードを停止"
                )
            }
        }
        targetButton = Button(this).apply {
            text = "連打の場所指定"
            setOnClickListener {
                if (targetView == null) showTarget() else hideTarget()
                syncClickerUi()
            }
        }
        clickButton = Button(this).apply {
            text = "連打開始"
            setOnClickListener {
                if (Control.clickerOn) {
                    Control.clickerOn = false
                    LogStore.add(this@OverlayService, "フローティングボタンで連打を終了")
                } else {
                    if (targetView == null) showTarget()
                    Control.clickerStartedAt = SystemClock.elapsedRealtime()
                    Control.clickerOn = true
                    // If the marker sits under this panel, the clicks
                    // would press the panel's own buttons: move the
                    // panel out of the way first (v2.7).
                    val rect = Control.panelRect
                    if (rect != null) {
                        val mx = (Prefs.clickerX(this@OverlayService) *
                            resources.displayMetrics.widthPixels).toInt()
                        val my = (Prefs.clickerY(this@OverlayService) *
                            resources.displayMetrics.heightPixels).toInt()
                        if (rect.contains(mx, my)) {
                            Control.blockedTapFx = Prefs.clickerX(this@OverlayService)
                            Control.blockedTapFy = Prefs.clickerY(this@OverlayService)
                            nudgePanel()
                        }
                    }
                    LogStore.add(this@OverlayService, "フローティングボタンで連打を開始")
                }
                syncClickerUi()
            }
        }
        val stopButton = Button(this).apply {
            text = "全停止"
            setOnClickListener {
                Control.emergencySuspend = true
                Control.manualArmAt = 0L
                Control.clickerOn = false
                Prefs.setFarm(this@OverlayService, false)
                armButton?.text = "広告開始"
                farmButton?.text = farmLabel()
                syncClickerUi()
                LogStore.add(this@OverlayService, "フローティングボタンで全停止")
            }
        }
        layout.addView(armButton)
        layout.addView(farmButton)
        layout.addView(targetButton)
        layout.addView(clickButton)
        layout.addView(stopButton)
        refreshHandler.post(labelRefresh)
        rootView = layout

        // FLAG_LAYOUT_NO_LIMITS (v2.42): without it Android itself
        // clips overlay windows to the display, no matter what x/y
        // we set — v2.36 fixed only OUR clamp math, so the panel
        // still stopped dead at the screen edge (user report
        // 2026-11-14 23:50: "移動しても画面外に行かない"). With
        // the flag, clampPanel() is the real bound: the grip can
        // never be dragged out of reach.
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
                val clamped = clampPanel(
                    prefs.getInt("x", 0), prefs.getInt("y", 0),
                    screenW, screenH,
                    layout.width, layout.height, grip.width, header.height
                )
                lp.x = clamped.first
                lp.y = clamped.second
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
                    val clamped = clampPanel(
                        startX + (ev.rawX - downRawX).toInt(),
                        startY + (ev.rawY - downRawY).toInt(),
                        screenW, screenH,
                        layout.width, layout.height, grip.width, header.height
                    )
                    lp.x = clamped.first
                    lp.y = clamped.second
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

    // ---------------- auto-clicker target marker (v2.1) ----------------

    private fun targetSizePx(): Int = (64 * resources.displayMetrics.density).toInt()

    private fun showTarget() {
        val wm = windowManager ?: return
        if (targetView != null) return
        val size = targetSizePx()
        val view = TargetView(this)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        lp.x = (Prefs.clickerX(this) * screenW).toInt() - size / 2
        lp.y = (Prefs.clickerY(this) * screenH).toInt() - size / 2
        lp.x = lp.x.coerceIn(0, screenW - size)
        lp.y = lp.y.coerceIn(0, screenH - size)
        targetView = view
        targetParams = lp
        runCatching { wm.addView(view, lp) }

        // Drag the marker to position it; the centre is the tap point.
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (startX + (ev.rawX - downRawX).toInt()).coerceIn(0, screenW - size)
                    lp.y = (startY + (ev.rawY - downRawY).toInt()).coerceIn(0, screenH - size)
                    runCatching { wm.updateViewLayout(view, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    Prefs.setClickerPos(
                        this@OverlayService,
                        (lp.x + size / 2).toFloat() / screenW,
                        (lp.y + size / 2).toFloat() / screenH
                    )
                    true
                }
                else -> false
            }
        }
        LogStore.add(this, "連打のマトを表示（ドラッグで位置指定）")
    }

    private fun hideTarget() {
        targetView?.let { runCatching { windowManager?.removeView(it) } }
        targetView = null
        targetParams = null
    }

    /**
     * Keep the panel labels and the marker's touchability in sync with
     * the clicker state. While clicking, the marker must NOT be
     * touchable — otherwise it would swallow the very taps it aims.
     */
    private fun syncClickerUi() {
        val on = Control.clickerOn
        clickButton?.text = if (on) "連打終了" else "連打開始"
        targetButton?.text = if (targetView == null) "連打の場所指定" else "マトをしまう"
        val view = targetView ?: return
        val lp = targetParams ?: return
        val wantFlags = if (on) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (lp.flags != wantFlags) {
            lp.flags = wantFlags
            view.alpha = if (on) 0.55f else 1f
            runCatching { windowManager?.updateViewLayout(view, lp) }
        }
    }

    override fun onDestroy() {
        refreshHandler.removeCallbacks(labelRefresh)
        // No panel = no visible way to stop the clicker: stop it too.
        Control.clickerOn = false
        Control.panelRect = null
        hideTarget()
        isRunning = false
        rootView?.let { runCatching { windowManager?.removeView(it) } }
        rootView = null
        super.onDestroy()
    }
}

/** Draggable target (mato) graphic for the auto-clicker: ring + dot + crosshair. */
private class TargetView(ctx: Context) : View(ctx) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 90, 40)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 90, 40)
        style = Paint.Style.FILL
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 90, 40)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - 4f
        canvas.drawCircle(cx, cy, r, halo)
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawCircle(cx, cy, r * 0.45f, ring)
        canvas.drawCircle(cx, cy, 5f, dot)
        canvas.drawLine(cx - r, cy, cx - r * 0.55f, cy, ring)
        canvas.drawLine(cx + r * 0.55f, cy, cx + r, cy, ring)
        canvas.drawLine(cx, cy - r, cx, cy - r * 0.55f, ring)
        canvas.drawLine(cx, cy + r * 0.55f, cx, cy + r, ring)
    }
}

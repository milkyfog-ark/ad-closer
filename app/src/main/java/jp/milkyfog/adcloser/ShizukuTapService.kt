package jp.milkyfog.adcloser

import android.content.Context
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.SystemServiceHelper

/**
 * Shizuku user service (v2.55). Runs with shell identity in
 * Shizuku's process space and injects MotionEvents through
 * IInputManager, so the events carry NO accessibility mark —
 * the mark games like ログウィズ use to discard our taps.
 * Falls back to the shell's own `input` command if direct
 * injection is unavailable for any reason.
 */
class ShizukuTapService : IShizukuTap.Stub {
    constructor() : super()
    constructor(context: Context) : super()

    // v2.59: what the last tap actually did — which path it
    // took and whether the system accepted it. The client
    // surfaces this in the log so a dead tap can be diagnosed
    // from the log alone.
    @Volatile
    private var diagInfo: String = "まだ送信していません"

    override fun diag(): String = diagInfo

    override fun destroy() {
        kotlin.system.exitProcess(0)
    }

    override fun tap(x: Int, y: Int, durationMs: Int) {
        tapM(0, x, y, durationMs)
    }

    override fun tapM(method: Int, x: Int, y: Int, durationMs: Int) {
        when (method) {
            3 -> evdevTap(x, y, durationMs)
            1 -> cmdTap(x, y, durationMs, chosen = true)
            2 -> if (!injectTap(x, y, durationMs, jitter = true)) {
                cmdTap(x, y, durationMs, chosen = false)
            }
            else -> if (!injectTap(x, y, durationMs, jitter = false)) {
                cmdTap(x, y, durationMs, chosen = false)
            }
        }
    }

    // v2.62: hardware-level taps — write multitouch events
    // straight to the touchscreen's evdev node, so they
    // enter the system exactly the way a real finger's do
    // (through the kernel input reader), leaving no trace
    // of software injection anywhere. This is the last
    // technical route if the game reads the hardware input
    // stream rather than the events the system delivers.
    private var evdevPath: String? = null
    private var evdevMaxX = -1
    private var evdevMaxY = -1
    private var evdevProbed = false
    private var trackingId = 1000

    @Volatile
    private var displayW = 1080

    @Volatile
    private var displayH = 2400

    override fun setDisplaySize(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            displayW = w
            displayH = h
        }
    }

    private fun probeEvdev(): Boolean {
        if (evdevProbed) return evdevPath != null
        evdevProbed = true
        return try {
            val devices = java.io.File("/proc/bus/input/devices").readText()
            var node: String? = null
            for (block in devices.split("\n\n")) {
                val nameLine = block.lineSequence()
                    .firstOrNull { it.startsWith("N: Name=") }
                    ?: continue
                if (!nameLine.contains("touch", ignoreCase = true)) continue
                val handlers = block.lineSequence()
                    .firstOrNull { it.startsWith("H: Handlers=") }
                    ?: continue
                val m = Regex("event(\\d+)").find(handlers) ?: continue
                node = "/dev/input/event" + m.groupValues[1]
                break
            }
            if (node == null) {
                diagInfo = "evdev方式：タッチパネルのデバイスが見つかりません"
                return false
            }
            evdevPath = node
            try {
                val proc = Runtime.getRuntime()
                    .exec(arrayOf("getevent", "-p", node))
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                for (line in out.lineSequence()) {
                    val mm = Regex("(0035|0036)\\s*:.*max\\s+(\\d+)")
                        .find(line) ?: continue
                    if (mm.groupValues[1] == "0035") {
                        evdevMaxX = mm.groupValues[2].toInt()
                    } else {
                        evdevMaxY = mm.groupValues[2].toInt()
                    }
                }
            } catch (t: Throwable) {
                // Ranges fall back to the display size below.
            }
            if (evdevMaxX <= 0) evdevMaxX = displayW - 1
            if (evdevMaxY <= 0) evdevMaxY = displayH - 1
            true
        } catch (t: Throwable) {
            diagInfo = "evdev方式：調査に失敗" +
                "（${t.javaClass.simpleName}: ${t.message}）"
            false
        }
    }

    private fun evdevTap(x: Int, y: Int, durationMs: Int): Boolean {
        if (!probeEvdev()) return false
        val path = evdevPath ?: return false
        val ex = (x.toLong() * evdevMaxX /
            maxOf(1, displayW - 1)).toInt()
        val ey = (y.toLong() * evdevMaxY /
            maxOf(1, displayH - 1)).toInt()
        return try {
            val fos = java.io.FileOutputStream(path, true)
            trackingId++
            val id = trackingId
            fun ev(type: Int, code: Int, value: Int): ByteArray {
                val nowMs = System.currentTimeMillis()
                val b = java.nio.ByteBuffer.allocate(24)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                b.putLong(nowMs / 1000)
                b.putLong((nowMs % 1000) * 1000)
                b.putShort(type.toShort())
                b.putShort(code.toShort())
                b.putInt(value)
                return b.array()
            }
            val down = java.io.ByteArrayOutputStream()
            down.write(ev(3, 47, 0)) // ABS_MT_SLOT 0
            down.write(ev(3, 57, id)) // ABS_MT_TRACKING_ID
            down.write(ev(3, 53, ex)) // ABS_MT_POSITION_X
            down.write(ev(3, 54, ey)) // ABS_MT_POSITION_Y
            down.write(ev(3, 58, 60)) // ABS_MT_PRESSURE
            down.write(ev(3, 48, 8)) // ABS_MT_TOUCH_MAJOR
            down.write(ev(1, 330, 1)) // BTN_TOUCH down
            down.write(ev(0, 0, 0)) // SYN_REPORT
            fos.write(down.toByteArray())
            fos.flush()
            if (durationMs > 0) SystemClock.sleep(durationMs.toLong())
            val up = java.io.ByteArrayOutputStream()
            up.write(ev(3, 57, -1)) // release the tracking id
            up.write(ev(1, 330, 0)) // BTN_TOUCH up
            up.write(ev(0, 0, 0)) // SYN_REPORT
            fos.write(up.toByteArray())
            fos.flush()
            fos.close()
            diagInfo = "evdev方式で送信" +
                "（$path、範囲 0–$evdevMaxX × 0–$evdevMaxY）"
            true
        } catch (t: Throwable) {
            diagInfo = "evdev方式：書き込み失敗" +
                "（${t.javaClass.simpleName}: ${t.message}）"
            false
        }
    }

    private fun cmdTap(x: Int, y: Int, durationMs: Int, chosen: Boolean) {
        val why = diagInfo
        try {
            if (durationMs <= 50) {
                Runtime.getRuntime().exec(
                    arrayOf("input", "tap", x.toString(), y.toString())
                ).waitFor()
            } else {
                Runtime.getRuntime().exec(
                    arrayOf(
                        "input", "swipe",
                        x.toString(), y.toString(),
                        x.toString(), y.toString(),
                        durationMs.toString()
                    )
                ).waitFor()
            }
            diagInfo = if (chosen) {
                "inputコマンド方式で送信（方式として選択）"
            } else {
                "inputコマンド方式で送信（直接注入不可: $why）"
            }
        } catch (t: Throwable) {
            diagInfo = "inputコマンド方式も失敗（直接注入: $why）"
        }
    }

    /** The real touchscreen's device id, or -1 when unknown. */
    private fun touchscreenDeviceId(): Int {
        try {
            for (id in InputDevice.getDeviceIds()) {
                val dev = InputDevice.getDevice(id) ?: continue
                if (dev.sources and InputDevice.SOURCE_TOUCHSCREEN ==
                    InputDevice.SOURCE_TOUCHSCREEN
                ) {
                    return id
                }
            }
        } catch (t: Throwable) {
            // fall through
        }
        return -1
    }

    private fun injectTap(
        x: Int, y: Int, durationMs: Int, jitter: Boolean
    ): Boolean {
        return try {
            val binder: IBinder =
                SystemServiceHelper.getSystemService("input")
                    ?: run {
                        diagInfo = "直接注入不可: inputサービスが取れません"
                        return false
                    }
            val stubClass =
                Class.forName("android.hardware.input.IInputManager\$Stub")
            val inputManager = stubClass
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder) ?: run {
                    diagInfo = "直接注入不可: IInputManagerが取れません"
                    return false
                }
            val injectMethod = inputManager.javaClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            // v2.59: build the events the way a real finger
            // looks to the system — addressed to the actual
            // touchscreen device, tool type FINGER, with
            // pressure and a contact size — instead of the
            // bare virtual-device events v2.55 sent, which
            // some game surfaces silently ignore.
            val deviceId = touchscreenDeviceId()
            val props = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            )
            fun coordsAt(cx: Int, cy: Int) = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = cx.toFloat()
                    this.y = cy.toFloat()
                    pressure = 1f
                    size = 0.1f
                    touchMajor = 24f
                    touchMinor = 24f
                    orientation = 0f
                }
            )
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, 1,
                props, coordsAt(x, y), 0, 0, 1f, 1f,
                deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            val okDown = injectMethod.invoke(inputManager, down, 0) as Boolean
            down.recycle()
            if (!okDown) {
                diagInfo = "直接注入: DOWNがシステムに拒否されました"
                return false
            }
            if (jitter && durationMs >= 40) {
                // Method 2: a real finger drifts a pixel or so
                // mid-press; emit one MOVE the same way.
                SystemClock.sleep((durationMs / 2).toLong())
                val mv = MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_MOVE, 1,
                    props, coordsAt(x + 1, y), 0, 0, 1f, 1f,
                    deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                )
                runCatching {
                    injectMethod.invoke(inputManager, mv, 0)
                }
                mv.recycle()
                SystemClock.sleep((durationMs - durationMs / 2).toLong())
            } else if (durationMs > 0) {
                SystemClock.sleep(durationMs.toLong())
            }
            val up = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP, 1,
                props, coordsAt(if (jitter) x + 1 else x, y),
                0, 0, 1f, 1f,
                deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            val okUp = injectMethod.invoke(inputManager, up, 0) as Boolean
            up.recycle()
            val devName = try {
                InputDevice.getDevice(deviceId)?.name ?: "不明"
            } catch (t: Throwable) {
                "不明"
            }
            diagInfo = "直接注入で送信OK（宛先デバイス $deviceId: $devName）"
            okUp
        } catch (t: Throwable) {
            diagInfo =
                "直接注入で例外: ${t.javaClass.simpleName}: ${t.message}"
            false
        }
    }
}

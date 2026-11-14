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
        if (injectTap(x, y, durationMs)) return
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
            diagInfo = "inputコマンド方式で送信（直接注入不可: $why）"
        } catch (t: Throwable) {
            diagInfo = "両方式とも失敗（直接注入: $why）"
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

    private fun injectTap(x: Int, y: Int, durationMs: Int): Boolean {
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
            fun coords() = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x.toFloat()
                    this.y = y.toFloat()
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
                props, coords(), 0, 0, 1f, 1f,
                deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            val okDown = injectMethod.invoke(inputManager, down, 0) as Boolean
            down.recycle()
            if (!okDown) {
                diagInfo = "直接注入: DOWNがシステムに拒否されました"
                return false
            }
            if (durationMs > 0) SystemClock.sleep(durationMs.toLong())
            val up = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP, 1,
                props, coords(), 0, 0, 1f, 1f,
                deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            val okUp = injectMethod.invoke(inputManager, up, 0) as Boolean
            up.recycle()
            diagInfo = "直接注入で送信OK（宛先デバイス $deviceId）"
            okUp
        } catch (t: Throwable) {
            diagInfo =
                "直接注入で例外: ${t.javaClass.simpleName}: ${t.message}"
            false
        }
    }
}

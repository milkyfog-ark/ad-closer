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

    override fun destroy() {
        kotlin.system.exitProcess(0)
    }

    override fun tap(x: Int, y: Int, durationMs: Int) {
        if (injectTap(x, y, durationMs)) return
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
        } catch (t: Throwable) {
            // Nothing more this process can do.
        }
    }

    private fun injectTap(x: Int, y: Int, durationMs: Int): Boolean {
        return try {
            val binder: IBinder =
                SystemServiceHelper.getSystemService("input") ?: return false
            val stubClass =
                Class.forName("android.hardware.input.IInputManager\$Stub")
            val inputManager = stubClass
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder) ?: return false
            val injectMethod = inputManager.javaClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN,
                x.toFloat(), y.toFloat(), 0
            )
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            val okDown = injectMethod.invoke(inputManager, down, 0) as Boolean
            down.recycle()
            if (!okDown) return false
            if (durationMs > 0) SystemClock.sleep(durationMs.toLong())
            val up = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                x.toFloat(), y.toFloat(), 0
            )
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            val okUp = injectMethod.invoke(inputManager, up, 0) as Boolean
            up.recycle()
            okUp
        } catch (t: Throwable) {
            false
        }
    }
}

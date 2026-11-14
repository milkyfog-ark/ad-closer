package jp.milkyfog.adcloser

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import rikka.shizuku.Shizuku

/**
 * Client side of the Shizuku tap engine (v2.55): binds the
 * ShizukuTapService user service (shell identity) and injects
 * taps through it. Used by the auto-clicker when the
 * 「Shizuku方式」 switch is on; every failure path returns
 * false so the caller can fall back to accessibility gestures.
 */
object ShizukuTap {
    private var service: IShizukuTap? = null
    private var binding = false
    private var lastBindAttemptAt = 0L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { IShizukuTap.Stub.asInterface(it) }
            binding = false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
        }
    }

    /** Shizuku is running and has granted this app permission. */
    fun ready(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun ensureBound(ctx: Context) {
        if (service != null || binding) return
        if (!ready()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBindAttemptAt < 3000) return
        lastBindAttemptAt = now
        binding = true
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(ctx.packageName, ShizukuTapService::class.java.name)
            ).daemon(false).processNameSuffix("tap").debuggable(false).version(1)
            Shizuku.bindUserService(args, connection)
        } catch (t: Throwable) {
            binding = false
        }
    }

    /**
     * Injects one tap (press durationMs) at pixel (x, y) via the
     * shell. Returns false when the Shizuku route is not usable
     * right now; the caller falls back to an accessibility
     * gesture for that beat.
     */
    fun tap(ctx: Context, x: Int, y: Int, durationMs: Int): Boolean {
        ensureBound(ctx)
        val svc = service ?: return false
        return try {
            svc.tap(x, y, durationMs)
            true
        } catch (t: Throwable) {
            service = null
            false
        }
    }
}

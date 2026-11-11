package jp.milkyfog.adcloser

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** User settings, stored in SharedPreferences. */
object Prefs {
    private const val FILE = "settings"
    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun master(ctx: Context): Boolean = sp(ctx).getBoolean("master", true)
    fun setMaster(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("master", v).apply()

    fun resident(ctx: Context): Boolean = sp(ctx).getBoolean("resident", true)
    fun setResident(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("resident", v).apply()

    fun patternA(ctx: Context): Boolean = sp(ctx).getBoolean("pattern_a", true)
    fun setPatternA(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("pattern_a", v).apply()

    fun patternB(ctx: Context): Boolean = sp(ctx).getBoolean("pattern_b", true)
    fun setPatternB(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("pattern_b", v).apply()

    fun storeRecovery(ctx: Context): Boolean = sp(ctx).getBoolean("store_recovery", true)
    fun setStoreRecovery(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("store_recovery", v).apply()

    /** Seconds to wait before the timing-based patterns (B/C) may tap. */
    fun guardSeconds(ctx: Context): Int = sp(ctx).getInt("guard_seconds", 30)
    fun setGuardSeconds(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("guard_seconds", v).apply()
}

/** In-process commands from the overlay / main screen to the accessibility service. */
object Control {
    /** Timestamp (elapsedRealtime) of the last manual "ad started" arm. 0 = none. */
    @Volatile var manualArmAt: Long = 0L

    /** When true, the current ad session is suspended until the next arm / detection. */
    @Volatile var emergencySuspend: Boolean = false
}

/** Small persistent action log so the user can see what the app did, and report it back. */
object LogStore {
    private const val KEY = "log_lines"
    private const val MAX = 80
    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("log", Context.MODE_PRIVATE)

    fun add(ctx: Context, message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date())
        val lines = all(ctx).toMutableList()
        lines.add("$time $message")
        while (lines.size > MAX) lines.removeAt(0)
        sp(ctx).edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    fun all(ctx: Context): List<String> {
        val raw = sp(ctx).getString(KEY, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split("\n")
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().remove(KEY).apply()
    }
}

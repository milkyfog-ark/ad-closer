package jp.milkyfog.adcloser

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cell Survivor's package name. It is always an ad-closer target. */
const val GAME_PACKAGE = "defense.roguelike.cell.shoot.survivor"

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

    /** Farming (auto-clicker) mode for stage grinding, added in v0.8. */
    fun farm(ctx: Context): Boolean = sp(ctx).getBoolean("farm_mode", false)
    fun setFarm(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("farm_mode", v).apply()

    /** Seconds to wait before the timing-based patterns (B/C) may tap. */
    fun guardSeconds(ctx: Context): Int = sp(ctx).getInt("guard_seconds", 30)
    fun setGuardSeconds(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("guard_seconds", v).apply()

    /**
     * Extra target apps for the ad closer (v2.0). Cell Survivor is
     * always a target and is not stored here. The set persists, so an
     * app the user checks once keeps working without re-selecting it.
     */
    fun targetPackages(ctx: Context): Set<String> =
        sp(ctx).getStringSet("target_packages", null) ?: emptySet()
    fun setTargetPackages(ctx: Context, v: Set<String>) =
        sp(ctx).edit().putStringSet("target_packages", v).apply()

    /** The last ordinary app the user had in the foreground (v2.0 shortcut). */
    fun lastForeignPkg(ctx: Context): String =
        sp(ctx).getString("last_foreign_pkg", "") ?: ""
    fun lastForeignLabel(ctx: Context): String =
        sp(ctx).getString("last_foreign_label", "") ?: ""
    fun setLastForeign(ctx: Context, pkg: String, label: String) =
        sp(ctx).edit().putString("last_foreign_pkg", pkg)
            .putString("last_foreign_label", label).apply()

    // ---- Auto-clicker settings (v2.1) ----
    /** true = specify the rate in taps per second; false = interval ms. */
    fun clickerUseCps(ctx: Context): Boolean = sp(ctx).getBoolean("clicker_use_cps", true)
    fun setClickerUseCps(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("clicker_use_cps", v).apply()
    fun clickerCps(ctx: Context): Int = sp(ctx).getInt("clicker_cps", 5)
    fun setClickerCps(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("clicker_cps", v).apply()
    fun clickerIntervalMs(ctx: Context): Int = sp(ctx).getInt("clicker_interval_ms", 500)
    fun setClickerIntervalMs(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("clicker_interval_ms", v).apply()
    /** Random jitter applied to the interval, in percent (0 = exact). */
    fun clickerJitterPct(ctx: Context): Int = sp(ctx).getInt("clicker_jitter_pct", 0)
    fun setClickerJitterPct(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("clicker_jitter_pct", v).apply()
    /** Auto-stop after this many minutes; 0 = no limit. */
    fun clickerLimitMinutes(ctx: Context): Int = sp(ctx).getInt("clicker_limit_min", 0)
    fun setClickerLimitMinutes(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("clicker_limit_min", v).apply()
    /** Target position as screen fractions (the marker's centre). */
    fun clickerX(ctx: Context): Float = sp(ctx).getFloat("clicker_x", 0.5f)
    fun clickerY(ctx: Context): Float = sp(ctx).getFloat("clicker_y", 0.5f)
    fun setClickerPos(ctx: Context, x: Float, y: Float) =
        sp(ctx).edit().putFloat("clicker_x", x).putFloat("clicker_y", y).apply()
}

/** In-process commands from the overlay / main screen to the accessibility service. */
object Control {
    /** Timestamp (elapsedRealtime) of the last manual "ad started" arm. 0 = none. */
    @Volatile var manualArmAt: Long = 0L

    /** When true, the current ad session is suspended until the next arm / detection. */
    @Volatile var emergencySuspend: Boolean = false

    /** Auto-clicker run state (v2.1), driven from the floating panel. */
    @Volatile var clickerOn: Boolean = false

    /** elapsedRealtime when the clicker was started (for the time limit). */
    @Volatile var clickerStartedAt: Long = 0L
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

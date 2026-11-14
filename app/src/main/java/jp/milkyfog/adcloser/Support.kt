package jp.milkyfog.adcloser

import android.content.Context
import android.graphics.Rect
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
    /**
     * How long each clicker press is held, in ms (v2.44). The
     * fixed 30ms stroke was too short for some apps to register
     * (user diagnosis 2026-11-15: the clicks were dispatched but
     * the target did not react), so it is adjustable 10–500ms.
     */
    fun clickerPressMs(ctx: Context): Int = sp(ctx).getInt("clicker_press_ms", 30)
    fun setClickerPressMs(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("clicker_press_ms", v).apply()
    /**
     * Finger-like tap experiment (v2.52): each stroke starts
     * within ±2px of the aim and drifts 1–3px during the press,
     * generating ACTION_MOVE events like a real finger instead
     * of a perfectly static, pixel-identical touch.
     */
    fun clickerHumanTap(ctx: Context): Boolean =
        sp(ctx).getBoolean("clicker_human_tap", false)
    fun setClickerHumanTap(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("clicker_human_tap", v).apply()
    /**
     * Hide-overlays experiment (v2.53): while the clicker runs,
     * remove BOTH overlay windows (panel + marker) for up to
     * 60s, so no touch delivered to the game is marked
     * "obscured by another app's window". Tests the user's
     * report that clicking worked before the floating-window
     * changes and stopped after.
     */
    fun clickerHideOverlay(ctx: Context): Boolean =
        sp(ctx).getBoolean("clicker_hide_overlay", false)
    fun setClickerHideOverlay(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("clicker_hide_overlay", v).apply()
    /**
     * Shizuku tap engine (v2.55): when on, the clicker injects
     * taps through Shizuku (shell identity, no accessibility
     * mark) instead of accessibility gestures. Falls back to
     * gestures per-beat when Shizuku is unavailable.
     */
    fun clickerShizuku(ctx: Context): Boolean =
        sp(ctx).getBoolean("clicker_shizuku", false)
    fun setClickerShizuku(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("clicker_shizuku", v).apply()
    /**
     * Which rows the floating panel shows (v2.46, user request
     * 2026-11-15: the panel is too big — hide the rows for the
     * features not in use right now). 「全停止」 is not switchable;
     * it stays as the emergency brake.
     */
    fun panelShowAd(ctx: Context): Boolean = sp(ctx).getBoolean("panel_show_ad", true)
    fun setPanelShowAd(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("panel_show_ad", v).apply()
    fun panelShowFarm(ctx: Context): Boolean = sp(ctx).getBoolean("panel_show_farm", true)
    fun setPanelShowFarm(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("panel_show_farm", v).apply()
    fun panelShowClicker(ctx: Context): Boolean = sp(ctx).getBoolean("panel_show_clicker", true)
    fun setPanelShowClicker(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("panel_show_clicker", v).apply()
    /**
     * Icon mode for the floating panel (v2.47, user idea
     * 2026-11-15): symbol-only buttons in one slim column —
     * grip ✥, close ✕, ad 🎞, farm 🔄/⏹, target ◎,
     * click ▷/⏸, all-stop ⚠.
     */
    fun panelIconMode(ctx: Context): Boolean = sp(ctx).getBoolean("panel_icon_mode", false)
    fun setPanelIconMode(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("panel_icon_mode", v).apply()
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

    /**
     * The floating panel's on-screen rectangle in pixels (v2.7),
     * published by OverlayService. Injected taps are delivered to the
     * topmost window, so a tap aimed at the game but covered by the
     * panel would press the panel's own buttons instead — the service
     * checks every tap against this rect and refuses + asks the panel
     * to move out of the way.
     */
    @Volatile var panelRect: Rect? = null

    /** Timestamp of the latest "panel, move away" request (v2.7). */
    @Volatile var panelNudgeAt: Long = 0L

    /** The tap point (screen fractions) the panel must move away from. */
    @Volatile var blockedTapFx: Float = 0f
    @Volatile var blockedTapFy: Float = 0f
}

/** Small persistent action log so the user can see what the app did, and report it back. */
object LogStore {
    private const val KEY = "log_lines"
    private const val MAX = 300
    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("log", Context.MODE_PRIVATE)

    // @Synchronized since v2.56: the clicker thread logs too.
    @Synchronized
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

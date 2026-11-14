package jp.milkyfog.adcloser

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.random.Random

/**
 * Watches only Cell Survivor (and, during an ad session, the Play Store /
 * Recents screens needed to get back from it) and closes rewarded ads.
 *
 * Patterns confirmed from the user's screenshots on 2026-11-11:
 *  A: top-right pill "報酬を獲得しました" with its x at about (94%, 6%).
 *  B: end card whose x is at the top-left, about (6%, 6%) or (6%, 3%),
 *     with no reward text -> timing based, guarded by [Prefs.guardSeconds].
 *  C: a Google Play link at the top-left must be tapped; the Play app then
 *     opens full-screen and the user must switch back via Recents before
 *     the ad's x appears.
 *  Store sheet: Play opens as a sheet over the ad (its x at about (93%, 23%));
 *     tapping that x returns to the ad. The store sometimes opens by itself.
 *
 * Safety: this service only ever taps the specific points above. It never
 * taps the centre of the screen (where install / open buttons live), and it
 * only acts on the Play Store while an ad session is active.
 */
class AdCloserService : AccessibilityService() {

    companion object {
        const val GAME_PKG = "defense.roguelike.cell.shoot.survivor"
        const val STORE_PKG = "com.android.vending"
        private val RECENTS_PKGS = setOf(
            "com.android.systemui",
            "com.sec.android.app.launcher",
            "com.android.launcher3"
        )
        private val AD_CLASS_HINTS = listOf(
            "AdActivity", "AdOverlay", "Interstitial", "AppLovin", "UnityAds",
            "AdColony", "Vungle", "IronSource", "ironsource", "MBridge",
            "Pangle", "AudienceNetwork"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private val tick = object : Runnable {
        override fun run() {
            try {
                evaluate()
            } catch (e: Exception) {
                // Never let one bad screen kill the watcher.
            }
            handler.postDelayed(this, 700)
        }
    }

    // ---- session state ----
    private var sessionActive = false
    private var adStartAt = 0L
    private var consumedArmAt = 0L
    private var lastATapAt = 0L
    private var aTapCount = 0
    private var lastCloseTapAt = 0L
    private var closeTapCount = 0
    // v2.8: pattern D fires only after the guard period (or 8s of the
    // label being continuously present) — the user's 2026-11-12 log
    // showed it tapping in the very second the session started, on a
    // label that then vanished, after which the session wrongly
    // concluded the ad had closed. lastAnyTapAt feeds the last-resort
    // finisher; sweep* is its state.
    private var dLabelSince = 0L
    private var lastAnyTapAt = 0L
    private var sweepCount = 0
    // v2.22: gap before the next sweep tap; drops to 1.2s right
    // after the reveal tap (see the finisher).
    private var sweepGapMs = 6_000L
    // v2.18: whether any real branch has tapped during this session,
    // and whether the finisher sweep has begun. Sessions that never
    // tapped are probably sitting on an ad no pattern recognised
    // (the Temu lamp creative, 2026-11-13: its session died after
    // 4 signal-less seconds while the ad was still on screen, and
    // the log then claimed the ad had closed).
    private var sessionTapped = false
    private var finisherActive = false
    // v2.21: true once this session has been inside the pattern-H
    // or pattern-G funnels. Funnel sessions get the long no-signal
    // grace and the early finisher even after tapping: the next
    // funnel stage may expose nothing at all (the 2026-11-13
    // 21:16 storegate video — its pill text was not in the node
    // tree, and the session, having tapped earlier stages, died
    // under the plain 4s rule before anything could act).
    private var funnelSeen = false
    private var lastSweepAt = 0L
    // Pattern I state (v2.10): top-right スキップ pill.
    private var skipSince = 0L
    private var lastSkipTapAt = 0L
    private var skipTapCount = 0
    // Pattern J state (v2.12): bottom-left 閉じる button.
    private var jSince = 0L
    private var lastJTapAt = 0L
    private var jTapCount = 0
    private var lastTopLeftTapAt = 0L
    private var topLeftStage = 0 // 0 = none, 1 = tried y=6%, 2 = tried y=3%, 3 = retried y=6%
    private var storeCloseCount = 0
    private var storeExcursions = 0
    // v2.16: store-sheet episodes. The sheet can open over an end
    // card BEFORE any session starts (foreground flips to the store,
    // so no session ever sees the card), and the old code also gave
    // up permanently after 3 x taps (count reset only at session
    // start, and reaching the cap ended the session). The user
    // explicitly asked for this x to be automated (2026-11-13), so
    // the store branch now also arms from recency: it acts when a
    // target app was in the foreground within the last 10 minutes.
    // Episodes: 3 x taps -> one Back -> 30s backoff -> re-arm,
    // up to 3 episodes, then a single give-up log line.
    private var lastTargetPkg = ""
    private var lastTargetAt = 0L
    private var storeEpisodes = 0
    private var storeRearmAt = 0L
    private var storeBackUsed = false
    private var storeGaveUp = false
    private var noAdSignalSince = 0L
    private var lastStoreActionAt = 0L
    // Return-from-full-screen-store state (v0.2). The v0.1 approach tapped
    // the game's name label inside Recents on every 700ms tick; on the
    // user's Galaxy S20 that tap does nothing, so it fired ~40 times in
    // 18 seconds and stranded the user on the Recents screen. v0.2 brings
    // the game forward with its launch intent first and uses Recents only
    // as a single, throttled fallback.
    private var returningFromStore = false
    private var returnStep = 0
    private var returnStepAt = 0L
    private var returnBlocked = false
    // v2.24: true while a driveReturn Recents fallback is in
    // flight, so its success can be booked against the budget.
    private var recentsArmed = false
    // v2.24: pattern-C pill taps by real bounds (see handleGame).
    private var cPillTaps = 0
    private var lastCPillTapAt = 0L
    // v2.25: after any store/excursion return, the end-card branch
    // stays quiet for a while (the ad resumes playing on return;
    // tapping at once reopens the store — the 2026-11-14 loop).
    private var postReturnQuietUntil = 0L
    // v2.25: end-card branch meter tracking (parked-meter escape).
    private var ecMeterVal = -1f
    private var ecMeterStuckSince = 0L
    // v2.30: pill-only patience (see handleGame). sawStorePillAt =
    // last time a top-left "Google Play" pill was on screen;
    // pillOnlySince = start of the current pill-only stretch.
    private var sawStorePillAt = 0L
    private var pillOnlySince = 0L
    private var lastEventClass = ""
    // Pattern E state (v1.5, from the user's TikTok-card screenshots on
    // 2026-11-12): an end card whose only corner control is first a ">>"
    // that opens the advertiser's own app full-screen; after switching
    // back to the game the same corner (0.926, 0.072) has become an x.
    // eStage: 0 = not started, 1 = ">>" tapped / excursion expected,
    // 2 = back in the game, tapping the x.
    private var eStage = 0
    private var eTapCount = 0
    private var lastETapAt = 0L
    private var returningFromExcursion = false
    private var excursionAt = 0L
    private var excursionTries = 0
    // v2.29: excursion entry/give-up log throttle (see handleExcursion).
    private var lastExcEntryPkg = ""
    private var lastExcEntryAt = 0L
    private var excEntryLogged = false
    // Pattern F state (v1.7): Meta Audience Network end card.
    private var fTapCount = 0
    // Pattern G state (v2.2): welcome-bonus interactive end card.
    private var gTapCount = 0
    private var lastGTapAt = 0L
    private var gCardSince = 0L
    // Pattern H state (v2.3): Temu funnel card. hScreenKey identifies
    // which funnel screen is up ("creative" / "landing" / "card");
    // hSince / hTapCount are per-screen.
    private var hScreenKey = ""
    private var hSince = 0L
    private var hTapCount = 0
    private var lastHTapAt = 0L
    private var lastFTapAt = 0L
    private var fCardSince = 0L
    // Pattern F pod-advance state (v1.8).
    private var fCounterText = ""
    private var fStageSince = 0L
    private var fAdvanceTaps = 0
    // v2.23: last pod meter reading and when it last moved.
    private var fLastMeter = -1f
    private var fMeterStuckSince = 0L
    // Multi-target state (v2.0): the package the current session belongs
    // to (returns go there, not always to the game), the package of the
    // window being evaluated, and the last foreign app recorded for the
    // settings shortcut.
    private var sessionPkg: String = GAME_PKG
    private var currentPkg: String = ""
    private var lastRecordedForeign = ""
    private var lastSpeedTapAt = 0L
    // v2.11: panel-evacuation throttle for farming.
    // v2.13: the x2 keeper counts "missing" battle frames instead
    // of timing them (see the keeper block for why).
    private var lastFarmNudgeAt = 0L
    private var x2MissingFrames = 0
    // v2.37: keeper take four — time of the first counted "missing"
    // frame of the current accumulation, the post-tap verify
    // window, and the per-episode tap count. See the keeper block
    // for why the v2.33 continuous-HUD stretch and the
    // disarm-until-seen latch were dropped.
    private var x2FirstMissingAt = 0L
    private var x2VerifyUntil = 0L
    private var x2EpisodeTaps = 0
    // When the last session ended in failure (v2.5 excursion watch).
    private var lastFailedSessionAt = 0L

    /** Ad-closing targets: Cell Survivor plus the user's checked apps. */
    private fun isTarget(pkg: String): Boolean =
        pkg == GAME_PKG || pkg in Prefs.targetPackages(this)

    /** Remember the last ordinary app seen in the foreground so the
     *  settings screen can offer "add the app you just used". */
    private fun recordLastForeign(pkg: String) {
        if (pkg == packageName || pkg in RECENTS_PKGS || pkg == STORE_PKG) return
        if (pkg == lastRecordedForeign) return
        lastRecordedForeign = pkg
        val label = try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            pkg
        }
        Prefs.setLastForeign(this, pkg, label)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        LogStore.add(this, "監視サービスを開始しました")
        if (!ticking) {
            ticking = true
            handler.post(tick)
        }
    }

    override fun onDestroy() {
        ticking = false
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val cls = event.className?.toString() ?: return
        lastEventClass = cls
        val pkg = event.packageName?.toString() ?: return
        currentPkg = pkg
        if (isTarget(pkg) && Control.manualArmAt == 0L) {
            val lower = cls.lowercase()
            if (AD_CLASS_HINTS.any { cls.contains(it) } || lower.contains("adactivity")) {
                if (!sessionActive && Prefs.master(this) && Prefs.resident(this)) {
                    startSession(SystemClock.elapsedRealtime(), "広告画面を検知")
                }
            }
        }
        evaluate()
    }

    override fun onInterrupt() {}

    // ---------------- snapshot ----------------

    private class Snap(
        val texts: List<String>,
        val boundsByText: Map<String, Rect>,
        // Highest progress ratio (0..1) among nodes exposing range
        // info (e.g. a native progress bar); -1 when none is exposed.
        // Added in v1.9 to read the Meta ad meter.
        val maxProgress: Float
    ) {
        fun has(text: String): Boolean = texts.any { it.contains(text) }
        /** Whole-label match: "開く" must not match "ストアを開く". */
        fun hasExact(text: String): Boolean = texts.any { it.trim() == text }
        fun hasRegex(pattern: String): Boolean =
            texts.any { Regex(pattern).containsMatchIn(it) }
        fun firstRegex(pattern: String): String? =
            texts.firstOrNull { Regex(pattern).containsMatchIn(it) }
        fun boundsOf(text: String): Rect? =
            boundsByText.entries.firstOrNull { it.key.contains(text) }?.value
    }

    private fun snapshot(root: AccessibilityNodeInfo): Snap {
        val texts = ArrayList<String>()
        val bounds = HashMap<String, Rect>()
        var budget = 500
        var maxProgress = -1f
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || budget <= 0) return
            budget--
            val t = node.text?.toString()?.trim()
            val d = node.contentDescription?.toString()?.trim()
            val label = when {
                !t.isNullOrEmpty() -> t
                !d.isNullOrEmpty() -> d
                else -> null
            }
            if (label != null) {
                texts.add(label)
                if (!bounds.containsKey(label)) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    bounds[label] = r
                }
            }
            val ri = node.rangeInfo
            if (ri != null && ri.max > ri.min) {
                val ratio = (ri.current - ri.min) / (ri.max - ri.min)
                if (ratio > maxProgress) maxProgress = ratio
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return Snap(texts, bounds, maxProgress)
    }

    // ---------------- helpers ----------------

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun screenW(): Int = resources.displayMetrics.widthPixels
    private fun screenH(): Int = resources.displayMetrics.heightPixels

    /** Logs gesture cancellations (v1.6) so a failed tap can be told
     *  apart: cancelled = our side lost the gesture; completed but the
     *  game did not move = the game ignored the press. */
    private fun gestureCallback(why: String) =
        object : GestureResultCallback() {
            override fun onCancelled(gestureDescription: GestureDescription?) {
                LogStore.add(this@AdCloserService, "タップがキャンセルされました：$why")
            }
        }

    private var lastPanelBlockLogAt = 0L

    /**
     * True when the tap point is covered by our own floating panel
     * (v2.7): the tap would press the panel's buttons instead of the
     * game — the user's "buttons get pressed by themselves" bug.
     * Refusing also asks the panel to move out of the way.
     */
    private var lastSkipBlockLogAt = 0L

    private fun panelBlocks(fx: Double, fy: Double, why: String): Boolean {
        // v2.34: the roulette panel's スキップ zone (x 0.55–0.80,
        // y 0.50–0.60 — padded around the measured checkbox at
        // (0.598–0.635, 0.543–0.568) and its label) is hard-blocked
        // for EVERY point tap in this app, not just farming: the
        // user has insisted since 2026-11-12 that the skip must
        // never be pressed, and reported on 2026-11-14 that a press
        // reaction shows there while the roulette driver runs.
        // Whether the cause is a coordinate slip or the game
        // treating an ill-timed tap as a skip input, no tap from
        // this app may land in the zone. Every legitimate target
        // lives outside it (roulette 確認 at y 0.624, card picks at
        // x ≤ 0.495 / ≥ 0.825, ad close points at the edges).
        if (fx in 0.55..0.80 && fy in 0.50..0.60) {
            if (now() - lastSkipBlockLogAt > 5_000) {
                lastSkipBlockLogAt = now()
                LogStore.add(this, "$why：スキップ領域のためタップを中止しました")
            }
            return true
        }
        val rect = Control.panelRect ?: return false
        val x = (screenW() * fx).toInt()
        val y = (screenH() * fy).toInt()
        if (!rect.contains(x, y)) return false
        Control.blockedTapFx = fx.toFloat()
        Control.blockedTapFy = fy.toFloat()
        Control.panelNudgeAt = now()
        if (now() - lastPanelBlockLogAt > 5_000) {
            lastPanelBlockLogAt = now()
            LogStore.add(this, "$why：タップ先がフローティングパネルと重なっていたため中止し、パネルを退避します")
        }
        return true
    }

    private fun tap(xFraction: Double, yFraction: Double, why: String) {
        if (panelBlocks(xFraction, yFraction, why)) return
        val x = (screenW() * xFraction).toFloat()
        val y = (screenH() * yFraction).toFloat()
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 70))
            .build()
        gestureGuardUntil = now() + 300
        lastAnyTapAt = now()
        if (sessionActive) sessionTapped = true
        dispatchGesture(gesture, gestureCallback(why), null)
        LogStore.add(this, "$why (${(xFraction * 100).toInt()}%, ${(yFraction * 100).toInt()}%) をタップ")
    }

    /**
     * Double tap for game buttons (v1.6). Single synthetic presses —
     * plain (v1.2) and firmer (v1.4) alike — were logged repeatedly on
     * the roulette / victory 確認 buttons while the game stayed on the
     * same screen, so the press now comes twice, 400ms apart, delivered
     * as one gesture. Card picks keep the proven single tap.
     */
    private fun tapButton(xFraction: Double, yFraction: Double, why: String) {
        if (panelBlocks(xFraction, yFraction, why)) return
        val x = (screenW() * xFraction).toFloat()
        val y = (screenH() * yFraction).toFloat()
        fun stroke(start: Long) = GestureDescription.StrokeDescription(
            Path().apply { moveTo(x, y) }, start, 80
        )
        val gesture = GestureDescription.Builder()
            .addStroke(stroke(0))
            .addStroke(stroke(400))
            .build()
        gestureGuardUntil = now() + 900
        lastAnyTapAt = now()
        if (sessionActive) sessionTapped = true
        dispatchGesture(gesture, gestureCallback(why), null)
        LogStore.add(this, "$why (${(xFraction * 100).toInt()}%, ${(yFraction * 100).toInt()}%) をタップ")
    }

    /**
     * Firm single press (v2.15): one 280ms stroke. The 確認 buttons
     * have now ignored plain 70ms taps (v1.2/v1.4) and 80ms double
     * taps (v1.6+) alike, while single taps work fine on card picks
     * and the speed icon — so the confirm actions alternate between
     * the double tap and this longer press across attempts, in case
     * the game's button handler wants a sustained touch.
     */
    private fun tapPress(xFraction: Double, yFraction: Double, why: String) {
        if (panelBlocks(xFraction, yFraction, why)) return
        val x = (screenW() * xFraction).toFloat()
        val y = (screenH() * yFraction).toFloat()
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 280))
            .build()
        gestureGuardUntil = now() + 600
        lastAnyTapAt = now()
        if (sessionActive) sessionTapped = true
        dispatchGesture(gesture, gestureCallback(why), null)
        LogStore.add(this, "$why (${(xFraction * 100).toInt()}%, ${(yFraction * 100).toInt()}%) を長押しタップ")
    }

    /**
     * v2.32: a still longer press (500ms) for the roulette 確認.
     * Every shorter style has been ignored by this panel at least
     * once; the rotation in the roulette branch tries it alongside
     * the plain tap and the double tap.
     */
    private fun tapPressLong(xFraction: Double, yFraction: Double, why: String) {
        if (panelBlocks(xFraction, yFraction, why)) return
        val x = (screenW() * xFraction).toFloat()
        val y = (screenH() * yFraction).toFloat()
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        gestureGuardUntil = now() + 900
        lastAnyTapAt = now()
        if (sessionActive) sessionTapped = true
        dispatchGesture(gesture, gestureCallback(why), null)
        LogStore.add(this, "$why (${(xFraction * 100).toInt()}%, ${(yFraction * 100).toInt()}%) を長押しタップ（500ms）")
    }

    private fun tapRect(r: Rect, why: String) {
        if (panelBlocks(
                r.centerX().toDouble() / screenW(),
                r.centerY().toDouble() / screenH(), why
            )
        ) return
        val path = Path().apply { moveTo(r.centerX().toFloat(), r.centerY().toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 70))
            .build()
        gestureGuardUntil = now() + 300
        lastAnyTapAt = now()
        if (sessionActive) sessionTapped = true
        dispatchGesture(gesture, gestureCallback(why), null)
        LogStore.add(this, "$why をタップ")
    }

    private fun startSession(at: Long, why: String) {
        sessionActive = true
        lastFailedSessionAt = 0L
        if (isTarget(currentPkg)) sessionPkg = currentPkg
        adStartAt = at
        lastATapAt = 0L
        aTapCount = 0
        lastCloseTapAt = 0L
        closeTapCount = 0
        lastTopLeftTapAt = 0L
        topLeftStage = 0
        storeCloseCount = 0
        storeExcursions = 0
        storeEpisodes = 0
        storeRearmAt = 0L
        storeBackUsed = false
        storeGaveUp = false
        noAdSignalSince = 0L
        returningFromStore = false
        returnStep = 0
        returnBlocked = false
        recentsArmed = false
        cPillTaps = 0
        lastCPillTapAt = 0L
        postReturnQuietUntil = 0L
        ecMeterVal = -1f
        ecMeterStuckSince = 0L
        sawStorePillAt = 0L
        pillOnlySince = 0L
        eStage = 0
        eTapCount = 0
        lastETapAt = 0L
        returningFromExcursion = false
        fTapCount = 0
        lastFTapAt = 0L
        fCardSince = 0L
        fCounterText = ""
        fStageSince = 0L
        fAdvanceTaps = 0
        fLastMeter = -1f
        fMeterStuckSince = 0L
        gTapCount = 0
        lastGTapAt = 0L
        gCardSince = 0L
        hScreenKey = ""
        sessionReturns = 0
        capLogged = false
        hSince = 0L
        hTapCount = 0
        lastHTapAt = 0L
        dLabelSince = 0L
        lastAnyTapAt = 0L
        sweepCount = 0
        sweepGapMs = 6_000L
        funnelSeen = false
        lastSweepAt = 0L
        sessionTapped = false
        finisherActive = false
        skipSince = 0L
        lastSkipTapAt = 0L
        skipTapCount = 0
        jSince = 0L
        lastJTapAt = 0L
        jTapCount = 0
        LogStore.add(this, "$why：広告の監視を開始")
    }

    private fun endSession(note: String) {
        if (sessionActive) LogStore.add(this, note)
        // A session that ended in failure ("…手動で閉じてください")
        // means an ad is very likely still on screen and the user is
        // still fighting it. Keep watching for store / advertiser-app
        // excursions for 60s so a jump to e.g. the Temu app in that
        // window still gets pulled back (user report 2026-11-12: no
        // return from the Temu app after the session had given up).
        if (note.contains("手動")) lastFailedSessionAt = now()
        sessionActive = false
        returningFromStore = false
        returningFromExcursion = false
        noAdSignalSince = 0L
    }

    // ---------------- main evaluation ----------------

    // ---------------- auto-clicker engine (v2.1) ----------------
    // Independent of the ad sessions: taps the marker position at the
    // configured rate while Control.clickerOn is set from the panel.
    // Individual taps are NOT logged (they would flood the 80-line log
    // in seconds); only start / stop / auto-stop are.
    private var clickerLoopRunning = false

    // Until this time the clicker must not dispatch: another gesture
    // (farm / ad closing) is in flight, and a clicker tap landing
    // mid-gesture cancels it (v2.6 — the user's 2026-11-12 log showed
    // repeated タップがキャンセルされました while both ran).
    private var gestureGuardUntil = 0L

    private val clickerRunnable = object : Runnable {
        override fun run() {
            if (!Control.clickerOn) {
                clickerLoopRunning = false
                return
            }
            if (!Prefs.master(this@AdCloserService)) {
                Control.clickerOn = false
                clickerLoopRunning = false
                LogStore.add(this@AdCloserService, "オートクリッカー：全体オフのため停止しました")
                return
            }
            val limitMin = Prefs.clickerLimitMinutes(this@AdCloserService)
            if (limitMin > 0 &&
                SystemClock.elapsedRealtime() - Control.clickerStartedAt > limitMin * 60_000L
            ) {
                Control.clickerOn = false
                clickerLoopRunning = false
                LogStore.add(this@AdCloserService, "オートクリッカー：設定時間になったため自動停止しました")
                return
            }
            if (now() < gestureGuardUntil) {
                // Another gesture is in flight; skip this beat rather
                // than cancel it.
                handler.postDelayed(this, 100)
                return
            }
            // Never let the clicker press the floating panel's own
            // buttons: if the marker sits under the panel, skip beats
            // (the panel is asked to move away) instead of toggling
            // the panel's controls.
            val rect = Control.panelRect
            if (rect != null) {
                val px = (screenW() * Prefs.clickerX(this@AdCloserService)).toInt()
                val py = (screenH() * Prefs.clickerY(this@AdCloserService)).toInt()
                if (rect.contains(px, py)) {
                    Control.blockedTapFx = Prefs.clickerX(this@AdCloserService)
                    Control.blockedTapFy = Prefs.clickerY(this@AdCloserService)
                    Control.panelNudgeAt = now()
                    handler.postDelayed(this, 300)
                    return
                }
            }
            val fx = Prefs.clickerX(this@AdCloserService).toDouble()
            val fy = Prefs.clickerY(this@AdCloserService).toDouble()
            val x = (screenW() * fx).toFloat()
            val y = (screenH() * fy).toFloat()
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        Path().apply { moveTo(x, y) }, 0, 30
                    )
                )
                .build()
            dispatchGesture(gesture, null, null)
            handler.postDelayed(this, nextClickerDelayMs())
        }
    }

    private fun nextClickerDelayMs(): Long {
        val base = if (Prefs.clickerUseCps(this)) {
            1000.0 / Prefs.clickerCps(this).coerceAtLeast(1)
        } else {
            Prefs.clickerIntervalMs(this).toDouble()
        }
        val jitter = Prefs.clickerJitterPct(this)
        val factor = if (jitter > 0) {
            1.0 + (Random.nextDouble() * 2.0 - 1.0) * jitter / 100.0
        } else {
            1.0
        }
        return (base * factor).toLong().coerceAtLeast(20L)
    }

    private fun syncClicker() {
        if (Control.clickerOn && !clickerLoopRunning) {
            clickerLoopRunning = true
            handler.post(clickerRunnable)
            LogStore.add(this, "オートクリッカーを開始しました")
        } else if (!Control.clickerOn && clickerLoopRunning) {
            clickerLoopRunning = false
            handler.removeCallbacks(clickerRunnable)
            LogStore.add(this, "オートクリッカーを終了しました")
        }
    }

    private fun evaluate() {
        syncClicker()
        if (!Prefs.master(this)) return

        if (Control.emergencySuspend) {
            Control.emergencySuspend = false
            if (sessionActive) endSession("緊急停止しました")
        }

        // Manual arm from the overlay button.
        val arm = Control.manualArmAt
        if (arm > 0 && arm != consumedArmAt) {
            consumedArmAt = arm
            if (!sessionActive) startSession(arm, "手動開始")
        }

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        currentPkg = pkg
        recordLastForeign(pkg)
        val snap = snapshot(root)

        when {
            isTarget(pkg) -> {
                // v2.16: remember the last target foreground (arms
                // the store branch by recency) and close out any
                // store-sheet episode — we are back, so it ended.
                lastTargetPkg = pkg
                lastTargetAt = now()
                otherPkg = ""
                if (storeCloseCount > 0 || storeEpisodes > 0 || storeGaveUp) {
                    storeCloseCount = 0
                    storeEpisodes = 0
                    storeRearmAt = 0L
                    storeBackUsed = false
                    storeGaveUp = false
                }
                if (returningFromStore) {
                    // We made it back to the target app from the store.
                    returningFromStore = false
                    storeExcursions++
                    // v2.24: book a Recents-fallback return against
                    // the return budget like any other return. And
                    // do NOT reset topLeftStage here: resetting it
                    // restarted the corner escalation from its
                    // first candidate after every store visit,
                    // which (with the ungated Recents return) looped
                    // store<->game ~25 times on 2026-11-14.
                    if (recentsArmed) {
                        recentsArmed = false
                        bookReturn()
                    }
                    postReturnQuietUntil = now() + 15_000
                    // v2.31: pattern C committed (pill pressed) —
                    // prime the escalation so its first post-return
                    // tap lands ~1.5s after the return, while the
                    // revealed x is still visible.
                    if (cPillTaps >= 1 && lastTopLeftTapAt > now() - 2_500) {
                        lastTopLeftTapAt = now() - 2_500
                    }
                    noAdSignalSince = 0L
                    if (eStage == 1) eStage = 2
                    LogStore.add(this, "対象アプリに戻りました。×の出現を待ちます")
                }
                if (returningFromExcursion) {
                    // Back from the advertiser's app (pattern E).
                    returningFromExcursion = false
                    postReturnQuietUntil = now() + 15_000
                    if (cPillTaps >= 1 && lastTopLeftTapAt > now() - 2_500) {
                        lastTopLeftTapAt = now() - 2_500
                    }
                    noAdSignalSince = 0L
                    if (eStage == 1) {
                        eStage = 2
                        lastETapAt = 0L
                    }
                    LogStore.add(this, "広告主アプリから対象アプリに戻りました")
                }
                handleGame(snap)
                if (pkg == GAME_PKG) maybeFarmTick()
            }
            pkg == STORE_PKG -> {
                // v2.16: also arm from recency — a sheet that opens
                // over an end card before any session starts must
                // still be closed automatically. Recency is scoped
                // to target apps, so the user's own Play browsing
                // from the launcher is never touched.
                val recentTarget = lastTargetAt > 0 && now() - lastTargetAt < 600_000
                val watch = sessionActive ||
                    (lastFailedSessionAt > 0 && now() - lastFailedSessionAt < 60_000) ||
                    recentTarget
                if (watch && Prefs.storeRecovery(this)) {
                    if (!sessionActive && recentTarget) sessionPkg = lastTargetPkg
                    handleStore(snap)
                }
            }
            pkg in RECENTS_PKGS -> {
                if (returningFromStore) driveReturn(inRecents = true)
            }
            else -> {
                val watch = sessionActive ||
                    (lastFailedSessionAt > 0 && now() - lastFailedSessionAt < 60_000)
                if (watch) {
                    // v2.19 user-takeover rule: known advertiser apps
                    // (Meta overlay, TikTok, Temu) are excursions to
                    // return from immediately. Any OTHER app is never
                    // returned from — if one stays in front for 6s
                    // during a session, the USER has taken over (in
                    // the incident they opened Muse to report the
                    // problem, and this app's own settings screen):
                    // end the session instead of fighting them.
                    val adPkgs = setOf(
                        "com.facebook.aura", "com.zhiliaoapp.musically",
                        "com.ss.android.ugc.trill", "com.ss.android.ugc.aweme",
                        "com.einnovation.temu"
                    )
                    if (pkg != otherPkg) {
                        otherPkg = pkg
                        otherPkgSince = now()
                    }
                    when {
                        // v2.23: Meta pods flicker the foreground
                        // between the game and aura every 2-3s (user
                        // log 2026-11-14: ten excursion entries in
                        // 22s, no returns). Acting on each flicker
                        // only spams the log and churns state, so an
                        // advertiser app must stay in front for 1.5s
                        // before it counts as an excursion.
                        pkg in adPkgs && now() - otherPkgSince > 1_500 ->
                            handleExcursion(pkg)
                        sessionActive && now() - otherPkgSince > 4_000 ->
                            endSession("別のアプリが使われているため監視を終了します")
                        else -> {
                            // Non-ad app, session active, under 6s:
                            // wait and see. Watch-only (no session):
                            // nothing to return from either.
                        }
                    }
                }
            }
        }
    }

    /**
     * Pattern E excursion (v1.5): during a session the foreground became
     * an app that is neither the game, the store, nor Recents — the
     * advertiser's own app, opened by the end card's corner control.
     * Bring the game forward with its launch intent, like pattern C.
     * (Our own app's screens are ignored.)
     */
    private fun handleExcursion(pkg: String) {
        if (pkg == packageName) return
        if (!returningFromExcursion) {
            returningFromExcursion = true
            excursionAt = now()
            excursionTries = 0
            // v2.29: while an advertiser app stays in front with
            // the return budget closed, episodes used to end and
            // re-enter every few seconds, repeating this entry
            // line each time (2026-11-14 01:26: the same aura
            // line 14 times in 35s). Log an entry at most once
            // per package per minute; silent re-entries still
            // retry the return in case the budget reopens.
            excEntryLogged = pkg != lastExcEntryPkg || now() - lastExcEntryAt > 60_000
            if (excEntryLogged) {
                lastExcEntryPkg = pkg
                lastExcEntryAt = now()
                LogStore.add(this, "別アプリ（$pkg）に移りました。対象アプリに戻します")
            }
            return
        }
        val waited = now() - excursionAt
        if (waited > 1800 && excursionTries < 3) {
            excursionTries++
            excursionAt = now()
            if (!returnToApp()) {
                // Refused (budget closed): resolve this episode
                // via the give-up branch instead of re-entering
                // at once (v2.29).
                excursionTries = 3
            }
        } else if (excursionTries >= 3 && waited > 3000) {
            returningFromExcursion = false
            if (excEntryLogged) {
                LogStore.add(this, "自動で対象アプリに戻れませんでした。手動で元のアプリに戻ってください")
            }
        }
    }

    private fun handleGame(snap: Snap) {
        // AdMob end cards come in Japanese and English variants; the
        // English "Reward granted" card (× at about (94%, 5%)) reached the
        // user on 2026-11-11 and v0.3 missed it because only the Japanese
        // text was registered.
        val rewardReady = snap.has("報酬を獲得しました") || snap.has("Reward granted")
        val endCard = snap.has("インストール") || snap.has("Google Play") ||
            snap.has("Install") || snap.has("ダウンロード")
        // v2.30: the top-left "Google Play" pill, detected once
        // here for the pattern-C / pill-only logic in the end-card
        // branch and the finisher guard below.
        val dmMain = resources.displayMetrics
        val gpPill = snap.boundsByText.entries
            .filter { it.key.contains("Google Play") }
            .map { it.value }
            .firstOrNull { r ->
                r.centerX() < dmMain.widthPixels * 0.35 &&
                    r.centerY() < dmMain.heightPixels * 0.12 &&
                    r.width() < dmMain.widthPixels * 0.5 &&
                    r.height() < dmMain.heightPixels * 0.10
            }
        if (gpPill != null) sawStorePillAt = now()
        // Pattern I (v2.10, user report 2026-11-12): an end card whose
        // working exit is the 「スキップ」 pill at the TOP-RIGHT (the
        // Gemini ad in the report; its bottom 「閉じる」/ダウンロード
        // buttons are not the exit — the user instructed that the
        // top-right skip is what must be pressed). Recognised by the
        // pill's actual bounds in the top-right area, so the game's
        // own スキップ checkbox (mid-screen, on the roulette panel)
        // never matches.
        val skipBounds = snap.boundsOf("スキップ")
        val skipInCorner = skipBounds != null &&
            skipBounds.centerX() > screenW() * 0.60 &&
            skipBounds.centerY() < screenH() * 0.15
        // Pattern J (v2.12, user report 2026-11-13): the pattern-I
        // family's other variant — a black info panel at the bottom
        // (title, domain, description) whose exit is the 「閉じる」
        // button at the BOTTOM-LEFT (with the green check), next to
        // a 詳細/ダウンロード CTA. This variant has no skip pill;
        // the user instructed the bottom-left 閉じる is the button
        // to press. Recognised by the label's bounds in the
        // bottom-left area (pattern D covers only the top-left).
        val closeBottomBounds = snap.boundsOf("閉じる") ?: snap.boundsOf("Close")
        val closeBottomLeft = closeBottomBounds != null &&
            closeBottomBounds.centerY() > screenH() * 0.85 &&
            closeBottomBounds.centerX() < screenW() * 0.40
        // Pattern D (found 2026-11-11): an end card whose only close
        // affordance is a labelled pill button "閉じる" at the top-left
        // (about (10%, 6%)), with no reward text and no install text, so
        // patterns A and B both missed it. The label is unambiguous, so
        // tap the label's own bounds — but only in the top-left corner
        // area, so a "閉じる" inside the game's own dialogs (usually
        // centred) is never touched.
        val closeBounds = snap.boundsOf("閉じる") ?: snap.boundsOf("Close")
        val closeInCorner = closeBounds != null &&
            closeBounds.centerY() < screenH() * 0.12 &&
            closeBounds.centerX() < screenW() * 0.30
        // Pattern F markers (v1.7): Meta Audience Network shows the
        // label "Ads served by Meta" throughout, plus a pod counter
        // ("Ad 1 of 2") during playback. The end card drops the counter
        // and shows an x at about (92%, 10%) — with none of the texts
        // patterns A–E key on (user report 2026-11-12: the app sat idle
        // through all three stages).
        val metaMarker = snap.has("Ads served by Meta")
        val metaCounter = snap.hasRegex("Ad \\d+ of \\d+")
        val metaCard = metaMarker && !metaCounter
        // Pattern G markers (v2.2, user screenshot 2026-11-12): an
        // interactive "welcome bonus" end card — a big red 獲得 button
        // (a store trap; never tap), a small 閉じる link BELOW it at
        // the bottom centre (NOT the pattern-D corner pill, and the
        // user explicitly said not to use it), and the real close
        // control: an x at the top-right (about (93%, 6%)). None of
        // the A–F markers match this card, so v2.1 sat idle.
        val welcomeCard = !rewardReady &&
            (snap.has("ウェルカムボーナス") || snap.has("豪華報酬") ||
                (snap.has("獲得") && closeBounds != null && !closeInCorner))
        // Pattern H markers (v2.3, user walkthrough 2026-11-12): a
        // Temu funnel. The creative's x (about (93%, 6%)) does NOT
        // close the ad — it opens Temu's landing page inside the ad;
        // that page's own x (about (96%, 4%)) launches the Temu app;
        // after returning to the game, the black end card is waiting
        // and ITS x finally ends the ad. v2.2 matched none of the
        // three screens, the session timed out after 4s of "no
        // signal", and even the excursion return never engaged.
        val temuCreative = !rewardReady && snap.has("Main Product")
        val temuLanding = snap.has("Temuを選ぶ理由") ||
            (snap.has("Temu") && snap.has("プライバシーの保護"))
        // v2.9: the card's 開く test must be a whole-label match.
        // With a substring test, the storegate screen matched here —
        // its pill 「ストアを開く」 contains 開く and the video tree
        // carries a "Temu" node — and the app tapped a non-existent
        // top-right x on the video instead of the pill (user log
        // 2026-11-12 18:46).
        val temuCard = snap.has("Shop Like a Billionaire") ||
            (snap.has("Temu") && snap.hasExact("開く"))
        // Pattern H gate screen (v2.4, user report 2026-11-12): a
        // Temu video-stage screen whose ONLY way forward is the
        // top-left pill "ストアを開く" — no x anywhere. Tapping it
        // opens the store / Temu app / landing page, all of which the
        // existing recovery flows (store sheet, full-store return,
        // excursion return, landing branch) already handle.
        val storeGateBounds = snap.boundsOf("ストアを開く")
        val storeGate = storeGateBounds != null

        if (!sessionActive) {
            if (Prefs.resident(this) &&
                (rewardReady || endCard || closeInCorner || metaCard || welcomeCard ||
                    temuCreative || temuLanding || temuCard || storeGate || skipInCorner ||
                    closeBottomLeft)
            ) {
                startSession(now(), "広告画面を検知")
            }
            return
        }

        // Store sheet rendered inside the game window: its header sits
        // part-way down the screen and its x is at about (93%, 23%).
        val playHeader = snap.boundsOf("Google Play")
        if (playHeader != null && Prefs.storeRecovery(this)) {
            val cy = playHeader.centerY().toDouble() / screenH()
            if (cy in 0.15..0.32 && now() - lastStoreActionAt > 3000 && storeCloseCount < 3) {
                lastStoreActionAt = now()
                storeCloseCount++
                tap(0.932, 0.233, "ストアのシートの×")
                return
            }
        }

        // Pattern E (v1.5): the "Google Playに行く" end card (TikTok /
        // Pangle family). Its big button text is the marker. This must
        // run before the generic endCard branch, whose pattern-B taps
        // aim at the top-left and would waste the session here.
        if (snap.has("Google Playに行く") && Prefs.patternB(this)) {
            noAdSignalSince = 0L
            val elapsedSec = (now() - adStartAt) / 1000
            when (eStage) {
                0 -> {
                    // The corner control is still ">>", not an x. The
                    // guard period ensures a mid-video frame can never
                    // trigger this tap.
                    if (elapsedSec >= Prefs.guardSeconds(this) && now() - lastETapAt > 4000) {
                        lastETapAt = now()
                        eStage = 1
                        tap(0.926, 0.072, "パターンE：右上の>>（広告主アプリが開きます）")
                    }
                }
                1 -> {
                    // No excursion happened (the tap may have swapped
                    // the control in place): treat the corner as the x.
                    if (now() - lastETapAt > 12000) {
                        eStage = 2
                        lastETapAt = 0L
                        LogStore.add(this, "パターンE：別アプリは開かなかったため、そのまま右上×を試します")
                    }
                }
                else -> {
                    if (eTapCount < 3 && now() - lastETapAt > 3500) {
                        lastETapAt = now()
                        eTapCount++
                        tap(0.926, 0.072, "パターンE：戻った後の右上×")
                    } else if (eTapCount >= 3 && now() - lastETapAt > 8000) {
                        endSession("パターンE：戻った後の×を押せませんでした（手動で閉じてください）")
                    }
                }
            }
            return
        }

        if (metaMarker && !metaCounter &&
            snap.maxProgress >= 0f && snap.maxProgress < 0.99f
        ) {
            // The counter is not visible but a progress meter is still
            // running: mid-playback (a flickery tree), NOT the end
            // card. Tapping the corner now would open Google Play
            // (user correction 2026-11-12), so just keep waiting. If
            // this state persists implausibly long, fall through and
            // treat it as the end card.
            noAdSignalSince = 0L
            if (fCardSince == 0L) fCardSince = now()
            if (now() - fCardSince < 20_000) return
        }

        // Pattern F (v1.7): Meta end card. The corner spot is shared
        // with the playback timer icon, so the tap waits until the
        // guard period since the session start has passed — or the
        // counter-less card has been on screen for 15s (the case where
        // the session itself only started at the end card).
        if (metaCard && Prefs.patternB(this)) {
            noAdSignalSince = 0L
            if (fCardSince == 0L) fCardSince = now()
            val guardPassed =
                (now() - adStartAt) / 1000 >= Prefs.guardSeconds(this)
            val settled = now() - fCardSince >= 15_000
            if (guardPassed || settled) {
                if (fTapCount < 3 && now() - lastFTapAt > 4000) {
                    lastFTapAt = now()
                    fTapCount++
                    tap(0.922, 0.103, "パターンF：右上の×（Meta広告）")
                } else if (fTapCount >= 3 && now() - lastFTapAt > 8000) {
                    endSession("パターンF：×を押せませんでした（手動で閉じてください）")
                }
            }
            return
        }

        if (metaMarker && metaCounter && Prefs.patternB(this)) {
            // Meta pod stage ("Ad 1 of 2"). The corner becomes the
            // "next" button ONLY after the bottom progress meter has
            // run to the end; tapping it earlier opens Google Play
            // (user corrections 2026-11-12 — v1.7 waited forever, and
            // v1.8's periodic taps would have been actively harmful).
            // The meter is a native progress bar whose range info the
            // snapshot reads, so tap only when it reports complete.
            // Fallback when no meter node is exposed at all: 40s/ad.
            noAdSignalSince = 0L
            fCardSince = 0L
            val counterText = snap.firstRegex("Ad \\d+ of \\d+") ?: ""
            if (counterText != fCounterText) {
                fCounterText = counterText
                fStageSince = now()
                fAdvanceTaps = 0
                fLastMeter = -1f
                fMeterStuckSince = 0L
            }
            val meterDone = snap.maxProgress >= 0.99f
            val noMeterFallback = snap.maxProgress < 0f &&
                now() - fStageSince >= 40_000
            // v2.23: some pods park the meter just short of full
            // (user screenshot 2026-11-14: the bar sits visually
            // complete and the corner button is the only way
            // forward, yet the >= 0.99 gate never passes). A meter
            // that has not moved for 8s at >= 0.90 is finished in
            // practice; a stage lasting 75s is finished regardless.
            val mp = snap.maxProgress
            if (mp >= 0f) {
                if (fLastMeter < 0f || mp - fLastMeter > 0.005f) {
                    fLastMeter = mp
                    fMeterStuckSince = now()
                }
            } else if (fLastMeter >= 0f) {
                fLastMeter = -1f
                fMeterStuckSince = 0L
            }
            val meterStuck = mp >= 0.90f && fMeterStuckSince > 0 &&
                now() - fMeterStuckSince >= 8_000
            val longStage = mp >= 0f && now() - fStageSince >= 75_000
            if ((meterDone || meterStuck || longStage || noMeterFallback) &&
                fAdvanceTaps < 3 && now() - lastFTapAt > 5_000
            ) {
                lastFTapAt = now()
                fAdvanceTaps++
                fStageSince = now()
                fLastMeter = -1f
                fMeterStuckSince = 0L
                tap(0.922, 0.103, "パターンF：次の広告へ（右上）")
            }
            return
        }

        // Pattern H (v2.3): walk the Temu funnel screen by screen.
        // Each screen counts as an ad signal so the session survives
        // the whole funnel (v2.2's session died at the first screen).
        // The storegate pill is an exact, unambiguous element: while
        // it is on screen the gate flow is correct by definition, so
        // it outranks every looser text marker (v2.9).
        val hKey = when {
            storeGate -> "storegate"
            temuCard -> "card"
            temuLanding -> "landing"
            temuCreative -> "creative"
            else -> ""
        }
        if (hKey.isNotEmpty() && Prefs.patternB(this)) {
            funnelSeen = true
            noAdSignalSince = 0L
            if (hKey != hScreenKey) {
                hScreenKey = hKey
                hSince = now()
                hTapCount = 0
                lastHTapAt = 0L
            }
            val onScreenMs = now() - hSince
            val guardPassed =
                (now() - adStartAt) / 1000 >= Prefs.guardSeconds(this)
            when (hKey) {
                "card" -> {
                    // The final card: its x really closes the ad.
                    if ((guardPassed || onScreenMs >= 8_000) &&
                        hTapCount < 3 && now() - lastHTapAt > 4000
                    ) {
                        lastHTapAt = now()
                        hTapCount++
                        tap(0.927, 0.064, "パターンH：最終カードの右上×（Temu）")
                    } else if (hTapCount >= 3 && now() - lastHTapAt > 8000) {
                        endSession("パターンH：最終カードの×を押せませんでした（手動で閉じてください）")
                    }
                }
                "landing" -> {
                    // The landing page's x launches the Temu app; the
                    // generic excursion return then brings the session
                    // app back to the final card.
                    if (onScreenMs >= 2_500 && hTapCount < 2 &&
                        now() - lastHTapAt > 4000
                    ) {
                        lastHTapAt = now()
                        hTapCount++
                        tap(0.958, 0.041, "パターンH：ランディングページの右上×（Temu）")
                    }
                }
                "storegate" -> {
                    // The gate pill: pressing it is the only way the
                    // ad advances (user instruction 2026-11-12). Wait
                    // for the guard period, or 12s on this screen when
                    // the session was armed late, so a still-playing
                    // video is never cut short.
                    if ((guardPassed || onScreenMs >= 12_000) &&
                        hTapCount < 2 && now() - lastHTapAt > 6000
                    ) {
                        lastHTapAt = now()
                        hTapCount++
                        if (storeGateBounds != null) {
                            tapRect(storeGateBounds, "パターンH：左上の「ストアを開く」")
                        } else {
                            tap(0.158, 0.062, "パターンH：左上の「ストアを開く」")
                        }
                    }
                }
                else -> {
                    // "creative": its x opens the landing page.
                    if ((guardPassed || onScreenMs >= 10_000) &&
                        hTapCount < 2 && now() - lastHTapAt > 4000
                    ) {
                        lastHTapAt = now()
                        hTapCount++
                        tap(0.927, 0.064, "パターンH：広告の右上×（Temu・ランディングへ）")
                    }
                }
            }
            return
        }

        // Pattern G (v2.2): welcome-bonus card — tap ONLY the
        // top-right x, never the 獲得 button and never the bottom
        // 閉じる link (user instruction 2026-11-12). The x may stay
        // inactive during the card's countdown, so wait for the guard
        // period since the session start, or for the card to have
        // been on screen continuously for 12s (manual-arm-late case).
        if (welcomeCard && Prefs.patternB(this)) {
            funnelSeen = true
            noAdSignalSince = 0L
            if (gCardSince == 0L) gCardSince = now()
            val guardPassed =
                (now() - adStartAt) / 1000 >= Prefs.guardSeconds(this)
            val settled = now() - gCardSince >= 12_000
            if (guardPassed || settled) {
                if (gTapCount < 3 && now() - lastGTapAt > 4000) {
                    lastGTapAt = now()
                    gTapCount++
                    tap(0.927, 0.064, "パターンG：右上の×（ウェルカムボーナス広告）")
                } else if (gTapCount >= 3 && now() - lastGTapAt > 8000) {
                    endSession("パターンG：右上の×を押せませんでした（手動で閉じてください）")
                }
            }
            return
        }

        // Pattern I: tap the top-right 「スキップ」 pill itself.
        // Same patience as G/D: the guard period, or the pill present
        // continuously for 8s (a late-armed session), before tapping.
        if (!skipInCorner) skipSince = 0L
        if (skipInCorner && Prefs.patternB(this)) {
            noAdSignalSince = 0L
            if (skipSince == 0L) skipSince = now()
            val skipReady =
                (now() - adStartAt) / 1000 >= Prefs.guardSeconds(this) ||
                    now() - skipSince >= 8_000
            if (skipReady && skipTapCount < 3 && now() - lastSkipTapAt > 4000) {
                lastSkipTapAt = now()
                skipTapCount++
                tapRect(skipBounds!!, "パターンI：右上の「スキップ」")
            } else if (skipTapCount >= 3 && now() - lastSkipTapAt > 8000) {
                endSession("パターンI：「スキップ」を押せませんでした（手動で閉じてください）")
            }
            return
        }

        // Pattern J: tap the bottom-left 「閉じる」 button itself.
        // Runs after pattern I: when both exist (the Gemini card),
        // the user's instruction was the skip pill first.
        if (!closeBottomLeft) jSince = 0L
        if (closeBottomLeft && Prefs.patternB(this)) {
            noAdSignalSince = 0L
            if (jSince == 0L) jSince = now()
            val jReady =
                (now() - adStartAt) / 1000 >= Prefs.guardSeconds(this) ||
                    now() - jSince >= 8_000
            if (jReady && jTapCount < 3 && now() - lastJTapAt > 4000) {
                lastJTapAt = now()
                jTapCount++
                tapRect(closeBottomBounds!!, "パターンJ：左下の「閉じる」ボタン")
            } else if (jTapCount >= 3 && now() - lastJTapAt > 8000) {
                endSession("パターンJ：左下の「閉じる」を押せませんでした（手動で閉じてください）")
            }
            return
        }

        if (!closeInCorner) dLabelSince = 0L
        if (closeInCorner && Prefs.patternB(this)) {
            noAdSignalSince = 0L
            if (dLabelSince == 0L) dLabelSince = now()
            // A corner close label visible in the first seconds of an
            // ad is often still inactive or a decoy that disappears
            // (the user's lucky-wheel ad, 2026-11-12): wait for the
            // guard period, or for the label to have been present
            // continuously for 8s, before spending a tap on it.
            val dReady = (now() - adStartAt) / 1000 >= Prefs.guardSeconds(this) ||
                now() - dLabelSince >= 8_000
            if (dReady && closeTapCount < 3 && now() - lastCloseTapAt > 3500) {
                lastCloseTapAt = now()
                closeTapCount++
                tapRect(closeBounds!!, "パターンD：「閉じる」ボタン")
            }
            return
        }

        if (rewardReady) {
            noAdSignalSince = 0L
            if (Prefs.patternA(this) && now() - lastATapAt > 4000) {
                if (aTapCount >= 6) {
                    endSession("報酬テキストの×を押せませんでした（手動で閉じてください）")
                    return
                }
                lastATapAt = now()
                // The reward pill is not always on the right: on
                // 2026-11-11 the user hit an English card whose pill
                // "✕ Reward granted" sits at the TOP-LEFT, and v0.4 kept
                // tapping the empty top-right corner. Decide the side
                // from where the reward text actually is, and alternate
                // the two known heights (y≈6.1% / y≈4.6%) per side.
                val rb = snap.boundsOf("報酬を獲得しました")
                    ?: snap.boundsOf("Reward granted")
                val onLeft = rb != null && rb.centerX() < screenW() / 2
                val x = if (onLeft) 0.055 else 0.942
                val y = if (aTapCount % 2 == 0) 0.061 else 0.046
                aTapCount++
                tap(x, y, if (onLeft) "パターンA（左上）：報酬テキスト横の×" else "パターンA：報酬テキスト横の×")
            }
            return
        }

        if (endCard) {
            noAdSignalSince = 0L
            if (Prefs.patternB(this)) {
                val elapsedSec = (now() - adStartAt) / 1000
                if (elapsedSec >= Prefs.guardSeconds(this)) {
                    // v2.25 veto (a): just back from the store or
                    // an advertiser app — the ad resumes playing,
                    // and tapping again at once reopens the store
                    // (the 2026-11-14 loop; user: "戻っても待たない
                    // ですぐ押すから延々ループした").
                    // v2.31: once the pill has been pressed
                    // (pattern C committed), the opposite rule
                    // wins — the x this flow reveals auto-hides
                    // within a few seconds, so the next press must
                    // be FAST (user correction 2026-11-14 01:46:
                    // "再度押すのが遅いから消えちゃう"). No quiet
                    // period then.
                    if (cPillTaps == 0 && now() < postReturnQuietUntil) return
                    // v2.25 veto (b): a progress meter is still
                    // running — the VIDEO has not finished, no
                    // matter what texts it exposes. The FREECASH
                    // video shows "Google Play"/install texts and
                    // a bottom-left seconds countdown from the
                    // first frame; tapping during the countdown
                    // leaves for the store mid-ad (user
                    // observation 2026-11-14). Escape hatch: a
                    // meter parked unchanged for 20s counts as
                    // finished (the pattern-F lesson).
                    val mp = snap.maxProgress
                    if (mp > 0f && mp < 0.99f) {
                        if (ecMeterVal < 0f || mp - ecMeterVal > 0.005f) {
                            ecMeterVal = mp
                            ecMeterStuckSince = now()
                        }
                        if (now() - ecMeterStuckSince < 20_000) return
                    } else {
                        ecMeterVal = -1f
                        ecMeterStuckSince = 0L
                    }
                    // Pattern C proper (v2.24): if a "Google Play"
                    // pill is on screen in the top-left, press the
                    // pill ITSELF — the centre of its real bounds —
                    // not the fixed corner point. The fixed point
                    // (5.8%, 6.2%) sits on the pill's left edge/icon
                    // and did not register as a press on the
                    // FREECASH video (user correction 2026-11-14:
                    // had the pill actually been pressed, the flow
                    // would have finished in one round; it was not
                    // pressed). One proper press opens the store;
                    // after the return, the x appears and the other
                    // branches (or the escalation below, whose stage
                    // store returns no longer reset) finish the ad.
                    // Capped at 2 presses per session.
                    // v2.30: a screen whose ONLY end-card signal
                    // is this pill is, for the FREECASH family, a
                    // VIDEO STILL PLAYING — its end card has an
                    // install button and no pill, and this video
                    // exposes no meter node, so veto (b) cannot
                    // protect it. Tapping now leaves for the store
                    // mid-video (the 2026-11-14 01:35-01:37
                    // loop). Wait for 60s of pill-only presence
                    // before treating the screen as a pattern-C
                    // card: a finished card never changes on its
                    // own, while a playing video ends and changes
                    // the screen (install texts appear, the pill
                    // goes away). (User correction 2026-11-14
                    // 01:46: the video does NOT restart on return;
                    // the loop's engine was the slow second press
                    // — the controls this ad reveals on a tap
                    // auto-hide within a few seconds. Hence 60s,
                    // not 75s, and the fast post-return presses
                    // below.)
                    val onlyPill = gpPill != null &&
                        !snap.has("インストール") && !snap.has("Install") &&
                        !snap.has("ダウンロード")
                    if (onlyPill && cPillTaps == 0) {
                        if (pillOnlySince == 0L) pillOnlySince = now()
                        if (now() - pillOnlySince < 60_000) return
                    } else {
                        pillOnlySince = 0L
                    }
                    val pill = gpPill
                    if (pill != null && cPillTaps < 2 &&
                        now() - lastCPillTapAt > 6_000
                    ) {
                        lastCPillTapAt = now()
                        cPillTaps++
                        tapRect(pill, "パターンC：左上の「Google Play」をタップ")
                        return
                    }
                    // Corner escalation (extended in v2.5): the user's
                    // 2026-11-12 log showed an end card where all three
                    // top-left candidates failed — some cards put the x
                    // at the top-RIGHT with no reward pill for pattern A
                    // to key on. Candidate history: (92.4%, 6.4%) is
                    // the circled x of Meta end cards (v2.19, CubeAway);
                    // (92.2%, 10.3%) is the Meta pod corner — the pod
                    // "next" button and end-card x sit LOWER than
                    // every other candidate (v2.23).
                    // v2.27: when no "Google Play" pill was ever found
                    // on this card, the left-top fixed points have
                    // nothing to hit (a real pill is pressed by its
                    // bounds above) — the most likely close spot is
                    // then the Meta corner, so try it FIRST. The
                    // 2026-11-14 01:10 log showed a text-hidden Meta
                    // pod (Ad 1 of 2 / Ad 2 of 2, markers not exposed)
                    // flailing through four wrong corners for ~50s
                    // before the escalation reached that spot. When a
                    // pill WAS tried, keep the original left-first
                    // order (pattern C cards).
                    // v2.29: the circled-x point (92.4%, 6.4%)
                    // goes FIRST in the no-pill order: it is the
                    // measured centre of the Freecash end card's x
                    // (user screenshot 2026-11-14 01:29, where the
                    // Meta-first order needed four tries and the
                    // card only closed when the escalation reached
                    // the 94.2% neighbour) and of Meta end cards
                    // (CubeAway). The Meta pod corner stays second.
                    val escPts = if (cPillTaps == 0) arrayOf(
                        0.924 to 0.064, 0.922 to 0.103, 0.942 to 0.061,
                        0.058 to 0.062, 0.058 to 0.028, 0.942 to 0.046,
                        0.058 to 0.062
                    ) else arrayOf(
                        0.058 to 0.062, 0.058 to 0.028, 0.942 to 0.061,
                        0.924 to 0.064, 0.922 to 0.103, 0.942 to 0.046,
                        0.058 to 0.062
                    )
                    val escLabels = if (cPillTaps == 0) arrayOf(
                        "パターンB：右上の候補（丸×）",
                        "パターンB：右上の候補（Meta位置）",
                        "パターンB：右上の候補1",
                        "パターンB/C：左上の候補1", "パターンB：左上の候補2",
                        "パターンB：右上の候補4", "パターンB/C：左上の候補1を再試行"
                    ) else arrayOf(
                        "パターンB/C：左上の候補1", "パターンB：左上の候補2",
                        "パターンB：右上の候補1", "パターンB：右上の候補2（丸×）",
                        "パターンB：右上の候補3（Meta位置）", "パターンB：右上の候補4",
                        "パターンB/C：左上の候補1を再試行"
                    )
                    val escWait = longArrayOf(3000, 4000, 4000, 4000, 4000, 4000, 5000)
                    // v2.31: within 10s after a return with pattern
                    // C committed, stage taps come every 1.2s —
                    // the revealed controls auto-hide within a few
                    // seconds, so the normal 4s spacing keeps
                    // arriving after they are gone.
                    val stageWait = if (cPillTaps >= 1 && now() - lastReturnAt < 10_000) {
                        1_200L
                    } else {
                        escWait[topLeftStage]
                    }
                    if (topLeftStage in 0..6 && now() - lastTopLeftTapAt > stageWait) {
                        val p = escPts[topLeftStage]
                        val label = escLabels[topLeftStage]
                        lastTopLeftTapAt = now()
                        topLeftStage++
                        tap(p.first, p.second, label)
                    } else if (topLeftStage == 7 && now() - lastTopLeftTapAt > 8000) {
                        // None of the candidates closed the ad: stop so
                        // the app never keeps tapping, and let the user
                        // close it.
                        endSession("候補の×を押せませんでした（手動で閉じてください）")
                    }
                }
            }
            return
        }

        // Playable ads with almost no text hooks (the user's
        // 2026-11-12 lucky-wheel ad: a wheel, an orange "Go" button,
        // a top-right x, an Ad badge) expose a lone "Go" — enough to
        // keep the session alive, never a tap target.
        if (snap.has("Go")) noAdSignalSince = 0L

        // Last-resort finisher (v2.8): a session still alive after
        // 50s with no branch having tapped for 10s is an ad none of
        // the patterns recognised (a long session cannot be plain
        // gameplay — signal-less sessions die in 4s). Sweep the known
        // top-right x positions. Never while a progress meter reads
        // mid-range: an early corner tap can open the store (the
        // pattern-F lesson).
        val meterMid = snap.maxProgress > 0.0 && snap.maxProgress < 0.99
        // v2.26: a screen exposing NO text nodes at all is the
        // game's own canvas (the game exposes nothing to the
        // accessibility tree). The 2026-11-14 00:46-00:56 sessions
        // outlived their ads into the victory/game screens — every
        // closing diagnostic read texts=[] — and, funnelSeen having
        // granted them the long grace, the finisher swept blind
        // points (including the centre reveal tap) on the game
        // itself. On a bare canvas there is nothing an ad branch
        // could legitimately press: no finisher, and the session
        // ends on the short rule like any finished ad.
        val bareCanvas = snap.texts.isEmpty()
        // v2.18: a session that has never tapped gets the finisher
        // at 15s instead of 50s — it is most likely parked on an
        // unrecognised creative whose x sits at the first sweep
        // point, and waiting 50s just leaves the ad on screen.
        val finisherStart = if (!sessionTapped || funnelSeen) 15_000 else 50_000
        // v2.30: while a store pill was seen recently and never
        // pressed, the screen may be that video with its controls
        // hidden — do not sweep blind points into it.
        val pillGuard = cPillTaps == 0 && sawStorePillAt > 0 &&
            now() - sawStorePillAt < 90_000
        if (!meterMid && !bareCanvas && !pillGuard && now() - adStartAt >= finisherStart && now() - lastAnyTapAt > 10_000) {
            if (sweepCount < 6 && now() - lastSweepAt > sweepGapMs) {
                lastSweepAt = now()
                finisherActive = true
                // v2.21: the storegate pill position is a candidate:
                // some networks draw the whole video UI without
                // exposing the pill's text, so pattern H can never
                // see it; the position is fixed, so try it blind.
                // v2.22: candidate 2 is a REVEAL tap. The user
                // reports some videos show NO controls at all until
                // the screen is tapped once, and the controls then
                // auto-hide again within a few seconds (possibly
                // anti-bot). So the reveal tap is immediately
                // followed (1.2s) by the storegate tap while the
                // pill is visible; if the reveal tap itself counts
                // as the video's click-through, the store flows
                // handle whatever opens.
                // v2.28: candidate 2 is the Meta pod corner
                // (92.2%, 10.3%). A pod whose texts are ALL hidden
                // (the 2026-11-14 01:19 Ad 2 of 2: no counter, no
                // marker, no install text) reaches ONLY this
                // sweep — the corner escalation never runs without
                // an end-card text — and up to v2.27 the sweep
                // never tried that corner at all.
                val pts = arrayOf(
                    0.927 to 0.064, 0.922 to 0.103, 0.50 to 0.45,
                    0.158 to 0.062, 0.898 to 0.064, 0.942 to 0.061
                )
                val p = pts[sweepCount]
                sweepCount++
                sweepGapMs = if (sweepCount == 3) 1_200 else 6_000
                when (sweepCount) {
                    2 -> tap(p.first, p.second, "最終手段：右上の×候補（Meta位置）")
                    3 -> tap(p.first, p.second, "最終手段：画面をタップ（ボタンを表示）")
                    4 -> tap(p.first, p.second, "最終手段：左上の「ストアを開く」候補")
                    else -> tap(p.first, p.second, "最終手段：右上の×候補$sweepCount")
                }
            } else if (sweepCount >= 6 && now() - lastSweepAt > 8000) {
                endSession("最終手段の×でも閉じられませんでした（手動で閉じてください）")
                return
            }
        }

        // In the game with no ad signals: the ad is over. Sessions
        // that tapped follow the usual 4s rule; sessions that never
        // tapped (or are mid-finisher) get 30s, because "no signals"
        // for them usually means an ad no pattern recognised, not
        // a closed ad (the lamp-creative incident, 2026-11-13).
        if (noAdSignalSince == 0L) noAdSignalSince = now()
        val deathMs = if (bareCanvas || (sessionTapped && !finisherActive && !funnelSeen)) 4_000 else 30_000
        if (now() - noAdSignalSince > deathMs) {
            if (!sessionTapped || funnelSeen) {
                // Leave evidence: what the unrecognised screen
                // actually exposed, for the next diagnosis.
                val texts = snap.texts.take(8).joinToString(" / ") { it.take(20) }
                LogStore.add(this, "診断：無信号で終了した画面のテキスト=[$texts]")
            }
            endSession("広告が閉じたと判断して待機に戻ります")
        }
    }

    private fun handleStore(snap: Snap) {
        val playHeader = snap.boundsOf("Google Play")
        val isSheet = playHeader != null &&
            (playHeader.centerY().toDouble() / screenH()) in 0.15..0.32
        if (isSheet) {
            if (now() - lastStoreActionAt < 2500) return
            if (storeGaveUp) return
            if (storeCloseCount >= 3) {
                // Episode escalation (v2.16): one Back press, then a
                // 30s backoff and a fresh episode, instead of the old
                // permanent stop. The sheet may simply not have been
                // ready for the first taps.
                if (!storeBackUsed) {
                    storeBackUsed = true
                    lastStoreActionAt = now()
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    LogStore.add(this, "ストアのシート：×で閉じないため「戻る」で閉じます")
                    return
                }
                if (storeRearmAt == 0L) {
                    storeRearmAt = now() + 30_000
                    LogStore.add(this, "ストアのシートが閉じません。30秒後に再試行します")
                } else if (now() >= storeRearmAt) {
                    if (storeEpisodes < 3) {
                        storeEpisodes++
                        storeCloseCount = 0
                        storeBackUsed = false
                        storeRearmAt = 0L
                        LogStore.add(this, "ストアのシート：再試行を開始します")
                    } else {
                        storeGaveUp = true
                        LogStore.add(this, "ストアのシートを自動で閉じられませんでした（手動で閉じてください）")
                    }
                }
                return
            }
            lastStoreActionAt = now()
            storeCloseCount++
            tap(0.932, 0.233, "ストアのシートの×")
        } else {
            // Full-screen Play app (pattern C): bring the game back.
            if (returnBlocked) return
            if (returningFromStore) {
                driveReturn(inRecents = false)
                return
            }
            if (!returnBudgetOk()) {
                // v2.24: no return budget (breaker suspended, cap
                // reached, or too soon after the last return): do
                // not start a return episode at all.
                returnBlocked = true
                LogStore.add(this, "自動復帰が停止中のため、ストアからの復帰を行いません")
                return
            }
            returningFromStore = true
            returnStep = 0
            returnStepAt = now()
            lastStoreActionAt = now()
            LogStore.add(this, "パターンC：ストア（全画面）から対象アプリへの復帰を開始")
            returnToApp()
        }
    }

    // ---- automatic-return circuit breaker (v2.19) ----
    // Incident (user log 2026-11-13 20:59): after a Meta end card,
    // the excursion return kept dragging the user back to the game
    // whenever they opened ANY other app (including Muse, to
    // report the problem, and this app's own settings) — each
    // return reset the episode, so the 3-try cap never bound, and
    // the user felt the phone was fighting them. Safety rules:
    //  - every return flow goes through returnToApp();
    //  - 3 returns inside 3 minutes suspend ALL automatic returns
    //    for 10 minutes;
    //  - a non-ad app staying foreground for 6s during a session
    //    ends the session (the user has taken over; see evaluate).
    private val returnTimes = mutableListOf<Long>()
    private var returnSuspendUntil = 0L
    private var returnSuspendLogged = false
    // v2.20: the v2.19 breaker still allowed a burst of 3 returns
    // in 19 seconds (user log 2026-11-13 21:16:05/:11/:18, each
    // triggered by the ad's own aura overlay re-foregrounding) —
    // from the user's seat that IS being fought. So: at most 2
    // returns per session, and at least 15s between returns.
    private var sessionReturns = 0
    private var lastReturnAt = 0L
    private var capLogged = false
    private var otherPkg = ""
    private var otherPkgSince = 0L

    private fun returnGateOpen(): Boolean {
        val t = now()
        returnTimes.removeAll { t - it > 180_000 }
        if (t < returnSuspendUntil) {
            if (!returnSuspendLogged) {
                returnSuspendLogged = true
                LogStore.add(this, "自動復帰を停止中です（ユーザーの操作を優先）")
            }
            return false
        }
        return true
    }

    private fun noteReturn() {
        val t = now()
        returnTimes.add(t)
        returnTimes.removeAll { t - it > 180_000 }
        if (returnTimes.size >= 3 && t >= returnSuspendUntil) {
            returnSuspendUntil = t + 600_000
            returnSuspendLogged = false
            LogStore.add(this, "短時間に自動復帰が繰り返されたため、10分間停止します")
        }
    }

    // v2.24: ONE budget for every return mechanism. The Recents
    // fallback in driveReturn used to bypass the breaker, the
    // per-session cap and the 15s gap entirely, which produced
    // the 2026-11-14 pattern-C loop (store -> Recents return ->
    // stage reset -> corner tap -> store, ~25 cycles).
    private fun returnBudgetOk(checkGap: Boolean = true): Boolean {
        if (!returnGateOpen()) return false
        if (sessionActive && sessionReturns >= 2) {
            if (!capLogged) {
                capLogged = true
                LogStore.add(this, "この広告では自動復帰の上限（2回）に達したため、復帰を停止します")
            }
            return false
        }
        if (checkGap && now() - lastReturnAt < 15_000) return false
        return true
    }

    private fun bookReturn() {
        lastReturnAt = now()
        if (sessionActive) sessionReturns++
        noteReturn()
    }

    private fun returnToApp(): Boolean {
        if (!returnBudgetOk()) return false
        val ok = launchAppIntent()
        if (ok) bookReturn()
        return ok
    }

    /** Bring the session's target app to the front via its launch intent. */
    private fun launchAppIntent(): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(sessionPkg)
            if (intent == null) {
                LogStore.add(this, "対象アプリの起動インテントが見つかりません")
                false
            } else {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                startActivity(intent)
                LogStore.add(this, "対象アプリを直接呼び戻しました（起動インテント）")
                true
            }
        } catch (e: Exception) {
            LogStore.add(this, "起動インテントに失敗しました：" + e.javaClass.simpleName)
            false
        }
    }

    /**
     * Timed, single-shot return steps. Called on the 700ms tick while the
     * foreground is the store or Recents and a return is in progress. Each
     * step fires at most once; if the game window appears, evaluate()
     * clears [returningFromStore] and the normal ad flow resumes.
     */
    // ---------------- farming mode (v0.8) ----------------
    // Colour-based screen handling for low-level stage farming, designed
    // with the user on 2026-11-12 from their screenshots. The game is
    // Unity-based and exposes almost no accessibility text, so these
    // screens are recognised by sampling screenshot colours instead:
    //  - ステータス強化 (3 cards + 無料リフレッシュ button): tap the
    //    strongest card by colour rank 赤>黄>紫>青>緑; ties prefer the
    //    middle card, then left, then right (the user's chosen rule).
    //  - ステータス強化 result (2 cards, no refresh button, shown right
    //    after the roulette): tap 「空白をタップして続ける」 at (50%, 68%).
    //  - ルーレット: tap the lime-green 「確認」 at (72%, 62%); never the
    //    orange AD リフレッシュ button next to it.
    //  - 勝利: tap the lime-green 「確認」 at (25%, 70%); never the blue
    //    AD 「2倍受け取る」 button.
    // Battle and stage-select screens are only watched, never tapped
    // (the user starts each run manually; stamina is limited). If the
    // same screen still needs action after 3 tries within a minute, the
    // mode switches itself off and says so in the log.
    private var farmBusy = false
    private var farmLastShotAt = 0L
    private var farmCooldownUntil = 0L
    private var farmSig = ""
    private var farmSigCount = 0
    private var farmSigWindowStart = 0L
    private var farmShotFailures = 0
    // Diagnostics (v0.9): the first on-device farm test (2026-11-12) did
    // nothing at all and logged nothing, so v0.9 reports which stage of
    // the pipeline is reached: gate heartbeat, screenshot success/failure
    // with error codes, and the sampled colour fractions.
    private var farmDiagGateAt = 0L
    private var farmDiagAt = 0L

    // Diagnostics repeat the same values for minutes while farming
    // idles; identical repeats flooded the 80-line log and wiped the
    // ad-handling evidence the user needed to send (2026-11-12).
    // Route diag lines through a dedupe: only changes are recorded.
    private var lastDiagText = ""

    private fun diagLog(text: String) {
        if (text == lastDiagText) return
        lastDiagText = text
        LogStore.add(this, text)
    }
    private var farmShotLogged = false
    private var farmExCount = 0

    private fun maybeFarmTick() {
        if (!Prefs.farm(this)) return
        val tg = now()
        if (tg - farmDiagGateAt > 10_000) {
            farmDiagGateAt = tg
            diagLog("周回診断：gate session=$sessionActive busy=$farmBusy sdk=${Build.VERSION.SDK_INT}")
        }
        if (sessionActive || farmBusy) return
        if (Build.VERSION.SDK_INT < 31) return // wrapHardwareBuffer needs API 31
        val t = now()
        if (t < farmCooldownUntil) return
        if (t - farmLastShotAt < 1100) return
        farmLastShotAt = t
        farmBusy = true
        val executor = java.util.concurrent.Executor { r -> handler.post(r) }
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val bmp = screenshotToBitmap(result)
                            if (bmp != null) {
                                farmShotFailures = 0
                                if (!farmShotLogged) {
                                    farmShotLogged = true
                                    LogStore.add(
                                        this@AdCloserService,
                                        "周回診断：画面取得OK (${bmp.width}x${bmp.height})"
                                    )
                                }
                                handleFarmBitmap(bmp)
                                bmp.recycle()
                            } else if (!farmShotLogged) {
                                farmShotLogged = true
                                LogStore.add(this@AdCloserService, "周回診断：画面の変換に失敗しました")
                            }
                        } catch (e: Exception) {
                            // One bad frame must not kill the watcher.
                        }
                        farmBusy = false
                    }

                    override fun onFailure(errorCode: Int) {
                        farmBusy = false
                        farmShotFailures++
                        if (farmShotFailures <= 5) {
                            LogStore.add(
                                this@AdCloserService,
                                "周回診断：画面取得に失敗 (code=$errorCode)"
                            )
                        }
                    }
                }
            )
        } catch (e: Exception) {
            farmBusy = false
            farmExCount++
            if (farmExCount <= 3) {
                LogStore.add(this, "周回診断：画面取得で例外 ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun screenshotToBitmap(result: ScreenshotResult): Bitmap? {
        val buffer = result.hardwareBuffer ?: return null
        try {
            val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            val copy = wrapped.copy(Bitmap.Config.ARGB_8888, false)
            wrapped.recycle()
            return copy
        } finally {
            buffer.close()
        }
    }

    private fun hsvOf(bmp: Bitmap, fx: Double, fy: Double): FloatArray {
        val x = (bmp.width * fx).toInt().coerceIn(0, bmp.width - 1)
        val y = (bmp.height * fy).toInt().coerceIn(0, bmp.height - 1)
        val c = bmp.getPixel(x, y)
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            android.graphics.Color.red(c),
            android.graphics.Color.green(c),
            android.graphics.Color.blue(c),
            hsv
        )
        return hsv
    }

    /** Share of sampled pixels in a fractional rect matching [pred]. */
    private fun fractionWhere(
        bmp: Bitmap,
        x0: Double, x1: Double, y0: Double, y1: Double,
        pred: (FloatArray) -> Boolean
    ): Double {
        var hit = 0
        var total = 0
        var fx = x0
        while (fx <= x1) {
            var fy = y0
            while (fy <= y1) {
                total++
                if (pred(hsvOf(bmp, fx, fy))) hit++
                fy += 0.012
            }
            fx += 0.012
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }

    /** Colour rank of a pixel: 5=赤 (strongest) .. 1=緑, 0 = not a rank colour. */
    private fun rankOfHsv(h: FloatArray): Int {
        if (h[1] < 0.42f || h[2] < 0.32f) return 0
        val hue = h[0]
        return when {
            hue < 14f || hue > 336f -> 5
            hue in 22f..58f -> 4
            hue in 250f..314f -> 3
            hue in 176f..224f -> 2
            hue in 100f..168f -> 1
            else -> 0
        }
    }

    /** Majority colour rank of one card body around centre [cx]; 0 if unclear. */
    /**
     * Bucketed average colour of a card's icon (v2.7), as a short
     * signature string. The icon circle sits at about (cx, 37.5%);
     * the inner box avoids the animated rim/sparkles, so the same
     * item reads the same signature frame after frame while a
     * different item (capsule, wing, swatter, bone…) does not.
     */
    private fun iconSig(bmp: Bitmap, cx: Double): String {
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var n = 0
        var fx = cx - 0.022
        while (fx <= cx + 0.022) {
            var fy = 0.355
            while (fy <= 0.395) {
                val c = bmp.getPixel(
                    (fx * bmp.width).toInt().coerceIn(0, bmp.width - 1),
                    (fy * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                )
                sr += android.graphics.Color.red(c)
                sg += android.graphics.Color.green(c)
                sb += android.graphics.Color.blue(c)
                n++
                fy += 0.008
            }
            fx += 0.008
        }
        if (n == 0) return "000"
        val r = (sr / n).toInt() shr 6
        val g = (sg / n).toInt() shr 6
        val b = (sb / n).toInt() shr 6
        return "$r$g$b"
    }

    private fun cardRank(bmp: Bitmap, cx: Double): Int = cardRankShare(bmp, cx).first

    /**
     * Like [cardRank] but also returns the winning colour's share of all
     * rank-coloured samples (0.0 when unclear). v1.1 uses the share to tell
     * the 3-card selection layout from the 2-card result layout: sampled
     * at the true layout's centres the cards are solid (high share);
     * sampled at the wrong layout's centres each strip mixes two cards or
     * card + background (low share).
     */
    private fun cardRankShare(bmp: Bitmap, cx: Double): Pair<Int, Double> {
        val counts = IntArray(6)
        var fx = cx - 0.145
        while (fx <= cx + 0.145) {
            var fy = 0.335
            while (fy <= 0.615) {
                val r = rankOfHsv(hsvOf(bmp, fx, fy))
                if (r > 0) counts[r]++
                fy += 0.014
            }
            fx += 0.014
        }
        var best = 0
        var bestCount = 0
        var total = 0
        for (r in 1..5) {
            total += counts[r]
            if (counts[r] > bestCount) {
                bestCount = counts[r]
                best = r
            }
        }
        if (total < 12) return Pair(0, 0.0)
        val share = bestCount.toDouble() / total
        return if (bestCount * 2 >= total) Pair(best, share) else Pair(0, share)
    }

    // v1.2: the guard counts actions per screen FINGERPRINT (for the
    // selection, the actual card ranks), and the fingerprint is cleared
    // whenever a frame matches no known screen. v1.0/v1.1 counted per
    // screen type within a minute, which stopped the mode after three
    // legitimate ステータス強化 picks in one run (the user's first
    // successful test, 2026-11-12): picks are separated by battle frames
    // and each new draw has different cards, so only a truly stuck
    // screen — same cards, no gap — can now reach the cap.
    //
    // v1.4: reaching the cap no longer switches the whole mode off. On
    // the victory screen (2026-11-12) the taps were logged but the game
    // did not advance, and the mode-level stop then forced the user to
    // re-enable farming by hand — twice. Now the cap only puts THAT
    // screen instance to sleep until it goes away; farming continues
    // for everything else, and 全停止 remains the real brake.
    private var farmFingerprint = ""
    private var farmSuppressed = ""
    // v2.14: slow retries after suppression. The roulette panel
    // stalled again in the user's 2026-11-13 log: three properly
    // formed double taps at the 確認 button (17:08:53–17:09:02,
    // no cancellations, panel not covering) did not advance it,
    // and the v1.4 sleep then parked the screen forever. These
    // panels appear to become responsive later than their buttons
    // render (reel / ejection animation), so giving up after ~10s
    // is too early: while the same screen persists, keep retrying
    // at a slow interval before sleeping for real. v2.14 shipped
    // 15s x 4; the user's next log showed even that window may be
    // short (and every press style so far ignored), so v2.15
    // widened it to 20s x 8 and alternates the press style.
    private var farmSlowAt = 0L
    private var farmSlowLeft = 0
    private var rouTapIdx = 0
    private var vicTapIdx = 0
    // v2.32: the roulette's own fast driver. A panel instance is
    // identified by continuous presence (a 15s absence starts a
    // new instance); rouStreak counts its attempts so far.
    private var rouStreak = 0
    private var lastRouTapAt = 0L
    private var lastRouSeenAt = 0L

    private fun farmAct(sig: String, fingerprint: String, action: () -> Unit) {
        val t = now()
        if (fingerprint == farmSuppressed) {
            if (farmSlowLeft > 0 && t - farmSlowAt >= 20_000) {
                farmSlowAt = t
                farmSlowLeft--
                LogStore.add(this, "周回：$sig はまだ同じ画面のため、ゆっくり再試行します（残り $farmSlowLeft 回）")
                action()
                farmCooldownUntil = t + 4500
            }
            return
        }
        if (fingerprint == farmFingerprint && t - farmSigWindowStart < 60_000) {
            farmSigCount++
        } else {
            farmSig = sig
            farmFingerprint = fingerprint
            farmSigCount = 1
            farmSigWindowStart = t
        }
        if (farmSigCount > 3) {
            farmSuppressed = fingerprint
            farmSlowAt = t
            farmSlowLeft = 8
            LogStore.add(this, "周回：この画面（$sig）は自動で進められなかったため、間隔を空けて再試行します")
            return
        }
        action()
        farmCooldownUntil = t + 4500
    }

    /**
     * v2.35: the MEASURED centre of the roulette's lime 確認
     * button in this screenshot. Scans the button's neighbourhood
     * (x 0.50–0.95, y 0.55–0.70) for the detection green and
     * returns the bounding-box centre as screen fractions; falls
     * back to the classic fixed point (0.712, 0.624) when too few
     * green pixels are found. The tap helpers log the fractions
     * they are given, so every attempt records the measured
     * position in the action log — no fixed-coordinate guessing.
     */
    private fun rouConfirmCentre(bmp: Bitmap): Pair<Double, Double> {
        var minFx = Double.MAX_VALUE
        var maxFx = Double.MIN_VALUE
        var minFy = Double.MAX_VALUE
        var maxFy = Double.MIN_VALUE
        var hits = 0
        var fx = 0.50
        while (fx <= 0.95) {
            var fy = 0.55
            while (fy <= 0.70) {
                val h = hsvOf(bmp, fx, fy)
                if (h[0] in 60f..106f && h[1] > 0.45f && h[2] > 0.55f) {
                    hits++
                    if (fx < minFx) minFx = fx
                    if (fx > maxFx) maxFx = fx
                    if (fy < minFy) minFy = fy
                    if (fy > maxFy) maxFy = fy
                }
                fy += 0.004
            }
            fx += 0.004
        }
        if (hits < 30) return 0.712 to 0.624
        return ((minFx + maxFx) / 2) to ((minFy + maxFy) / 2)
    }

    private fun handleFarmBitmap(bmp: Bitmap) {
        // ステータス強化 screens share the lime banner at the top.
        // v1.0 calibration from the user's on-device diagnostics
        // (2026-11-12): the banner read 0.48 against a 0.50 threshold and
        // the refresh button 0.47 against 0.45, while card ranks were
        // already correct ([4,4,5] for 黄/黄/赤). The banner strip is now
        // the banner's central band (the white title text and the ribbon
        // edges dilute a full-height strip), and both thresholds sit at
        // 0.35 — still far above the ~0.10 seen on battle screens and
        // the ~0.00 the result screen gives for the refresh button.
        val banner = fractionWhere(bmp, 0.25, 0.75, 0.174, 0.194) { h ->
            h[0] in 60f..106f && h[1] > 0.45f && h[2] > 0.55f
        }
        // v0.9 diagnostics: report sampled values every ~5s so a silent
        // no-match can be debugged from the user's log screenshot.
        val td = now()
        if (td - farmDiagAt > 5000) {
            farmDiagAt = td
            val ref = fractionWhere(bmp, 0.345, 0.655, 0.702, 0.760) { h ->
                h[0] in 183f..212f && h[1] > 0.45f && h[2] > 0.55f
            }
            val rk = if (banner > 0.2) {
                val cs = doubleArrayOf(0.172, 0.495, 0.825)
                IntArray(3) { cardRank(bmp, cs[it]) }.joinToString(",")
            } else "-"
            diagLog("周回診断：banner=${"%.2f".format(banner)} ref=${"%.2f".format(ref)} ranks=[$rk]")
            // (v1.1 decides between the 3-card and 2-card layouts by
            // colour share; see the banner branch below.)
        }
        if (banner > 0.35) {
            // v1.1: telling the 3-card selection from the 2-card result
            // screen by the 無料リフレッシュ button proved fragile
            // on-device (0.47 in one test, 0.14 in the next — the
            // floating panel can cover the button in screenshots).
            // Instead compare layouts by colour share: sampled at the
            // true layout's centres the strips are solid card colour
            // (high share); at the wrong layout's centres they mix two
            // cards or card + background (low share).
            val centers3 = doubleArrayOf(0.172, 0.495, 0.825)
            val centers2 = doubleArrayOf(0.335, 0.665)
            val info3 = Array(3) { cardRankShare(bmp, centers3[it]) }
            val info2 = Array(2) { cardRankShare(bmp, centers2[it]) }
            val ranks3 = IntArray(3) { info3[it].first }
            val share3 = if (ranks3.all { it > 0 }) info3.minOf { it.second } else 0.0
            val share2 = if (info2.all { it.first > 0 }) info2.minOf { it.second } else 0.0
            if (share3 > 0.0 && share3 >= share2) {
                val best = maxOf(ranks3[0], ranks3[1], ranks3[2])
                val order = intArrayOf(1, 0, 2) // tie: middle, left, right
                val pick = order.first { ranks3[it] == best }
                val names = arrayOf("", "緑", "青", "紫", "黄", "赤")
                // v2.7: the fingerprint gains an icon signature. The
                // user's 2026-11-12 log showed the guard suppressing a
                // selection screen after repeated (82%, 45%) taps —
                // with ranks alone, a stuck screen and a fresh screen
                // that rolled the same colours are indistinguishable.
                // The signature (bucketed average colour of each
                // card's icon) separates them: same items, same
                // signature; new items, new fingerprint, fresh count.
                val sig = centers3.joinToString("-") { iconSig(bmp, it) }
                farmAct("ステータス強化", "sel:" + ranks3.joinToString(",") + ":" + sig) {
                    LogStore.add(this, "周回：ステータス強化で「${names[best]}」を選択")
                    // Retry attempts shift the tap point (the victory
                    // branch's v1.2 lesson): stem centre, then the icon
                    // circle, then lower on the card.
                    val ys = doubleArrayOf(0.45, 0.375, 0.52)
                    val y = ys[minOf(farmSigCount - 1, 2)]
                    tap(centers3[pick], y, "周回：カード選択")
                }
            } else if (share2 > 0.0) {
                farmAct("リザルト", "res:${info2[0].first},${info2[1].first}") {
                    tapButton(0.50, 0.68, "周回：空白をタップして続ける")
                }
            }
            return
        }

        // 勝利 screen: red banner + green/blue button pair at y ~ 70%.
        val victoryBanner = fractionWhere(bmp, 0.28, 0.72, 0.272, 0.312) { h ->
            (h[0] < 12f || h[0] > 344f) && h[1] > 0.40f && h[2] > 0.38f
        }
        val vConfirm = fractionWhere(bmp, 0.095, 0.415, 0.677, 0.732) { h ->
            h[0] in 60f..106f && h[1] > 0.45f && h[2] > 0.55f
        }
        val vDouble = fractionWhere(bmp, 0.55, 0.91, 0.677, 0.732) { h ->
            h[0] in 183f..212f && h[1] > 0.40f && h[2] > 0.55f
        }
        if (victoryBanner > 0.35 && vConfirm > 0.5 && vDouble > 0.35) {
            // Retry with slightly shifted points inside the button: in
            // the v1.2 test the centre tap was logged three times while
            // the game stayed on this screen.
            val pts = arrayOf(
                doubleArrayOf(0.255, 0.705),
                doubleArrayOf(0.255, 0.688),
                doubleArrayOf(0.300, 0.705)
            )
            val p = pts[vicTapIdx % pts.size]
            val vidx = vicTapIdx
            vicTapIdx++
            farmAct("勝利", "vic") {
                if (vidx % 2 == 1) {
                    tapPress(p[0], p[1], "周回：勝利の確認")
                } else {
                    tapButton(p[0], p[1], "周回：勝利の確認")
                }
            }
            return
        }

        // If our own floating panel covers the roulette 確認 zone,
        // ask it to move BEFORE judging the screen (v2.11): the
        // screenshot includes the panel, so a covered button reads
        // as "not a roulette screen" and no tap is ever attempted —
        // the v2.7 tap guard never gets its chance. The user's
        // 2026-11-13 screenshot measured this exactly: lime share
        // 0.28 over the full button (panel on its right half) versus
        // 0.55 over the left half alone.
        val panelRectNow = Control.panelRect
        if (panelRectNow != null && now() - lastFarmNudgeAt > 5_000 &&
            panelRectNow.contains(
                (screenW() * 0.717).toInt(), (screenH() * 0.623).toInt()
            )
        ) {
            lastFarmNudgeAt = now()
            Control.blockedTapFx = 0.717f
            Control.blockedTapFy = 0.623f
            Control.panelNudgeAt = now()
            LogStore.add(this, "周回：パネルが「確認」ボタンに重なっていたため退避します")
        }

        // ルーレット: green 確認 + orange AD リフレッシュ pair at y ~ 62%.
        // v2.11: each button is ALSO sampled on a partial strip, so a
        // panel parked over one end cannot hide the whole button.
        val rConfirm = maxOf(
            fractionWhere(bmp, 0.545, 0.89, 0.595, 0.652) { h ->
                h[0] in 60f..106f && h[1] > 0.45f && h[2] > 0.55f
            },
            fractionWhere(bmp, 0.545, 0.73, 0.595, 0.652) { h ->
                h[0] in 60f..106f && h[1] > 0.45f && h[2] > 0.55f
            }
        )
        val rRefresh = maxOf(
            fractionWhere(bmp, 0.11, 0.46, 0.595, 0.652) { h ->
                h[0] in 15f..47f && h[1] > 0.5f && h[2] > 0.5f
            },
            fractionWhere(bmp, 0.27, 0.46, 0.595, 0.652) { h ->
                h[0] in 15f..47f && h[1] > 0.5f && h[2] > 0.5f
            }
        )
        if (rConfirm > 0.5 && rRefresh > 0.4) {
            // v2.6 (user correction 2026-11-12): NEVER tap the スキップ
            // checkbox. The v1.3 skip-first logic was wrong — what
            // advances this panel is the 確認 button below it. The
            // skip-tap loop plus clicker-caused gesture cancellations
            // stalled farming on this panel.
            //
            // v2.32 (user report 2026-11-14 02:10): the v2.14/v2.15
            // scheme — 3 quick tries, then 20s slow retries — still
            // left this panel standing, and the user explicitly
            // rejected the slow pace (「サクサク自動周回して貰いたい」).
            // The roulette therefore got its own fast driver,
            // bypassing farmAct's 3-per-minute cap.
            //
            // v2.34/v2.35 (user reports 2026-11-14 02:22 and
            // 03:00): the user reported that while the driver runs,
            // a PRESS REACTION shows on the スキップ checkbox —
            // the control that must never be pressed — and then
            // corrected Muse's "ejection animation" theory in
            // full: this panel does not rotate and has no such
            // animation; it is a static panel (it spins only when
            // the player watches an ad via the left リフレッシュ
            // button), and 確認 simply means "fine as-is". The
            // v2.34 settle-wait built on that theory was dropped
            // before it ever shipped. What v2.35 does instead:
            // no fixed coordinates at all — each attempt taps the
            // MEASURED centre of the lime button in the current
            // screenshot (rouConfirmCentre), rotating three plain
            // press styles (tap / double tap / 500ms press), every
            // ~4s, up to 15 attempts per panel instance; the tap
            // helpers log the fractions used, so the position is
            // verifiable from the log. As a hard guarantee,
            // panelBlocks() also refuses any point tap inside the
            // skip zone itself (see there). A panel instance is
            // identified by continuous presence: an absence of 15s
            // or more starts a new instance. Past 15 attempts the
            // old farmAct slow path remains as the backstop.
            val t = now()
            if (t - lastRouSeenAt > 15_000) {
                if (rouStreak >= 4) {
                    LogStore.add(this, "周回：前のルーレットは $rouStreak 回タップした時点で画面が変わりました")
                }
                rouStreak = 0
                lastRouTapAt = 0L
            }
            lastRouSeenAt = t
            val centre = rouConfirmCentre(bmp)
            if (rouStreak < 15) {
                if (t - lastRouTapAt >= 4_000) {
                    lastRouTapAt = t
                    rouStreak++
                    val idx = rouTapIdx
                    rouTapIdx++
                    when (idx % 3) {
                        0 -> tap(centre.first, centre.second, "周回：ルーレットの確認")
                        1 -> tapButton(centre.first, centre.second, "周回：ルーレットの確認")
                        else -> tapPressLong(centre.first, centre.second, "周回：ルーレットの確認")
                    }
                    farmCooldownUntil = t + 4500
                }
            } else {
                farmAct("ルーレット", "rou") {
                    val idx = rouTapIdx
                    rouTapIdx++
                    if (idx % 2 == 1) {
                        tapPress(centre.first, centre.second, "周回：ルーレットの確認")
                    } else {
                        tapButton(centre.first, centre.second, "周回：ルーレットの確認")
                    }
                }
            }
            return
        }

        // v2.0: keep the battle at double speed (user request
        // 2026-11-12). The enemy-speed icon at the top-left shows a
        // warm-coloured "x2" under its label while 2x is active; when
        // it is missing, tap the icon. Calibrated on the user's
        // screenshots: the icon's green share (0.20+) and the label's
        // warm share (0.21+) mark the in-battle HUD — the stage-select
        // screen reads ~0 on both, so it is never tapped — and the
        // strip under the label reads 0.00 without the x2 versus
        // 0.08+ with it (threshold 0.03).
        val iconGreen = fractionWhere(bmp, 0.03, 0.14, 0.185, 0.235) { h ->
            h[0] in 55f..115f && h[1] > 0.35f && h[2] > 0.25f
        }
        if (iconGreen > 0.10) {
            val labelWarm = fractionWhere(bmp, 0.005, 0.165, 0.228, 0.253) { h ->
                h[0] in 18f..58f && h[1] > 0.35f && h[2] > 0.20f
            }
            if (labelWarm > 0.12) {
                val x2Warm = fractionWhere(bmp, 0.05, 0.125, 0.24, 0.275) { h ->
                    h[0] in 18f..58f && h[1] > 0.35f && h[2] > 0.20f
                }
                // Stability, take three (v2.33). The v2.13 frame
                // count (3 accumulated "missing" frames, 20s tap
                // throttle) flip-flopped the speed in the user's
                // 2026-11-14 02:11–02:12 log: keeper taps at 02:11:48,
                // 02:12:12 and 02:12:38, each within seconds of a
                // battle (re)starting, while the user watched the
                // "x2" displayed under the label. The strip itself
                // is NOT mis-calibrated — offline it reads 0.088 on
                // the user's own x2-present battle screenshot versus
                // 0.000 on x2-absent ones — so the false "missing"
                // frames are the OPENING frames of each battle,
                // before the glyph renders / while the HUD fades
                // in, and with selections every few seconds those
                // opening frames are most of what the keeper ever
                // sees. Three defences, in order:
                //  1. Grace: "missing" frames count only after the
                //     battle HUD has been CONTINUOUSLY present for
                //     4s (a >3s gap between confirmed battle frames
                //     restarts the stretch, so selections and panel
                //     transitions always re-arm the grace).
                //  2. Volume: 5 counted frames are required.
                //  3. Disarm: after a keeper tap, no further tap
                //     until some frame positively SEES the x2
                //     again (a correct tap makes it appear; a
                //     mis-tap can no longer be compounded), and
                //     taps are at least 90s apart regardless.
                // A frame that sees the x2 still resets the count
                // (and re-arms), and a clearly-not-battle frame
                // still resets it (below).
                // Take four (v2.37): take three starved exactly
                // like v2.11 did — with card picks every 5–13s
                // and a 4.5s screenshot cooldown after each pick,
                // battle frames arrive in bursts separated by
                // >3s gaps, so the "continuous HUD" stretch
                // restarted on every burst and the keeper never
                // fired at all (user report 2026-11-14 18:13;
                // detection re-measured fine on that day's
                // screenshots: icon 0.28, label 0.26, strip 0.000
                // absent / 0.096 present). Accumulate "missing"
                // frames across bursts again (the speed setting
                // persists across the selection overlay), but
                // require >=4 counted frames SPANNING >=4s, so a
                // momentary mis-read cannot fire a tap. After a
                // tap, a 10s verify window pauses counting; if
                // the x2 is still missing afterwards, counting
                // resumes and ONE more tap is allowed (>=12s
                // after the previous tap, at most 2 per missing
                // episode). No permanent disarm: a mis-tap can be
                // corrected by the second tap instead of latching
                // the keeper off until the x2 happens to be seen
                // again. Every counted frame is diag-logged with
                // its readings, so a future mis-fire is
                // diagnosable from the log alone.
                val t = now()
                if (x2Warm >= 0.03) {
                    x2MissingFrames = 0
                    x2FirstMissingAt = 0L
                    x2EpisodeTaps = 0
                } else if (t >= x2VerifyUntil && x2EpisodeTaps < 2) {
                    if (x2MissingFrames == 0) x2FirstMissingAt = t
                    x2MissingFrames++
                    diagLog(
                        "周回診断：倍速 icon=${"%.2f".format(iconGreen)} " +
                            "label=${"%.2f".format(labelWarm)} " +
                            "strip=${"%.2f".format(x2Warm)} " +
                            "missing=${x2MissingFrames}"
                    )
                    if (x2MissingFrames >= 4 &&
                        t - x2FirstMissingAt >= 4_000 &&
                        t - lastSpeedTapAt >= 12_000
                    ) {
                        lastSpeedTapAt = t
                        x2EpisodeTaps++
                        x2MissingFrames = 0
                        x2FirstMissingAt = 0L
                        x2VerifyUntil = t + 10_000
                        tap(0.082, 0.21, "周回：敵の移動速度アイコン（×2化）")
                    }
                }
            }
        } else if (iconGreen <= 0.02) {
            // Clearly not the battle HUD (stage select, loading):
            // forget any accumulated "x2 missing" evidence.
            x2MissingFrames = 0
            x2FirstMissingAt = 0L
        }

        // No known screen in this frame: the previous screen is gone, so
        // forget its fingerprint (v1.2 guard reset) and any suppression.
        farmFingerprint = ""
        farmSigCount = 0
        farmSuppressed = ""
        farmSlowLeft = 0
    }

    private fun driveReturn(inRecents: Boolean) {
        val waited = now() - returnStepAt
        when (returnStep) {
            0 -> if (waited > 1800) {
                if (!returnBudgetOk(checkGap = false)) {
                    // v2.24: the Recents fallback is a return too
                    // and must obey the breaker and the per-session
                    // cap. Ungated, it looped store<->game ~25
                    // times while the breaker was suspended
                    // (2026-11-14). The 15s gap is not applied
                    // here: within one episode this fallback is a
                    // one-shot, and episode repetition is what the
                    // cap bounds.
                    returningFromStore = false
                    returnBlocked = true
                    LogStore.add(this, "自動復帰が停止中のため、ストアからの復帰を中止します")
                    return
                }
                // Launch intent did not bring the game forward: open
                // Recents once as a fallback.
                returnStep = 1
                returnStepAt = now()
                recentsArmed = true
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                LogStore.add(this, "復帰の予備手段：履歴（タブ）を開きます")
            }
            1 -> if (waited > 1300) {
                if (inRecents) {
                    // One UI centres the most recent task, which is the
                    // game (the store was opened from it). Tap the card
                    // body once; v0.1 tapped the app-name label instead,
                    // which One UI ignores. Never fire this tap while the
                    // store is still in front.
                    returnStep = 2
                    returnStepAt = now()
                    tap(0.50, 0.45, "履歴の中央のカード")
                } else if (waited > 3500) {
                    // Recents never came to the front; skip the card tap
                    // and retry the launch intent instead.
                    returnStep = 3
                    returnStepAt = now()
                    returnToApp()
                }
            }
            2 -> if (waited > 2000) {
                returnStep = 3
                returnStepAt = now()
                returnToApp()
            }
            3 -> if (waited > 2500) {
                returningFromStore = false
                returnBlocked = true
                recentsArmed = false
                LogStore.add(
                    this,
                    "自動で対象アプリに戻れませんでした。履歴から元のアプリを選んでください（監視は続けます）"
                )
            }
        }
    }
}

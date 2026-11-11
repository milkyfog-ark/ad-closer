package jp.milkyfog.adcloser

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
    private var lastTopLeftTapAt = 0L
    private var topLeftStage = 0 // 0 = none, 1 = tried y=6%, 2 = tried y=3%, 3 = retried y=6%
    private var storeCloseCount = 0
    private var storeExcursions = 0
    private var noAdSignalSince = 0L
    private var lastStoreActionAt = 0L
    private var recentsInProgress = false
    private var recentsStage = 0
    private var recentsStageAt = 0L
    private var recentsAttempts = 0
    private var lastEventClass = ""

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
        if (pkg == GAME_PKG && Control.manualArmAt == 0L) {
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
        val boundsByText: Map<String, Rect>
    ) {
        fun has(text: String): Boolean = texts.any { it.contains(text) }
        fun boundsOf(text: String): Rect? =
            boundsByText.entries.firstOrNull { it.key.contains(text) }?.value
    }

    private fun snapshot(root: AccessibilityNodeInfo): Snap {
        val texts = ArrayList<String>()
        val bounds = HashMap<String, Rect>()
        var budget = 500
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
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return Snap(texts, bounds)
    }

    // ---------------- helpers ----------------

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun screenW(): Int = resources.displayMetrics.widthPixels
    private fun screenH(): Int = resources.displayMetrics.heightPixels

    private fun tap(xFraction: Double, yFraction: Double, why: String) {
        val x = (screenW() * xFraction).toFloat()
        val y = (screenH() * yFraction).toFloat()
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 70))
            .build()
        dispatchGesture(gesture, null, null)
        LogStore.add(this, "$why (${(xFraction * 100).toInt()}%, ${(yFraction * 100).toInt()}%) をタップ")
    }

    private fun tapRect(r: Rect, why: String) {
        val path = Path().apply { moveTo(r.centerX().toFloat(), r.centerY().toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 70))
            .build()
        dispatchGesture(gesture, null, null)
        LogStore.add(this, "$why をタップ")
    }

    private fun startSession(at: Long, why: String) {
        sessionActive = true
        adStartAt = at
        lastATapAt = 0L
        lastTopLeftTapAt = 0L
        topLeftStage = 0
        storeCloseCount = 0
        storeExcursions = 0
        noAdSignalSince = 0L
        recentsInProgress = false
        recentsAttempts = 0
        LogStore.add(this, "$why：広告の監視を開始")
    }

    private fun endSession(note: String) {
        if (sessionActive) LogStore.add(this, note)
        sessionActive = false
        recentsInProgress = false
        noAdSignalSince = 0L
    }

    // ---------------- main evaluation ----------------

    private fun evaluate() {
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
        val snap = snapshot(root)

        when {
            pkg == GAME_PKG -> {
                if (recentsInProgress) {
                    // We made it back to the game from the store.
                    recentsInProgress = false
                    storeExcursions++
                    topLeftStage = 0
                    noAdSignalSince = 0L
                    LogStore.add(this, "ゲームに戻りました。×の出現を待ちます")
                }
                handleGame(snap)
            }
            pkg == STORE_PKG -> {
                if (sessionActive && Prefs.storeRecovery(this)) handleStore(snap)
            }
            pkg in RECENTS_PKGS -> {
                if (recentsInProgress) handleRecents(snap)
            }
        }
    }

    private fun handleGame(snap: Snap) {
        val rewardReady = snap.has("報酬を獲得しました")
        val endCard = snap.has("インストール") || snap.has("Google Play")

        if (!sessionActive) {
            if (Prefs.resident(this) && (rewardReady || endCard)) {
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

        if (rewardReady) {
            noAdSignalSince = 0L
            if (Prefs.patternA(this) && now() - lastATapAt > 5000) {
                lastATapAt = now()
                tap(0.942, 0.061, "パターンA：報酬を獲得しました の×")
            }
            return
        }

        if (endCard) {
            noAdSignalSince = 0L
            if (Prefs.patternB(this)) {
                val elapsedSec = (now() - adStartAt) / 1000
                if (elapsedSec >= Prefs.guardSeconds(this)) {
                    if (topLeftStage == 0 && now() - lastTopLeftTapAt > 3000) {
                        lastTopLeftTapAt = now()
                        topLeftStage = 1
                        tap(0.058, 0.062, "パターンB/C：左上の候補1")
                    } else if (topLeftStage == 1 && now() - lastTopLeftTapAt > 4000) {
                        lastTopLeftTapAt = now()
                        topLeftStage = 2
                        tap(0.058, 0.028, "パターンB：左上の候補2")
                    } else if (topLeftStage == 2 && now() - lastTopLeftTapAt > 5000) {
                        // The x that appears after coming back from full-screen
                        // Play was never confirmed to sit at y=3%: retry the
                        // y=6% spot once before giving up.
                        lastTopLeftTapAt = now()
                        topLeftStage = 3
                        tap(0.058, 0.062, "パターンB/C：左上の候補1を再試行")
                    } else if (topLeftStage == 3 && now() - lastTopLeftTapAt > 8000) {
                        // None of the candidates closed the ad: stop so the
                        // app never keeps tapping, and let the user close it.
                        endSession("左上の×を押せませんでした（手動で閉じてください）")
                    }
                }
            }
            return
        }

        // In the game with no ad signals: the ad is over.
        if (noAdSignalSince == 0L) noAdSignalSince = now()
        if (now() - noAdSignalSince > 4000) {
            endSession("広告が閉じたと判断して待機に戻ります")
        }
    }

    private fun handleStore(snap: Snap) {
        if (now() - lastStoreActionAt < 2500) return
        val playHeader = snap.boundsOf("Google Play")
        val isSheet = playHeader != null &&
            (playHeader.centerY().toDouble() / screenH()) in 0.15..0.32
        if (isSheet) {
            if (storeCloseCount >= 3) {
                endSession("ストアが何度も開くため停止しました（手動で閉じてください）")
                return
            }
            lastStoreActionAt = now()
            storeCloseCount++
            tap(0.932, 0.233, "ストアのシートの×")
        } else {
            // Full-screen Play app (pattern C): go back via Recents.
            if (recentsAttempts >= 2) {
                endSession("タブ操作での復帰に失敗したため停止しました")
                return
            }
            recentsAttempts++
            recentsInProgress = true
            recentsStage = 0
            recentsStageAt = now()
            lastStoreActionAt = now()
            performGlobalAction(GLOBAL_ACTION_RECENTS)
            LogStore.add(this, "パターンC：履歴（タブ）を開いてゲームに戻ります")
        }
    }

    private fun handleRecents(snap: Snap) {
        // Preferred: tap the game's card, found by its label.
        val gameLabel = snap.boundsByText.entries.firstOrNull {
            it.key.contains("セルサバイバー") || it.key.contains("Cell Survivor")
        }
        if (gameLabel != null) {
            tapRect(gameLabel.value, "履歴の中のセルサバイバー")
            recentsStageAt = now()
            return
        }
        val waited = now() - recentsStageAt
        if (recentsStage == 0 && waited > 1600) {
            // One UI shows the previous app peeking from the side; tap it
            // once to centre it.
            recentsStage = 1
            recentsStageAt = now()
            tap(0.12, 0.45, "履歴の横のカード")
        } else if (recentsStage == 1 && waited > 1300) {
            recentsStage = 2
            recentsStageAt = now()
            tap(0.50, 0.45, "履歴の中央のカード")
        } else if (recentsStage == 2 && waited > 3000) {
            recentsInProgress = false
            LogStore.add(this, "履歴からゲームのカードを見つけられませんでした")
        }
    }
}

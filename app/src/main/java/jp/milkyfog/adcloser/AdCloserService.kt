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
    private var lastTopLeftTapAt = 0L
    private var topLeftStage = 0 // 0 = none, 1 = tried y=6%, 2 = tried y=3%, 3 = retried y=6%
    private var storeCloseCount = 0
    private var storeExcursions = 0
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
        aTapCount = 0
        lastCloseTapAt = 0L
        closeTapCount = 0
        lastTopLeftTapAt = 0L
        topLeftStage = 0
        storeCloseCount = 0
        storeExcursions = 0
        noAdSignalSince = 0L
        returningFromStore = false
        returnStep = 0
        returnBlocked = false
        LogStore.add(this, "$why：広告の監視を開始")
    }

    private fun endSession(note: String) {
        if (sessionActive) LogStore.add(this, note)
        sessionActive = false
        returningFromStore = false
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
                if (returningFromStore) {
                    // We made it back to the game from the store.
                    returningFromStore = false
                    storeExcursions++
                    topLeftStage = 0
                    noAdSignalSince = 0L
                    LogStore.add(this, "ゲームに戻りました。×の出現を待ちます")
                }
                handleGame(snap)
                maybeFarmTick()
            }
            pkg == STORE_PKG -> {
                if (sessionActive && Prefs.storeRecovery(this)) handleStore(snap)
            }
            pkg in RECENTS_PKGS -> {
                if (returningFromStore) driveReturn(inRecents = true)
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
            snap.has("Install")
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

        if (!sessionActive) {
            if (Prefs.resident(this) && (rewardReady || endCard || closeInCorner)) {
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

        if (closeInCorner && Prefs.patternB(this)) {
            noAdSignalSince = 0L
            if (closeTapCount < 3 && now() - lastCloseTapAt > 3500) {
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
        val playHeader = snap.boundsOf("Google Play")
        val isSheet = playHeader != null &&
            (playHeader.centerY().toDouble() / screenH()) in 0.15..0.32
        if (isSheet) {
            if (now() - lastStoreActionAt < 2500) return
            if (storeCloseCount >= 3) {
                endSession("ストアが何度も開くため停止しました（手動で閉じてください）")
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
            returningFromStore = true
            returnStep = 0
            returnStepAt = now()
            lastStoreActionAt = now()
            LogStore.add(this, "パターンC：ストア（全画面）からゲームへの復帰を開始")
            launchGameIntent()
        }
    }

    /** Bring Cell Survivor to the front via its launch intent. */
    private fun launchGameIntent(): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(GAME_PKG)
            if (intent == null) {
                LogStore.add(this, "ゲームの起動インテントが見つかりません")
                false
            } else {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                startActivity(intent)
                LogStore.add(this, "ゲームを直接呼び戻しました（起動インテント）")
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

    private fun maybeFarmTick() {
        if (!Prefs.farm(this) || sessionActive || farmBusy) return
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
                                handleFarmBitmap(bmp)
                                bmp.recycle()
                            }
                        } catch (e: Exception) {
                            // One bad frame must not kill the watcher.
                        }
                        farmBusy = false
                    }

                    override fun onFailure(errorCode: Int) {
                        farmBusy = false
                        farmShotFailures++
                        if (farmShotFailures == 5) {
                            LogStore.add(
                                this@AdCloserService,
                                "周回モード：画面の色を取得できませんでした（この画面では判定できない可能性があります）"
                            )
                        }
                    }
                }
            )
        } catch (e: Exception) {
            farmBusy = false
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
    private fun cardRank(bmp: Bitmap, cx: Double): Int {
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
        if (total < 12) return 0
        return if (bestCount * 2 >= total) best else 0
    }

    private fun farmAct(sig: String, action: () -> Unit) {
        val t = now()
        if (sig == farmSig && t - farmSigWindowStart < 60_000) {
            farmSigCount++
        } else {
            farmSig = sig
            farmSigCount = 1
            farmSigWindowStart = t
        }
        if (farmSigCount > 3) {
            Prefs.setFarm(this, false)
            LogStore.add(this, "周回モード：同じ画面（$sig）が続くため自動停止しました")
            farmSig = ""
            farmSigCount = 0
            return
        }
        action()
        farmCooldownUntil = t + 3000
    }

    private fun handleFarmBitmap(bmp: Bitmap) {
        // ステータス強化 screens share the lime banner at the top.
        val banner = fractionWhere(bmp, 0.25, 0.75, 0.172, 0.202) { h ->
            h[0] in 60f..106f && h[1] > 0.45f && h[2] > 0.55f
        }
        if (banner > 0.5) {
            // The 3-card selection has a blue 無料リフレッシュ button;
            // the 2-card result screen after the roulette does not.
            val refresh = fractionWhere(bmp, 0.345, 0.655, 0.702, 0.760) { h ->
                h[0] in 183f..212f && h[1] > 0.45f && h[2] > 0.55f
            }
            if (refresh > 0.45) {
                val centers = doubleArrayOf(0.172, 0.495, 0.825)
                val ranks = IntArray(3) { cardRank(bmp, centers[it]) }
                if (ranks.all { it > 0 }) {
                    val best = maxOf(ranks[0], ranks[1], ranks[2])
                    val order = intArrayOf(1, 0, 2) // tie: middle, left, right
                    val pick = order.first { ranks[it] == best }
                    val names = arrayOf("", "緑", "青", "紫", "黄", "赤")
                    farmAct("ステータス強化") {
                        LogStore.add(this, "周回：ステータス強化で「${names[best]}」を選択")
                        tap(centers[pick], 0.45, "周回：カード選択")
                    }
                }
            } else {
                val r1 = cardRank(bmp, 0.335)
                val r2 = cardRank(bmp, 0.665)
                if (r1 > 0 && r2 > 0) {
                    farmAct("リザルト") {
                        tap(0.50, 0.68, "周回：空白をタップして続ける")
                    }
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
            farmAct("勝利") { tap(0.255, 0.705, "周回：勝利の確認") }
            return
        }

        // ルーレット: green 確認 + orange AD リフレッシュ pair at y ~ 62%.
        val rConfirm = fractionWhere(bmp, 0.545, 0.89, 0.595, 0.652) { h ->
            h[0] in 60f..106f && h[1] > 0.45f && h[2] > 0.55f
        }
        val rRefresh = fractionWhere(bmp, 0.11, 0.46, 0.595, 0.652) { h ->
            h[0] in 15f..47f && h[1] > 0.5f && h[2] > 0.5f
        }
        if (rConfirm > 0.5 && rRefresh > 0.4) {
            farmAct("ルーレット") { tap(0.717, 0.623, "周回：ルーレットの確認") }
            return
        }
    }

    private fun driveReturn(inRecents: Boolean) {
        val waited = now() - returnStepAt
        when (returnStep) {
            0 -> if (waited > 1800) {
                // Launch intent did not bring the game forward: open
                // Recents once as a fallback.
                returnStep = 1
                returnStepAt = now()
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
                    launchGameIntent()
                }
            }
            2 -> if (waited > 2000) {
                returnStep = 3
                returnStepAt = now()
                launchGameIntent()
            }
            3 -> if (waited > 2500) {
                returningFromStore = false
                returnBlocked = true
                LogStore.add(
                    this,
                    "自動でゲームに戻れませんでした。履歴からセルサバイバーを選んでください（監視は続けます）"
                )
            }
        }
    }
}

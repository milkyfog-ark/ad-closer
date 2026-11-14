package jp.milkyfog.adcloser

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var guardLabel: TextView
    private lateinit var logText: TextView
    private lateinit var targetsText: TextView
    private lateinit var btnAddLast: Button
    private lateinit var seekClicker: SeekBar
    private lateinit var clickerLabel: TextView
    private lateinit var jitterLabel: TextView
    private lateinit var limitLabel: TextView
    private lateinit var pressLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        guardLabel = findViewById(R.id.guardLabel)
        logText = findViewById(R.id.logText)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "「広告クローザー」を探してオンにしてください", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnOverlayPerm).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
        findViewById<Button>(R.id.btnOverlayToggle).setOnClickListener {
            if (OverlayService.isRunning) {
                stopService(Intent(this, OverlayService::class.java))
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "先に②の許可をオンにしてください", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                startService(Intent(this, OverlayService::class.java))
            }
            refreshStatus()
        }

        targetsText = findViewById(R.id.targetsText)
        btnAddLast = findViewById(R.id.btnAddLast)
        findViewById<Button>(R.id.btnPickTargets).setOnClickListener { showTargetPicker() }
        btnAddLast.setOnClickListener { addLastForeign() }

        bindSwitch(R.id.swMaster, { Prefs.master(this) }, { Prefs.setMaster(this, it) })
        bindSwitch(R.id.swResident, { Prefs.resident(this) }, { Prefs.setResident(this, it) })
        bindSwitch(R.id.swPatternA, { Prefs.patternA(this) }, { Prefs.setPatternA(this, it) })
        bindSwitch(R.id.swPatternB, { Prefs.patternB(this) }, { Prefs.setPatternB(this, it) })
        bindSwitch(R.id.swStore, { Prefs.storeRecovery(this) }, { Prefs.setStoreRecovery(this, it) })
        bindSwitch(R.id.swFarm, { Prefs.farm(this) }, { Prefs.setFarm(this, it) })
        bindSwitch(R.id.swPanelAd, { Prefs.panelShowAd(this) }, { Prefs.setPanelShowAd(this, it) })
        bindSwitch(R.id.swPanelFarm, { Prefs.panelShowFarm(this) }, { Prefs.setPanelShowFarm(this, it) })
        bindSwitch(R.id.swPanelClicker, { Prefs.panelShowClicker(this) }, { Prefs.setPanelShowClicker(this, it) })
        bindSwitch(R.id.swPanelIcons, { Prefs.panelIconMode(this) }, { Prefs.setPanelIconMode(this, it) })
        bindSwitch(R.id.swClickerHuman, { Prefs.clickerHumanTap(this) }, { Prefs.setClickerHumanTap(this, it) })
        bindSwitch(R.id.swClickerHide, { Prefs.clickerHideOverlay(this) }, { Prefs.setClickerHideOverlay(this, it) })
        bindSwitch(R.id.swClickerShizuku, { Prefs.clickerShizuku(this) }, { v ->
            Prefs.setClickerShizuku(this, v)
            if (v) ShizukuTap.ensureBound(this)
        })
        findViewById<Button>(R.id.btnShizuku).setOnClickListener {
            try {
                when {
                    !rikka.shizuku.Shizuku.pingBinder() ->
                        Toast.makeText(
                            this,
                            "Shizukuが起動していません。Shizukuアプリで起動してから押してください",
                            Toast.LENGTH_LONG
                        ).show()
                    rikka.shizuku.Shizuku.checkSelfPermission() ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED ->
                        Toast.makeText(this, "すでに権限があります", Toast.LENGTH_SHORT).show()
                    else -> rikka.shizuku.Shizuku.requestPermission(1001)
                }
            } catch (t: Throwable) {
                Toast.makeText(this, "Shizukuに接続できませんでした", Toast.LENGTH_LONG).show()
            }
            refreshShizukuStatus()
        }
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener { _, _ ->
                refreshShizukuStatus()
            }
        } catch (t: Throwable) {
            // Shizuku unavailable; the status line explains it.
        }
        refreshShizukuStatus()

        val seek = findViewById<SeekBar>(R.id.seekGuard)
        seek.progress = Prefs.guardSeconds(this) - 10
        updateGuardLabel()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                Prefs.setGuardSeconds(this@MainActivity, progress + 10)
                updateGuardLabel()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ---- auto-clicker settings (v2.1) ----
        clickerLabel = findViewById(R.id.clickerLabel)
        jitterLabel = findViewById(R.id.jitterLabel)
        limitLabel = findViewById(R.id.limitLabel)
        seekClicker = findViewById(R.id.seekClicker)
        val swMode = findViewById<Switch>(R.id.swClickerMode)
        swMode.isChecked = Prefs.clickerUseCps(this)
        swMode.setOnCheckedChangeListener { _, checked ->
            Prefs.setClickerUseCps(this, checked)
            configureClickerSeek()
        }
        seekClicker.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (Prefs.clickerUseCps(this@MainActivity)) {
                    Prefs.setClickerCps(this@MainActivity, progress + 1)
                } else {
                    Prefs.setClickerIntervalMs(this@MainActivity, 50 + progress * 50)
                }
                updateClickerLabel()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        configureClickerSeek()

        val seekJitter = findViewById<SeekBar>(R.id.seekJitter)
        seekJitter.progress = Prefs.clickerJitterPct(this)
        updateJitterLabel()
        seekJitter.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                Prefs.setClickerJitterPct(this@MainActivity, progress)
                updateJitterLabel()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val seekLimit = findViewById<SeekBar>(R.id.seekLimit)
        seekLimit.progress = Prefs.clickerLimitMinutes(this)
        updateLimitLabel()
        seekLimit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                Prefs.setClickerLimitMinutes(this@MainActivity, progress)
                updateLimitLabel()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // v2.44: press-duration seekbar (10–500ms in 10ms steps).
        pressLabel = findViewById(R.id.pressLabel)
        val seekPress = findViewById<SeekBar>(R.id.seekPress)
        seekPress.progress = ((Prefs.clickerPressMs(this) - 10) / 10).coerceIn(0, 49)
        updatePressLabel()
        seekPress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                Prefs.setClickerPressMs(this@MainActivity, 10 + progress * 10)
                updatePressLabel()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnRefreshLog).setOnClickListener { refreshLog() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            LogStore.clear(this)
            refreshLog()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshLog()
        refreshShizukuStatus()
    }

    /** v2.55: Shizuku connection state, in plain words. */
    private fun refreshShizukuStatus() {
        val tv = findViewById<TextView>(R.id.shizukuStatus) ?: return
        val installed = try {
            packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (t: Throwable) {
            false
        }
        tv.text = when {
            !installed ->
                "Shizuku状態：アプリがインストールされていません（Play ストアで「Shizuku」を入れてください）"
            else -> try {
                when {
                    !rikka.shizuku.Shizuku.pingBinder() ->
                        "Shizuku状態：インストール済みですが起動していません（Shizukuアプリで「ワイヤレスデバッグ経由で起動」してください）"
                    rikka.shizuku.Shizuku.checkSelfPermission() ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED ->
                        "Shizuku状態：使用できます（連打設定で「Shizuku方式でタップ」をONにしてください）"
                    else ->
                        "Shizuku状態：起動しています。下のボタンで権限を許可してください"
                }
            } catch (t: Throwable) {
                "Shizuku状態：確認できませんでした"
            }
        }
    }

    private fun bindSwitch(id: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = get()
        sw.setOnCheckedChangeListener { _, checked -> set(checked) }
    }

    private fun updateGuardLabel() {
        guardLabel.text = "左上パターンの待ち時間：${Prefs.guardSeconds(this)}秒（広告開始からこの秒数が過ぎるまで左上は押しません）"
    }

    private fun configureClickerSeek() {
        if (Prefs.clickerUseCps(this)) {
            seekClicker.max = 19
            seekClicker.progress = Prefs.clickerCps(this).coerceIn(1, 20) - 1
        } else {
            seekClicker.max = 59
            seekClicker.progress = ((Prefs.clickerIntervalMs(this) - 50) / 50).coerceIn(0, 59)
        }
        updateClickerLabel()
    }

    private fun updateClickerLabel() {
        clickerLabel.text = if (Prefs.clickerUseCps(this)) {
            "連打速度：${Prefs.clickerCps(this)} 回/秒"
        } else {
            val ms = Prefs.clickerIntervalMs(this)
            "連打間隔：$ms ms（約 ${"%.1f".format(1000.0 / ms)} 回/秒）"
        }
    }

    private fun updateJitterLabel() {
        val j = Prefs.clickerJitterPct(this)
        jitterLabel.text = if (j == 0) {
            "間隔のゆらぎ：なし（正確に一定間隔）"
        } else {
            "間隔のゆらぎ：±$j %（毎回ランダムにずらします）"
        }
    }

    private fun updateLimitLabel() {
        val m = Prefs.clickerLimitMinutes(this)
        limitLabel.text = if (m == 0) {
            "自動停止：なし（手動で止めるまで連打）"
        } else {
            "自動停止：$m 分たったら自動で止まります"
        }
    }

    private fun updatePressLabel() {
        pressLabel.text = "1回押す長さ：${Prefs.clickerPressMs(this)} ms（反応しないアプリには長めが有効）"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabled.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun refreshStatus() {
        val a11y = if (isAccessibilityEnabled()) "オン" else "まだ"
        val overlay = if (Settings.canDrawOverlays(this)) "オン" else "まだ"
        val floating = if (OverlayService.isRunning) "表示中" else "非表示"
        statusText.text = "ユーザー補助：$a11y ／ 上に表示：$overlay ／ フローティングボタン：$floating"

        val names = mutableListOf("セルサバイバー")
        for (pkg in Prefs.targetPackages(this)) names.add(appLabel(pkg))
        targetsText.text = "対象アプリ：" + names.joinToString("、")
        val lastPkg = Prefs.lastForeignPkg(this)
        btnAddLast.text = if (lastPkg.isNotEmpty() && lastPkg != GAME_PACKAGE) {
            "近道：直前に使っていたアプリを追加（${Prefs.lastForeignLabel(this)}）"
        } else {
            "近道：直前に使っていたアプリを追加"
        }
    }

    private fun appLabel(pkg: String): String = try {
        val ai = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(ai).toString()
    } catch (e: Exception) {
        pkg
    }

    /** Installed launcher apps (package to display name), excluding
     *  this app and the game (the game is always a target). */
    private fun launcherApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = LinkedHashMap<String, String>()
        for (ri in packageManager.queryIntentActivities(intent, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || pkg == GAME_PACKAGE) continue
            if (!found.containsKey(pkg)) {
                found[pkg] = ri.loadLabel(packageManager).toString()
            }
        }
        return found.entries.map { it.key to it.value }.sortedBy { it.second }
    }

    private fun showTargetPicker() {
        val apps = launcherApps()
        if (apps.isEmpty()) {
            Toast.makeText(this, "アプリ一覧を取得できませんでした", Toast.LENGTH_LONG).show()
            return
        }
        val targets = Prefs.targetPackages(this).toMutableSet()
        val names = apps.map { it.second }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].first in targets }
        AlertDialog.Builder(this)
            .setTitle("対象アプリ（セルサバイバーは常に対象です）")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val pkg = apps[which].first
                if (isChecked) targets.add(pkg) else targets.remove(pkg)
                Prefs.setTargetPackages(this, targets)
                refreshStatus()
            }
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun addLastForeign() {
        val pkg = Prefs.lastForeignPkg(this)
        if (pkg.isEmpty() || pkg == GAME_PACKAGE || pkg == packageName) {
            Toast.makeText(
                this,
                "直前に使っていたアプリがまだ記録されていません。対象にしたいアプリを一度開いてから戻ってきてください",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val targets = Prefs.targetPackages(this).toMutableSet()
        targets.add(pkg)
        Prefs.setTargetPackages(this, targets)
        Toast.makeText(this, "対象に追加しました：" + Prefs.lastForeignLabel(this), Toast.LENGTH_LONG).show()
        refreshStatus()
    }

    private fun refreshLog() {
        val lines = LogStore.all(this)
        logText.text = if (lines.isEmpty()) "（まだ記録がありません）" else lines.joinToString("\n")
    }
}

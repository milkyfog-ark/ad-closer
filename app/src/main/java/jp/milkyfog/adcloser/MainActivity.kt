package jp.milkyfog.adcloser

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
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

        bindSwitch(R.id.swMaster, { Prefs.master(this) }, { Prefs.setMaster(this, it) })
        bindSwitch(R.id.swResident, { Prefs.resident(this) }, { Prefs.setResident(this, it) })
        bindSwitch(R.id.swPatternA, { Prefs.patternA(this) }, { Prefs.setPatternA(this, it) })
        bindSwitch(R.id.swPatternB, { Prefs.patternB(this) }, { Prefs.setPatternB(this, it) })
        bindSwitch(R.id.swStore, { Prefs.storeRecovery(this) }, { Prefs.setStoreRecovery(this, it) })
        bindSwitch(R.id.swFarm, { Prefs.farm(this) }, { Prefs.setFarm(this, it) })

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
    }

    private fun bindSwitch(id: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = get()
        sw.setOnCheckedChangeListener { _, checked -> set(checked) }
    }

    private fun updateGuardLabel() {
        guardLabel.text = "左上パターンの待ち時間：${Prefs.guardSeconds(this)}秒（広告開始からこの秒数が過ぎるまで左上は押しません）"
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
    }

    private fun refreshLog() {
        val lines = LogStore.all(this)
        logText.text = if (lines.isEmpty()) "（まだ記録がありません）" else lines.joinToString("\n")
    }
}

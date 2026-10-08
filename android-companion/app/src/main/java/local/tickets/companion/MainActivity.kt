package local.tickets.companion

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var pairing: TextView
    private val refresh = object : Runnable {
        override fun run() {
            status.text = Session.status().toString(2)
            renderPairing()
            Session.main.postDelayed(this, 750)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The short pairing code must never appear in screenshots or recent-app thumbnails.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 56, 28, 40) }
        val scroll = ScrollView(this).apply { addView(layout) }
        setContentView(scroll)
        layout.addView(TextView(this).apply {
            text = "手机验证与本地执行预览\n配对接口仅用于读取、状态和停止。手机本地任务须在本机明确确认，只操作已适配的大麦原生控件，到确认页立即停止，不提交、不支付。截图和页面文字仅在内存中处理。"
            textSize = 18f
        })
        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("配置手机本地任务（仅到确认页，不提交）") { startActivity(Intent(this, ExecutionActivity::class.java)) }
        button("停止手机本地任务") { local.tickets.companion.execution.LocalExecutionRuntime.stop() }
        button("1. 打开无障碍设置（必须手动开启）") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("2. 授权屏幕读取（系统确认）") {
            if (Session.accessibility == null) { toast("请先手动开启本应用的无障碍服务"); return@button }
            Session.capture?.stopSafely("regrant_requested")
            val manager = getSystemService(MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), 100)
        }
        button("3. 打开大麦并仅检查当前页") {
            if (Session.accessibility == null) { toast("请先开启无障碍服务"); return@button }
            val launch = packageManager.getLaunchIntentForPackage(Safety.DAMAI)
            if (launch == null) { toast("未检测到大麦"); return@button }
            try {
                startActivity(launch)
                // Navigation is only to the app launch intent; no product or purchase is selected.
                Session.main.postDelayed({ Session.inspect() }, 1600)
            } catch (_: Exception) { Session.lastError = "damai_launch_failed" }
        }
        button("4. 打开合成测试页（无订单）") { startActivity(Intent(this, SyntheticActivity::class.java)) }
        button(getString(R.string.native_audit_open)) {
            if (Session.accessibility == null) { toast("请先手动开启本应用的无障碍服务"); return@button }
            val launch = packageManager.getLaunchIntentForPackage(Safety.DAMAI)
            if (launch == null) { toast("未检测到大麦"); return@button }
            Session.armNativeAudit()
            try { startActivity(launch); toast(getString(R.string.native_audit_armed)) }
            catch (_: Exception) { Session.revokeNativeAudit(); Session.lastError = "damai_launch_failed" }
        }
        layout.addView(TextView(this).apply { setText(R.string.native_audit_help) })
        button(getString(R.string.capture_stop_and_revoke)) { Session.stop(); renderPairing() }
        button(getString(R.string.pairing_generate)) {
            if (Session.accessibility == null) { toast("请先手动开启本应用的无障碍服务"); return@button }
            Session.generatePairingCode()
            renderPairing()
        }
        button(getString(R.string.pairing_revoke)) { Session.revokePairingCode(); renderPairing() }
        pairing = TextView(this).apply {
            textSize = 18f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
            setTextIsSelectable(false)
        }
        layout.addView(pairing)
        renderPairing()
        layout.addView(TextView(this).apply { setText(R.string.pairing_help); textSize = 14f })
        status = TextView(this).apply { textSize = 12f }
        layout.addView(status)
    }
    @Deprecated("API 29 compatible consent result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100) return
        if (resultCode == RESULT_OK && data != null) {
            try {
                startForegroundService(Intent(this, CaptureService::class.java).putExtra("resultCode", resultCode).putExtra("consent", data))
            } catch (_: Exception) { Session.lastError = "capture_start_failed" }
        } else {
            Session.captureState = "consent_denied"
            Session.lastError = "consent_denied"
        }
    }
    private fun renderPairing() {
        pairing.text = Session.pairingCode()?.let { getString(R.string.pairing_active, it) } ?: getString(R.string.pairing_inactive)
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    override fun onResume() { super.onResume(); Session.main.post(refresh) }
    override fun onPause() { Session.main.removeCallbacks(refresh); super.onPause() }
}

class SyntheticActivity : Activity() {
    private lateinit var status: TextView
    private val refresh = object : Runnable {
        override fun run() { status.text = Session.status().toString(2); Session.main.postDelayed(this, 500) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 56, 28, 40) }
        setContentView(ScrollView(this).apply { addView(layout) })
        layout.addView(TextView(this).apply { text = "合成测试页\n中文识别测试：演唱会 · 选择场次 · 选择票档\n所有内容均为合成数据，无账号、无真实订单。"; textSize = 19f })
        layout.addView(Button(this).apply {
            id = R.id.synthetic_target
            text = "唯一测试按钮（只增加计数）"
            setOnClickListener { Session.testClickCount++ }
        })
        layout.addView(Button(this).apply {
            text = "通过无障碍点击唯一测试按钮"
            setOnClickListener {
                val success = Session.accessibility?.clickSynthetic() == true
                Toast.makeText(this@SyntheticActivity, if (success) "测试点击已请求" else "未点击：检查权限及唯一控件", Toast.LENGTH_SHORT).show()
            }
        })
        layout.addView(Button(this).apply { text = "检查合成页：节点 + 已授权 OCR"; setOnClickListener { Session.inspect() } })
        layout.addView(CheckBox(this).apply {
            text = "模拟受保护窗口（下次检查应提示黑屏/受保护）"
            setOnCheckedChangeListener { _, checked ->
                if (checked) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        })
        val paymentLabel = TextView(this).apply { text = "" }
        layout.addView(CheckBox(this).apply {
            text = "模拟危险页面（立即停止读取）"
            setOnCheckedChangeListener { _, checked -> paymentLabel.text = if (checked) "确认订单 · 提交订单 · 确认支付（合成，无真实操作）" else "" }
        })
        layout.addView(paymentLabel)
        layout.addView(Button(this).apply { text = "停止屏幕读取"; setOnClickListener { Session.stop() } })
        status = TextView(this).apply { textSize = 12f }
        layout.addView(status)
    }
    override fun onResume() { super.onResume(); Session.syntheticVisible = true; Session.main.post(refresh) }
    override fun onPause() { Session.syntheticVisible = false; Session.main.removeCallbacks(refresh); super.onPause() }
}

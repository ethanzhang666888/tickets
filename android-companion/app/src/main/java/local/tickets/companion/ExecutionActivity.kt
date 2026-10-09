package local.tickets.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import local.tickets.companion.execution.LocalExecutionRuntime
import local.tickets.companion.execution.LocalPlan
import local.tickets.companion.pages.DamaiPageParser
import local.tickets.companion.policy.TicketTarget
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Explicit local authorization; no exported intent extras, saved names, credentials, or automatic resume. */
class ExecutionActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingPlan: LocalPlan? = null
    private var consentInFlight = false
    private var captureDeadline = 0L
    private var resumed = false
    private var confirmationDialog: AlertDialog? = null
    private lateinit var startButton: Button
    private val awaitCapture = object : Runnable {
        override fun run() {
            if (pendingPlan == null || isFinishing || isDestroyed) return
            val remaining = captureDeadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                cancelPending("屏幕读取未能在5秒内就绪，未启动任务；请重新核对并授权")
            } else if (Session.capture != null && resumed) {
                startPendingPlan()
            } else {
                handler.postDelayed(this, minOf(100L, remaining))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 48, 28, 32) }
        setContentView(ScrollView(this).apply { addView(layout) })
        fun note(text: String) { layout.addView(TextView(this).apply { this.text = text; textSize = 16f }) }
        fun field(hint: String, value: String = "", multiline: Boolean = false): EditText = EditText(this).also {
            it.hint = hint; it.setText(value); it.isSaveEnabled = false
            it.isSaveFromParentEnabled = false
            it.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            it.inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            layout.addView(it)
        }
        note("手机本地执行预览 · 核对后停确认页\n仅适配已知原生布局；自绘选票证据需手动授权屏幕读取，并仅在本机进行文字识别（OCR）。未知或证据不完整的页面会停止，不猜坐标。选票、数量及观演人核对后停在确认页，不提交、不付款。\n请先停止电脑购票任务，在大麦打开目标页面，再回到这里配置。")
        val event = field("准确演出名称")
        note("目标按优先顺序，每行：完整日期时间 | 票档名称 | 单价（元）。例如：2026-11-07 19:00 | 内场880元 | 880。目标会在操作前与手机当前页面逐项匹配。")
        val targets = field("每行一个允许购买的日期与票档组合", multiline = true)
        val quantity = field("张数", "1")
        note("观演人留空时，默认选择页面顺序前N位（N为张数）；自定义时每行填写一位的准确姓名或页面完整展示文字，人数须与张数一致且不能重复。名单仅在内存中使用，不保存、不自动填充；同名或无法准确匹配时停止。")
        val buyers = field("观演人：留空默认前N位，或每行一位", multiline = true).apply {
            inputType = inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val budget = field("最高总预算（元）")
        val start = field("北京时间 uuuu-MM-dd HH:mm:ss；留空立即开始")
        val duration = field("从开始起持续分钟数，最多2880", "120")
        val priority = field("仅尝试第一目标的分钟数，0至60", "3")
        val waiting = CheckBox(this).apply { text = "缺货后仅被动观察，并在优先窗口后尝试上述备选（不主动刷新库存，无自动提交）" }
        layout.addView(waiting)
        note("USB断开后，已在手机确认的任务继续本地运行；锁屏、离开大麦、权限失效或到期则停止。进程被系统终止后不恢复。保持解锁和供电；当前真实业务流程及长时间运行仍待真机验收。")
        startButton = Button(this).apply {
            text = "核对任务并开始（不提交、不付款）"
            setOnClickListener {
                if (pendingPlan != null || consentInFlight || confirmationDialog != null) return@setOnClickListener
                try {
                    check(!LocalExecutionRuntime.isActive()) { "已有手机任务，请先停止" }
                    check(Session.accessibility != null && !Session.locked(this@ExecutionActivity)) { "请先解锁并手动开启无障碍服务" }
                    val now = System.currentTimeMillis()
                    val startAt = if (start.text.isBlank()) now else LocalDateTime.parse(start.text.toString().trim(),
                        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT))
                        .toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
                    val minutes = duration.text.toString().toLong()
                    require(minutes in 1..2880) { "持续时间应为1至2880分钟" }
                    val priorityMinutes = priority.text.toString().toLong()
                    require(priorityMinutes in 0..60) { "优先时段应为0至60分钟" }
                    val choices = targets.text.toString().lineSequence().filter { it.isNotBlank() }.map { line ->
                        val parts = line.split('|').map { it.trim() }
                        require(parts.size == 3) { "每个目标必须包含日期、票档和单价，用 | 分隔" }
                        TicketTarget(parts[0], parts[1], DamaiPageParser.moneyCents(parts[2]) ?: error("单价格式无效"))
                    }.toList()
                    val buyerLabels = buyers.text.toString().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                    val plan = LocalPlan(event.text.toString().trim(), choices, quantity.text.toString().toInt(),
                        DamaiPageParser.moneyCents(budget.text.toString()) ?: error("总预算无效"), startAt,
                        startAt + minutes * 60_000, priorityMinutes * 60_000, waiting.isChecked,
                        autoSubmit = false, buyerLabels = buyerLabels)
                    plan.validate(now)
                    check(damaiLaunchIntent() != null) { "未安装大麦" }
                    fun yuan(cents: Long) = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
                    fun beijing(time: Long) = java.time.Instant.ofEpochMilli(time).atOffset(ZoneOffset.ofHours(8))
                        .format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"))
                    val buyerSummary = if (plan.buyerLabels.isEmpty()) "默认选择页面顺序前${plan.quantity}位观演人"
                        else "自定义观演人（准确姓名或完整展示文字）：\n${plan.buyerLabels.joinToString("\n")}"
                    confirmationDialog = AlertDialog.Builder(this@ExecutionActivity).setTitle("确认手机本地任务")
                        .setMessage("${plan.eventTitle}\n目标优先顺序：\n${choices.joinToString("\n") { "${it.session} · ${it.tier} · 单价${yuan(it.unitPriceCents)}元" }}\n${plan.quantity}张，总预算 ${yuan(plan.maxTotalCents)} 元\n$buyerSummary\n开始：${beijing(plan.startAtMs)}（北京时间）\n截止：${beijing(plan.endAtMs)}（北京时间）\n仅第一目标优先时段：${priorityMinutes}分钟\n缺货等待：${if (plan.waitForStock) "仅被动观察，优先窗口后可尝试上述备选；不主动刷新" else "关闭"}\n已知原生布局 + 用户授权的本地OCR；未知或证据不足即停止。\n选票、数量和观演人核对后停在确认页，不提交、不付款，无自动提交。\n${if (Session.capture == null) "下一步需您手动完成系统屏幕读取授权，拒绝或超时均不启动任务。" else "使用当前已授权的屏幕读取。"}\n截图和名单仅在内存中处理，不保存、不外传。请确认电脑购票任务已停止。")
                        .setNegativeButton("取消", null).setPositiveButton("确认并开始") { _, _ -> authorizeAndStart(plan) }
                        .create().also { dialog ->
                            dialog.setOnDismissListener { confirmationDialog = null; updateStartButton() }
                            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            dialog.show()
                        }
                    updateStartButton()
                } catch (error: Exception) {
                    val message = if (error is IllegalArgumentException || error is IllegalStateException) error.message else null
                    Toast.makeText(this@ExecutionActivity, message ?: "输入格式无效", Toast.LENGTH_LONG).show()
                }
            }
        }
        layout.addView(startButton)
        layout.addView(Button(this).apply {
            text = "停止手机任务"
            setOnClickListener { cancelPending(); LocalExecutionRuntime.stop() }
        })
    }

    private fun authorizeAndStart(plan: LocalPlan) {
        if (isFinishing || isDestroyed || pendingPlan != null || consentInFlight) return
        pendingPlan = plan
        updateStartButton()
        if (Session.capture != null) {
            startPendingPlan()
            return
        }
        try {
            check(Session.accessibility != null && !Session.locked(this))
            consentInFlight = true
            val manager = getSystemService(MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE_CONSENT_REQUEST)
        } catch (_: Exception) {
            consentInFlight = false
            cancelPending("未能请求屏幕读取授权，未启动任务；请检查解锁和无障碍权限")
        }
    }

    @Deprecated("API 29 compatible consent result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CAPTURE_CONSENT_REQUEST || !consentInFlight) return
        consentInFlight = false
        if (pendingPlan == null || isFinishing || isDestroyed) { cancelPending(); return }
        if (resultCode != RESULT_OK || data == null) {
            cancelPending("屏幕读取授权已取消，未启动任务")
            return
        }
        try {
            // Consent is supplied only by the system result; never persisted or automatically approved.
            startForegroundService(Intent(this, CaptureService::class.java)
                .putExtra("resultCode", resultCode).putExtra("consent", data))
            captureDeadline = SystemClock.elapsedRealtime() + 5_000
            handler.post(awaitCapture)
        } catch (_: Exception) {
            cancelPending("屏幕读取启动失败，未启动任务")
        }
    }

    private fun startPendingPlan() {
        val plan = pendingPlan ?: return
        if (isFinishing || isDestroyed) { cancelPending(); return }
        if (LocalExecutionRuntime.isActive()) { cancelPending("已有手机任务，请先停止"); return }
        try {
            check(Session.capture != null) { "屏幕读取授权已失效" }
            val launch = damaiLaunchIntent() ?: error("未安装大麦")
            LocalExecutionRuntime.start(applicationContext, plan)
            startActivity(launch)
            cancelPending()
            finish()
        } catch (_: Exception) {
            LocalExecutionRuntime.stop("launch_failed")
            cancelPending("未启动，任务已安全停止；请检查解锁、权限和任务状态")
        }
    }

    private fun cancelPending(message: String? = null) {
        pendingPlan = null
        captureDeadline = 0L
        handler.removeCallbacks(awaitCapture)
        updateStartButton()
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun updateStartButton() {
        if (::startButton.isInitialized) startButton.isEnabled = pendingPlan == null && !consentInFlight && confirmationDialog == null
    }

    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onDestroy() {
        pendingPlan = null
        consentInFlight = false
        handler.removeCallbacksAndMessages(null)
        confirmationDialog?.dismiss()
        confirmationDialog = null
        super.onDestroy()
    }

    private companion object { const val CAPTURE_CONSENT_REQUEST = 101 }
}

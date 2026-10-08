package local.tickets.companion

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 48, 28, 32) }
        setContentView(ScrollView(this).apply { addView(layout) })
        fun note(text: String) { layout.addView(TextView(this).apply { this.text = text; textSize = 16f }) }
        fun field(hint: String, value: String = "", multiline: Boolean = false): EditText = EditText(this).also {
            it.hint = hint; it.setText(value); it.isSaveEnabled = false
            it.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            it.inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            layout.addView(it)
        }
        note("手机本地执行预览 · 仅到确认页\n只适配已知原生控件。新版自绘票档尚不支持，遇到不完整布局会停止，不猜坐标。此版本不选观演人、不提交、不付款。\n请先停止电脑购票任务，在大麦打开目标页面，再回到这里配置。")
        val event = field("准确演出名称")
        note("目标按优先顺序，每行：完整日期时间 | 票档名称 | 单价（元）。例如：2026-11-07 19:00 | 内场880元 | 880。目标会在操作前与手机当前页面逐项匹配。")
        val targets = field("每行一个允许购买的日期与票档组合", multiline = true)
        val quantity = field("张数", "1")
        val budget = field("最高总预算（元）")
        val start = field("北京时间 uuuu-MM-dd HH:mm:ss；留空立即开始")
        val duration = field("从开始起持续分钟数，最多2880", "120")
        val priority = field("仅尝试第一目标的分钟数，0至60", "3")
        val waiting = CheckBox(this).apply { text = "缺货后继续观察，并在优先窗口后尝试上述备选（不主动刷新库存）" }
        layout.addView(waiting)
        note("USB断开后，已在手机确认的任务继续本地运行；锁屏、离开大麦、权限失效或到期则停止。进程被系统终止后不恢复。保持解锁和供电；当前尚未通过长时间真机验收。")
        layout.addView(Button(this).apply {
            text = "核对任务并开始（不提交订单）"
            setOnClickListener {
                try {
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
                    val plan = LocalPlan(event.text.toString().trim(), choices, quantity.text.toString().toInt(),
                        DamaiPageParser.moneyCents(budget.text.toString()) ?: error("总预算无效"), startAt,
                        startAt + minutes * 60_000, priorityMinutes * 60_000, waiting.isChecked)
                    plan.validate(now)
                    val launch = packageManager.getLaunchIntentForPackage(Safety.DAMAI) ?: error("未安装大麦")
                    AlertDialog.Builder(this@ExecutionActivity).setTitle("确认手机本地任务")
                        .setMessage("${plan.eventTitle}\n${choices.joinToString("\n") { "${it.session} · ${it.tier}" }}\n${plan.quantity}张，总预算 ${budget.text} 元\n仅到确认页，不提交订单。请确认电脑购票任务已停止。")
                        .setNegativeButton("取消", null).setPositiveButton("确认并开始") { _, _ ->
                            try {
                                Session.capture?.stopSafely("local_native_execution")
                                LocalExecutionRuntime.start(applicationContext, plan)
                                startActivity(launch)
                                finish()
                            } catch (_: Exception) {
                                LocalExecutionRuntime.stop("launch_failed")
                                Toast.makeText(this@ExecutionActivity, "未启动，请检查解锁、权限和任务状态", Toast.LENGTH_LONG).show()
                            }
                        }.show()
                } catch (error: Exception) {
                    val message = if (error is IllegalArgumentException || error is IllegalStateException) error.message else null
                    Toast.makeText(this@ExecutionActivity, message ?: "输入格式无效", Toast.LENGTH_LONG).show()
                }
            }
        })
        layout.addView(Button(this).apply { text = "停止手机任务"; setOnClickListener { LocalExecutionRuntime.stop() } })
    }
}

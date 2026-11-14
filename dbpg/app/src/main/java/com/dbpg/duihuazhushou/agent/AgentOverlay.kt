package com.dbpg.duihuazhushou.agent

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dbpg.duihuazhushou.DhpApp
import com.dbpg.stcore.agent.TraceBus
import com.dbpg.stcore.agent.TraceEvent

/**
 * 迷你浮窗：折叠是状态胶囊，展开是结构化 Trace 时间线。
 *
 * ★ 纯原生、全局单例、跨界面持续：挂在 Activity 的 decorView 上，界面切换时
 *   把同一份 view 搬家，不会重建、不会丢 Trace。用 applicationContext 建 view，
 *   不持有 Activity。
 *
 * ★ 「不点开就看正文」：折叠态只有一个小胶囊，只显示最近一条状态；不抢占
 *   消息流。Agent 分步调用比直出慢，胶囊上给出「编排中 / 写正文 / 完成」，
 *   避免用户以为卡死。
 *
 * ★ 面板不写死：[showPanel] 接收任意 payload，按开放结构渲染；不认识的字段
 *   降级成通用键值展示，不报错、不拒绝——卡片/插件以后想弹什么自己拼。
 */
object AgentOverlay {

    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var timeline: LinearLayout? = null
    private var customHost: LinearLayout? = null
    private var expanded = false
    private var lastStatus = "待命"

    private var unsubscribe: (() -> Unit)? = null

    /** 由 DhpApp 的生命周期回调调用（Java 侧）。幂等。 */
    @JvmStatic
    fun attach(activity: Activity) {
        main.post {
            if (!AgentSettings.isOverlayOn(activity)) {
                detach()
                return@post
            }
            ensureBuilt()
            val decor = activity.window?.decorView as? ViewGroup ?: return@post
            val r = root ?: return@post
            if (r.parent !== decor) {
                (r.parent as? ViewGroup)?.removeView(r)
                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END,
                ).apply {
                    val d = (activity.resources.displayMetrics.density * 12).toInt()
                    setMargins(d, d, d, d * 7)
                }
                decor.addView(r, lp)
            }
            render()
        }
    }

    @JvmStatic
    fun detach() {
        main.post {
            (root?.parent as? ViewGroup)?.removeView(root)
        }
    }

    private fun ensureBuilt() {
        if (root != null) return
        val ctx = DhpApp.instance.applicationContext
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val container = FrameLayout(ctx)

        val b = TextView(ctx).apply {
            text = "◈ 待命"
            textSize = 11f
            setTextColor(Color.parseColor("#F4F7FF"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundBg(Color.parseColor("#CC3B5A86"), dp(14))
            isClickable = true
            setOnClickListener { expanded = !expanded; render() }
        }
        bubble = b
        container.addView(
            b,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ),
        )

        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(Color.parseColor("#F7FBFF"), dp(12))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(ctx).apply {
            text = "智能体过程"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A2230"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(ctx).apply {
            text = "清空"
            textSize = 11f
            setTextColor(Color.parseColor("#5A6B85"))
            setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener { TraceBus.clear(); renderTimeline() }
        })
        head.addView(TextView(ctx).apply {
            text = "收起"
            textSize = 11f
            setTextColor(Color.parseColor("#5A6B85"))
            setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener { expanded = false; render() }
        })
        p.addView(head)

        // 卡片/插件自定义面板的宿主：独立一块，不和时间线耦合
        val custom = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        customHost = custom
        p.addView(custom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val tl = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        timeline = tl
        val scroll = ScrollView(ctx).apply { addView(tl) }
        p.addView(
            scroll,
            LinearLayout.LayoutParams(dp(260), dp(300)),
        )
        panel = p
        container.addView(
            p,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = dp(34) },
        )

        root = container
        unsubscribe = TraceBus.subscribe { ev ->
            main.post {
                lastStatus = statusOf(ev)
                b.text = "◈ $lastStatus"
                renderTimeline()
            }
        }
        renderTimeline()
    }

    private fun statusOf(ev: TraceEvent) = when (ev.type) {
        TraceEvent.AGENT_BEGIN -> "编排中…"
        TraceEvent.STAGE_START -> "编排·${ev.stage}"
        TraceEvent.TOOL_CALL -> "调用工具·${ev.label}"
        TraceEvent.GEN_DELTA -> "写正文…"
        TraceEvent.GEN_DONE, TraceEvent.AGENT_PLAN -> "完成"
        TraceEvent.GEN_ERROR, TraceEvent.STAGE_FALLBACK -> "已退化"
        TraceEvent.GEN_STOP -> "已停止"
        else -> lastStatus
    }

    private fun render() {
        val r = root ?: return
        if (!AgentSettings.isOverlayOn(DhpApp.instance)) {
            r.visibility = View.GONE
            return
        }
        r.visibility = View.VISIBLE
        panel?.visibility = if (expanded) View.VISIBLE else View.GONE
        bubble?.text = "◈ $lastStatus"
        if (expanded) renderTimeline()
    }

    private fun renderTimeline() {
        val tl = timeline ?: return
        if (!expanded) return
        tl.removeAllViews()
        val ctx = DhpApp.instance.applicationContext
        val events = TraceBus.history().takeLast(80)
        if (events.isEmpty()) {
            tl.addView(TextView(ctx).apply {
                text = "还没有过程记录。发一条消息试试。"
                textSize = 11f
                setTextColor(Color.parseColor("#8A94A6"))
            })
            return
        }
        for (ev in events) {
            val line = TextView(ctx).apply {
                val dur = if (ev.durationMs >= 0) " ${ev.durationMs}ms" else ""
                val stage = if (ev.stage.isNotBlank()) "[${ev.stage}] " else ""
                val label = if (ev.label.isNotBlank()) "${ev.label} · " else ""
                val detail = ev.detail.take(120)
                text = "${ev.type}  $stage$label$detail$dur"
                textSize = 10.5f
                setTextColor(
                    when {
                        ev.type.contains("fallback") || ev.type == TraceEvent.GEN_ERROR -> Color.parseColor("#B0423A")
                        ev.type == TraceEvent.TOOL_CALL || ev.type == TraceEvent.TOOL_RESULT -> Color.parseColor("#2C6E8F")
                        else -> Color.parseColor("#3A4658")
                    },
                )
                setPadding(0, 2, 0, 2)
            }
            tl.addView(line)
        }
    }

    /**
     * 卡片/插件请求弹一个自定义面板。
     *
     * payload 是**开放结构**：识别 title / lines / text / rows 几种常见形状，
     * 其余字段一律降级成「键：值」文本。不认识不报错、不拒绝——后面插件
     * 想弹花哨变量，自己往 payload 里塞就行，这里不用改。
     */
    @JvmStatic
    fun showPanel(payload: Map<String, Any?>) {
        main.post {
            ensureBuilt()
            val host = customHost ?: return@post
            host.removeAllViews()
            val ctx = DhpApp.instance.applicationContext
            val title = payload["title"]?.toString()
            if (!title.isNullOrBlank()) {
                host.addView(TextView(ctx).apply {
                    text = title
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#1A2230"))
                })
            }
            val lines = mutableListOf<String>()
            payload["text"]?.toString()?.let { lines.add(it) }
            (payload["lines"] as? Iterable<*>)?.forEach { lines.add(it.toString()) }
            (payload["rows"] as? Iterable<*>)?.forEach { row ->
                @Suppress("UNCHECKED_CAST")
                val m = row as? Map<*, *>
                lines.add(if (m != null) "${m["key"] ?: m["label"] ?: ""}：${m["value"] ?: ""}" else row.toString())
            }
            // 没匹配到常见形状时，把整个 payload 平铺出来，保证「总有东西可看」
            if (lines.isEmpty()) {
                payload.filterKeys { it != "title" }.forEach { (k, v) -> lines.add("$k：$v") }
            }
            for (line in lines) {
                host.addView(TextView(ctx).apply {
                    text = line.take(400)
                    textSize = 11f
                    setTextColor(Color.parseColor("#3A4658"))
                    setPadding(0, 2, 0, 2)
                })
            }
            host.addView(TextView(ctx).apply {
                text = "关闭面板"
                textSize = 10.5f
                setTextColor(Color.parseColor("#5A6B85"))
                setPadding(0, 6, 0, 6)
                setOnClickListener { host.removeAllViews(); host.visibility = View.GONE }
            })
            host.visibility = View.VISIBLE
            expanded = true
            render()
        }
    }

    private fun roundBg(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        setStroke(1, Color.parseColor("#33445A82"))
    }
}

package com.dbpg.duihuazhushou.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.appearance.AppearanceConfig
import com.dbpg.duihuazhushou.appearance.ThemePalette
import com.dbpg.duihuazhushou.appearance.ThemeTokens
import com.dbpg.duihuazhushou.appearance.withAlpha
import com.dbpg.stcore.panel.PanelData

/**
 * 通用交互面板弹窗（`<options>` / `<summary>` / `<comment>`）。
 *
 * ## 它解决什么
 *
 * 预设提示词让模型吐 `<options><option>…</option></options>` 这类标签时，
 * 酒馆那套靠预设自带的显示正则把标签整块换成 HTML 皮肤卡片。没带皮肤的
 * 预设在我们这里只能看到一堆裸 XML。这里把标签解析成结构化面板，任何
 * 预设都能弹出可点的选项列表 —— 不绑定某一家的皮肤。
 *
 * ## 交互语义（对齐酒馆助手的选项面板）
 *
 * 点一条选项后干什么，由底部的模式开关决定：
 *   - 直接发送：选项文本作为消息发出并开始生成；
 *   - 仅填入：覆盖输入框，用户自己改/发；
 *   - 增量填入：追加到输入框现有内容后面。
 * 模式全局记住（用户习惯，不按会话存）。
 *
 * ## 视觉
 *
 * 与 [ActionSheet] 同一套底部面板语言（圆角 / 把手 / ThemeTokens 着色），
 * 辅助函数直接复用 —— 两个弹窗必须像一家人。
 */
object OptionPanelSheet {

    /** 选项点选后的去向。 */
    enum class Mode(val label: String, val hint: String) {
        SEND("直接发送", "点选后立即发出"),
        REPLACE("仅填入", "覆盖输入框现有内容"),
        APPEND("增量填入", "追加到输入框现有内容"),
        ;

        fun next(): Mode = entries[(ordinal + 1) % entries.size]
    }

    /** 点了选项：把文本和当前模式交回给聊天界面。 */
    fun interface Host {
        fun onPick(text: String, mode: Mode)
    }

    private const val PREFS = "dhp_panel"
    private const val KEY_MODE = "option_mode"

    fun loadMode(ctx: Context): Mode {
        val name = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null)
        return Mode.entries.firstOrNull { it.name == name } ?: Mode.SEND
    }

    private fun saveMode(ctx: Context, mode: Mode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
    }

    fun show(
        ctx: Context,
        cfg: AppearanceConfig,
        panel: PanelData,
        host: Host,
    ) {
        if (panel.isEmpty) return
        val t = ThemePalette.tokensOf(cfg)
        val tf = ThemePalette.fontTypeface(cfg.fontFamily)
        val dialog = ActionSheet.baseDialog(ctx)
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_option_panel, null)
        dialog.setContentView(view)
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        view.background = ActionSheet.round(ctx, ActionSheet.panelOf(t), 24f)
        view.findViewById<View>(R.id.panel_handle).background =
            ActionSheet.round(ctx, withAlpha(t.textFaint, 0.45f), 2f)

        view.findViewById<TextView>(R.id.panel_title).apply {
            text = if (panel.options.isEmpty()) "剧情面板" else "请选择回应"
            setTypeface(tf)
            setTextColor(t.text)
        }
        view.findViewById<TextView>(R.id.panel_subtitle).apply {
            val parts = ArrayList<String>()
            if (panel.options.isNotEmpty()) parts.add("${panel.options.size} 个选项")
            if (panel.comments.isNotEmpty()) parts.add("留言")
            if (panel.summaries.isNotEmpty()) parts.add("剧情摘要")
            if (parts.isEmpty()) visibility = View.GONE else {
                visibility = View.VISIBLE
                text = parts.joinToString(" · ")
                setTypeface(tf)
                setTextColor(t.textDim)
            }
        }

        val box = view.findViewById<LinearLayout>(R.id.panel_container)

        // 留言（<comment>）：放在最上，是"旁白/悄悄话"
        for (c in panel.comments) {
            box.addView(card(ctx, t, tf, "🐾 留言", c, density))
        }

        // 选项（<options>）：编号按钮，点选后按模式出手
        panel.options.forEachIndexed { i, text ->
            box.addView(optionRow(ctx, t, tf, i + 1, text, density) {
                dialog.dismiss()
                host.onPick(text, modeOf(ctx))
            })
        }

        // 剧情摘要（<summary>）：可折叠，不抢选项的视觉焦点
        if (panel.summaries.isNotEmpty()) {
            box.addView(card(ctx, t, tf, "📖 剧情摘要", panel.summaries.joinToString("\n\n"), density))
        }

        // 模式开关：点一下换一档，跟酒馆助手的「直接发送」开关同语义
        val modeView = view.findViewById<TextView>(R.id.panel_mode)
        fun paintMode() {
            val m = modeOf(ctx)
            modeView.text = "${m.label} ›\n${m.hint}"
            modeView.setTypeface(tf)
            modeView.setTextColor(t.accent)
        }
        paintMode()
        modeView.setOnClickListener {
            saveMode(ctx, modeOf(ctx).next())
            paintMode()
        }

        view.findViewById<TextView>(R.id.panel_close).apply {
            setTypeface(tf)
            setTextColor(t.textDim)
            background = ActionSheet.ripple(
                ctx,
                ActionSheet.round(ctx, t.panelSoft, 16f),
                withAlpha(t.text, 0.10f),
                16f,
            )
            setOnClickListener { dialog.dismiss() }
        }

        // 选项多时不能把底部操作行顶出屏幕
        val scroll = view.findViewById<android.widget.ScrollView>(R.id.panel_scroll)
        view.post {
            val max = (ctx.resources.displayMetrics.heightPixels * 0.62f).toInt()
            if (scroll.height > max) {
                scroll.layoutParams = scroll.layoutParams.also { it.height = max }
            }
        }

        ActionSheet.present(dialog, ctx)
    }

    private fun modeOf(ctx: Context): Mode = loadMode(ctx)

    /** 留言 / 摘要卡片。 */
    private fun card(
        ctx: Context,
        t: ThemeTokens,
        tf: Typeface,
        title: String,
        body: String,
        density: Float,
    ): View {
        fun dp(v: Float) = (v * density).toInt()
        val ll = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ActionSheet.round(ctx, t.panelSoft, 16f)
            setPadding(dp(14f), dp(10f), dp(14f), dp(12f))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.setMargins(dp(6f), dp(4f), dp(6f), dp(6f))
            layoutParams = lp
        }
        ll.addView(TextView(ctx).apply {
            text = title
            textSize = 11f
            setTypeface(tf)
            setTextColor(t.textFaint)
        })
        ll.addView(TextView(ctx).apply {
            text = body
            textSize = 13f
            setTypeface(tf)
            setTextColor(t.textDim)
            setPadding(0, dp(4f), 0, 0)
        })
        return ll
    }

    /** 一条选项：序号 + 文本。 */
    private fun optionRow(
        ctx: Context,
        t: ThemeTokens,
        tf: Typeface,
        index: Int,
        optText: String,
        density: Float,
        onClick: () -> Unit,
    ): View {
        fun dp(v: Float) = (v * density).toInt()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ActionSheet.ripple(
                ctx,
                ActionSheet.round(ctx, Color.TRANSPARENT, 16f),
                withAlpha(t.text, 0.08f),
                16f,
            )
            minimumHeight = dp(52f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.setMargins(dp(6f), dp(2f), dp(6f), dp(2f))
            layoutParams = lp
            setOnClickListener { onClick() }
        }
        row.addView(TextView(ctx).apply {
            text = String.format("%02d", index)
            textSize = 11f
            setTypeface(tf)
            setTextColor(t.accent)
            background = ActionSheet.round(ctx, t.accentSoft, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        })
        row.addView(TextView(ctx).apply {
            text = optText
            textSize = 14f
            setTypeface(tf)
            setTextColor(t.text)
            setPadding(dp(10f), 0, 0, 0)
        })
        return row
    }
}

package com.dbpg.duihuazhushou.settings

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dbpg.duihuazhushou.appearance.Appearance
import com.dbpg.duihuazhushou.appearance.ThemePalette
import com.dbpg.duihuazhushou.appearance.ThemeTokens

/**
 * 运营商配置页的「token 化控件工厂」。
 *
 * 这个页面是动态列表（运营商卡片 / 模型行在运行时生成），而全局换肤
 * [com.dbpg.duihuazhushou.appearance.UiBackdrop] 只在 Activity 进入前台时递归
 * 染一次，之后 new 出来的 View 它见不到。所以这里统一做两件事：
 *
 *  1. **所有颜色只从 [ThemeTokens] 取**：页面代码里不出现一个硬编码色值，
 *     换肤（[ThemePalette.tokensOf] / 加一套 ThemeStyle）一处改，全页跟着变；
 *  2. 每个动态 View 打 `dhp:keep-color`：UiBackdrop 见到这个 tag 只换字体，
 *     不再按"白底 → 面板色"的规则二次染色，避免把徽章/头像染成一片。
 *
 * 列表每次 loadConfig 都会重建，顺带重新读一次 Appearance —— 所以从外观设置
 * 页换完主题回来，新配色立即生效，不需要重启页面。
 *
 * 以后要把这套控件换成别的皮肤（甚至换一整套圆角/字号），只改本文件即可。
 */
internal class ProviderUi(private val ctx: Context) {

    val tk: ThemeTokens = ThemePalette.tokensOf(Appearance.load(ctx))
    private val dm = ctx.resources.displayMetrics

    fun dp(v: Int): Int = Math.round(v * dm.density)
    fun dp(v: Float): Int = Math.round(v * dm.density)

    /** 动态视图统一标记：退出全局染色（但字体仍跟着换）。 */
    private fun <T : View> T.keep(): T = apply { tag = "dhp:keep-color" }

    fun shape(fill: Int, radiusDp: Float, stroke: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke) setStroke(dp(1), tk.hairline)
        }

    fun vertical(pad: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (pad > 0) setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    fun horizontal(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun text(
        value: String,
        size: Float = 14f,
        color: Int = tk.text,
        bold: Boolean = false,
    ): TextView = TextView(ctx).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }.keep()

    /** 内容卡片：主题面板底 + 细描边 + 大圆角。 */
    fun card(radius: Float = 18f, pad: Int = 14): LinearLayout =
        vertical(pad).apply { background = shape(tk.panel, radius, stroke = true) }.keep()

    /** 首字母 / 首字圆形头像，底色跟随主题色的淡版。 */
    fun avatar(letter: String, sizeDp: Int = 42): TextView = TextView(ctx).apply {
        text = letter
        textSize = if (sizeDp >= 40) 17f else 13f
        setTextColor(tk.accent)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = shape(tk.accentSoft, sizeDp / 2f)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }.keep()

    enum class Badge { ON, OFF, WARN, NEUTRAL }

    /**
     * 状态胶囊。刻意全部走主题色系而不是写死绿/蓝：
     * 深色皮肤（黑金/墨蓝）下绿色徽章会突兀，且换肤时改不到。
     * 状态靠「文案 + 深浅」区分，颜色由主题统一决定。
     */
    fun badge(label: String, kind: Badge = Badge.NEUTRAL): TextView = TextView(ctx).apply {
        text = label
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        val fg: Int
        val bg: Int
        when (kind) {
            Badge.ON -> { fg = tk.accent; bg = tk.accentSoft }
            Badge.WARN -> { fg = tk.accent; bg = tk.accentSoft }
            Badge.OFF -> { fg = tk.textFaint; bg = tk.panelSoft }
            Badge.NEUTRAL -> { fg = tk.textDim; bg = tk.panelSoft }
        }
        setTextColor(fg)
        background = shape(bg, 999f)
        setPadding(dp(9), dp(3), dp(9), dp(3))
    }.keep()

    /** 分段 Tab：选中是主题实底 + onAccent 文字，未选中是次级面板。 */
    fun pill(label: String, selected: Boolean): TextView = TextView(ctx).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(if (selected) tk.onAccent else tk.textDim)
        background = shape(if (selected) tk.accent else tk.panelSoft, 999f)
        setPadding(dp(10), dp(9), dp(10), dp(9))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(8) }
    }.keep()

    /** 主按钮（可带左侧矢量图标）。 */
    fun primary(label: String, iconRes: Int = 0): TextView = TextView(ctx).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(tk.onAccent)
        background = shape(tk.accent, 999f)
        setPadding(dp(18), dp(11), dp(18), dp(11))
        if (iconRes != 0) {
            val d = ctx.getDrawable(iconRes)?.mutate()
            d?.setTint(tk.onAccent)
            setCompoundDrawablesWithIntrinsicBounds(d, null, null, null)
            compoundDrawablePadding = dp(6)
        }
    }.keep()

    /** 次级按钮。 */
    fun soft(label: String): TextView = TextView(ctx).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(tk.accent)
        background = shape(tk.panelSoft, 999f)
        setPadding(dp(16), dp(11), dp(16), dp(11))
    }.keep()

    /** 图标按钮：用 token 的图标色着色，跟主题走。 */
    fun icon(resId: Int, sizeDp: Int = 22): ImageView = ImageView(ctx).apply {
        setImageResource(resId)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        setColorFilter(tk.icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }.keep()

    fun input(hint: String, password: Boolean = false): EditText = EditText(ctx).apply {
        this.hint = hint
        textSize = 14f
        setTextColor(tk.text)
        setHintTextColor(tk.textFaint)
        background = shape(tk.panelSoft, 12f, stroke = true)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        isSingleLine = true
        inputType = if (password) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }.keep()

    fun gap(heightDp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
    }.keep()

    /** 列表项之间的间距参数（供 Activity 统一用，别各处写 8dp）。 */
    fun itemMargin(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }

    /**
     * 主题化表单弹窗：LLM 与生图两个运营商页共用。
     *
     * 不用系统 AlertDialog 的 setView，是因为深色皮肤下它会冒出一块系统白底；
     * 这里连弹窗底板都走 ThemeTokens。
     */
    fun formDialog(
        activity: Activity,
        title: String,
        fields: List<Pair<String, EditText>>,
        positive: String,
        onOk: () -> Unit,
    ) {
        val box = card(20f, 18)
        box.addView(text(title, 17f, tk.text, bold = true))
        for ((label, input) in fields) {
            val tv = text(label, 12f, tk.textDim)
            tv.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) }
            box.addView(tv)
            box.addView(input)
        }
        val row = horizontal().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(18) }
        }
        val cancel = soft("取消")
        val ok = primary(positive)
        row.addView(cancel)
        row.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        row.addView(ok)
        box.addView(row)

        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        box.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(
            activity.resources.displayMetrics.widthPixels - dp(48),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        cancel.setOnClickListener { d.dismiss() }
        ok.setOnClickListener { onOk(); d.dismiss() }
        d.show()
    }
}

package com.dbpg.duihuazhushou.imagegen

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * 「提示词预设」在配置页里占的那一格。
 *
 * ```
 * 提示词预设（只影响生图前的标签生成）
 * [●] 给「剧情 → 标签」挂预设
 * ┌───────────────────────────────────┐
 * │ Cemini Novel En                 › │
 * │ 2/15 段 · 972 字 · 3 条正则        │
 * └───────────────────────────────────┘
 * ```
 *
 * ★ 从 v1.8 起，这套东西的"里子"全在弹窗里（[PromptPresetDialog]）：
 *   选段、开关正则、切预设、导入删除都在那儿。配置页只留一行摘要。
 *   原因是段 + 正则一展开就是二三十行，塞进配置页会把「图片大小」「风格」
 *   这些常用项挤到屏幕外面去 —— 而它们才是每次出图都要动的。
 *
 * ★ 预设是**本机自持**的（[PromptPresetStore]），不进资产页、不等引擎。
 *   v1.5/1.6 那两版去读资产页，结果是"引擎没响应 / 读不到"，用户完全没有
 *   办法自己解决 —— 一条功能不该依赖另一个页面的生命周期。
 */
class PromptPresetBar(
    private val activity: Activity,
    private val onChanged: () -> Unit = {},
) : LinearLayout(activity) {

    private val enableSwitch = Switch(activity)
    private val card = LinearLayout(activity)
    private val cardTitle = TextView(activity)
    private val cardSub = TextView(activity)

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        setPadding(0, dp(4), 0, 0)

        addView(TextView(activity).apply {
            text = "提示词预设（只影响生图前的标签生成）"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF8A94A6.toInt())
        })

        enableSwitch.apply {
            text = "给「剧情 → 标签」挂预设"
            textSize = 13f
            setTextColor(0xFF1A2230.toInt())
            setPadding(0, dp(6), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                // 程序化 setChecked 也会进来：值没变直接返回，避免自激
                if (PromptPresetStore.load(activity).usePreset == checked) {
                    return@setOnCheckedChangeListener
                }
                PromptPresetStore.setUsePreset(activity, checked)
                refresh()
                onChanged()
            }
        }
        addView(enableSwitch)

        card.apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) }
            background = cardBg()
            isClickable = true
            setOnClickListener { openSettings() }
        }
        cardTitle.apply {
            textSize = 13f
            setTextColor(0xFF1A2230.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        cardSub.apply {
            textSize = 10.5f
            setTextColor(0xFF8A94A6.toInt())
            setPadding(0, dp(3), 0, 0)
            maxLines = 2
        }
        card.addView(cardTitle)
        card.addView(cardSub)
        addView(card)

        refresh()
    }

    private fun state() = PromptPresetStore.load(activity)

    /** 重绘；插件被卸载时整条隐藏。 */
    fun refresh() {
        val active = PromptPresetPlugin.isActive(activity)
        visibility = if (active) VISIBLE else GONE
        if (!active) return

        val st = state()
        val cur = st.resolved()

        if (enableSwitch.isChecked != st.usePreset) enableSwitch.isChecked = st.usePreset

        card.alpha = if (st.usePreset) 1f else 0.5f
        cardTitle.text = when {
            st.presets.isEmpty() -> "还没有预设 —— 点这里导入"
            cur == null -> "点这里选择预设"
            else -> "${cur.name}   ›"
        }

        cardSub.text = when {
            st.presets.isEmpty() -> "酒馆预设 JSON、{name, prompt} 小配置、一段破甲文本都能导入"
            cur == null -> ""
            else -> {
                val picked = cur.pickedCount(st.jailbreakOnly)
                val chars = cur.body(st.jailbreakOnly).length
                val scope = if (st.jailbreakOnly) "破甲段" else "全部段"
                buildString {
                    append("$scope $picked/${cur.total} 段 · $chars 字")
                    if (cur.rules.isNotEmpty()) {
                        append(" · ${cur.rules.count { it.on }}/${cur.rules.size} 条正则")
                    }
                    if (!st.usePreset) append(" · 未启用")
                    if (st.jailbreakOnly && cur.jbFellBack && cur.structural) {
                        append("（没有破甲段，取全部）")
                    }
                }
            }
        }
    }

    private fun openSettings() {
        PromptPresetDialog.show(activity) {
            refresh()
            onChanged()
        }
    }

    // ------------------------------------------------------------ 小工具

    private fun cardBg() = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(0xFFF7F8FB.toInt())
        setStroke(dp(1), 0xFFE4E9F2.toInt())
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
}

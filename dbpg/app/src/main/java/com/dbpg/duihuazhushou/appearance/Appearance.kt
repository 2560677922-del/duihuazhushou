package com.dbpg.duihuazhushou.appearance

import android.content.Context

/**
 * 原生界面外观配置。
 *
 * 存 SharedPreferences，不进网页的 IndexedDB —— 因为它只影响**原生气泡**的排版，
 * 和 ST 的预设/变量/正则无关，放原生侧最直接，改完下一次 onResume 就生效。
 */
data class AppearanceConfig(
    /** 正文字号，sp */
    val textSizeSp: Float = 16.5f,
    /** 行距倍数 */
    val lineSpacing: Float = 1.4f,
    /** 气泡最大宽度，占屏宽的百分比（60~92） */
    val bubbleWidthPercent: Int = 90,
    /** 原生侧也隐藏思维链（网页快照里也有这个开关，这里优先） */
    val hideReasoning: Boolean = true,
)

object Appearance {

    private const val PREF = "dhp_appearance"
    private const val K_TEXT_SIZE = "text_size"
    private const val K_LINE = "line_spacing"
    private const val K_WIDTH = "bubble_width"
    private const val K_HIDE_REASONING = "hide_reasoning"

    fun load(ctx: Context): AppearanceConfig {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return AppearanceConfig(
            textSizeSp = p.getFloat(K_TEXT_SIZE, 16.5f).coerceIn(12f, 24f),
            lineSpacing = p.getFloat(K_LINE, 1.4f).coerceIn(1.0f, 2.0f),
            bubbleWidthPercent = p.getInt(K_WIDTH, 90).coerceIn(60, 94),
            hideReasoning = p.getBoolean(K_HIDE_REASONING, true),
        )
    }

    fun save(ctx: Context, cfg: AppearanceConfig) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat(K_TEXT_SIZE, cfg.textSizeSp)
            .putFloat(K_LINE, cfg.lineSpacing)
            .putInt(K_WIDTH, cfg.bubbleWidthPercent)
            .putBoolean(K_HIDE_REASONING, cfg.hideReasoning)
            .apply()
    }
}

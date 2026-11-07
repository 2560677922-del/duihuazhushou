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
    /**
     * 美化 HTML 消息用内嵌 WebView 真渲染（CSS 生效）。
     * 关掉则退化为纯文本（清洗后的可读版本）。
     */
    val htmlRender: Boolean = true,
    /**
     * 是否允许美化消息里的 JavaScript 执行。
     * 默认关：模型输出不可信；需要社区美化卡（状态栏/按钮）时可自己打开。
     */
    val allowScript: Boolean = false,
    /**
     * 气泡款式（见 [BubbleStyle]）。
     *
     * 存字符串而不是枚举：以后加款式只改 [BubbleStyle] 一处，
     * 老版本读到不认识的值会自动回落默认款，不会崩。
     */
    val bubbleStyle: String = BubbleStyle.ROUND.id,
    /** 聊天背景遮罩浓度 0–90：越高字越清楚、图越淡。 */
    val bgDim: Int = 55,
    /** 主题色（按钮/高亮/chip 的强调色）。存 hex 字符串，不认识回落默认蓝。 */
    val accentColor: String = "#2F66D8",
    /** 页面底色：纯色背景，也是背景图没盖到的边条颜色。 */
    val bgBaseColor: String = "#F4F6FA",
    /** 装饰风格：none / sakura（樱花）/ grass（青草）。自绘，不带图片资产。 */
    val decoration: String = "none",
)

object Appearance {

    private const val PREF = "dhp_appearance"
    private const val K_TEXT_SIZE = "text_size"
    private const val K_LINE = "line_spacing"
    private const val K_WIDTH = "bubble_width"
    private const val K_HIDE_REASONING = "hide_reasoning"
    private const val K_HTML_RENDER = "html_render"
    private const val K_ALLOW_SCRIPT = "allow_script"
    private const val K_BUBBLE_STYLE = "bubble_style"
    private const val K_BG_DIM = "bg_dim"
    private const val K_ACCENT = "accent_color"
    private const val K_BASE = "bg_base_color"
    private const val K_DECO = "decoration"

    fun load(ctx: Context): AppearanceConfig {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return AppearanceConfig(
            textSizeSp = p.getFloat(K_TEXT_SIZE, 16.5f).coerceIn(12f, 24f),
            lineSpacing = p.getFloat(K_LINE, 1.4f).coerceIn(1.0f, 2.0f),
            bubbleWidthPercent = p.getInt(K_WIDTH, 90).coerceIn(60, 94),
            hideReasoning = p.getBoolean(K_HIDE_REASONING, true),
            htmlRender = p.getBoolean(K_HTML_RENDER, true),
            allowScript = p.getBoolean(K_ALLOW_SCRIPT, false),
            bubbleStyle = BubbleStyle.byId(p.getString(K_BUBBLE_STYLE, null)).id,
            bgDim = p.getInt(K_BG_DIM, 55).coerceIn(0, 90),
            accentColor = p.getString(K_ACCENT, "#2F66D8") ?: "#2F66D8",
            bgBaseColor = p.getString(K_BASE, "#F4F6FA") ?: "#F4F6FA",
            decoration = p.getString(K_DECO, "none") ?: "none",
        )
    }

    fun save(ctx: Context, cfg: AppearanceConfig) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat(K_TEXT_SIZE, cfg.textSizeSp)
            .putFloat(K_LINE, cfg.lineSpacing)
            .putInt(K_WIDTH, cfg.bubbleWidthPercent)
            .putBoolean(K_HIDE_REASONING, cfg.hideReasoning)
            .putBoolean(K_HTML_RENDER, cfg.htmlRender)
            .putBoolean(K_ALLOW_SCRIPT, cfg.allowScript)
            .putString(K_BUBBLE_STYLE, cfg.bubbleStyle)
            .putInt(K_BG_DIM, cfg.bgDim)
            .putString(K_ACCENT, cfg.accentColor)
            .putString(K_BASE, cfg.bgBaseColor)
            .putString(K_DECO, cfg.decoration)
            .apply()
    }
}

/**
 * 气泡款式预设。
 *
 * 只描述"长什么样"（圆角、描边、配色、是否投影），渲染逻辑在
 * `chat/ChatAdapter`。加一套新款 = 在 [ALL] 里加一条 + 渲染里加个分支，
 * 不改数据层、不改设置页结构。
 */
data class BubbleStyle(
    val id: String,
    val label: String,
    /** 圆角（dp）。 */
    val cornerDp: Float,
    /** 是否有 1dp 描边。 */
    val stroke: Boolean,
    /** 是否投影（轻微 elevation）。 */
    val elevation: Boolean,
    /** 用户气泡底色（ARGB）。 */
    val userColor: Int,
    /** AI 气泡底色。 */
    val aiColor: Int,
) {
    companion object {
        val ROUND = BubbleStyle("round", "圆润", 20f, true, true, 0xFFDCE9FF.toInt(), 0xFFFFFFFF.toInt())
        val SOFT = BubbleStyle("soft", "柔和", 14f, false, false, 0xFFE8EDFF.toInt(), 0xFFF7F8FC.toInt())
        val SQUARE = BubbleStyle("square", "方角", 8f, true, false, 0xFFDCE9FF.toInt(), 0xFFFFFFFF.toInt())
        val MINIMAL = BubbleStyle("minimal", "极简", 18f, false, false, 0xFFEFF3F9.toInt(), 0xFFFBFCFE.toInt())

        val ALL = listOf(ROUND, SOFT, SQUARE, MINIMAL)

        /** 按 id 取；不认识（老数据/手改）一律回落 ROUND。 */
        fun byId(id: String?): BubbleStyle =
            ALL.firstOrNull { it.id == id } ?: ROUND
    }
}

package com.dbpg.duihuazhushou.appearance

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.dbpg.duihuazhushou.chat.ChatBackground
import kotlin.math.min
import kotlin.random.Random

/**
 * 主题系统：主题色 / 页面底色 / 装饰风格 + 全部非聊天界面的背景铺设。
 *
 * 三件事放一个文件，因为它们共用同一套"颜色从哪来"的规则：
 *   [ThemePalette]         色板与取色（都从 AppearanceConfig 读）
 *   [BackdropDrawable]     底色 + 背景图（保持原比例）+ 遮罩 + 装饰，一层画完
 *   [UiBackdrop]           统一给非聊天界面贴背景（DhpApp 生命周期里调）
 *
 * 装饰是**自绘**的（樱花花瓣 / 青草叶），不用图片资源：
 * 换风格零资产成本，颜色还能跟着主题色走。
 */
object ThemePalette {

    /** 主题色候选（accent）：按钮、高亮、chip 都染这个色。 */
    data class Swatch(val hex: String, val label: String)

    val ACCENTS = listOf(
        Swatch("#2F66D8", "默认蓝"),
        Swatch("#E75480", "樱花粉"),
        Swatch("#2E9E63", "青草绿"),
        Swatch("#E8843C", "暖橙"),
        Swatch("#7C5CFF", "暮紫"),
        Swatch("#14919B", "湖青"),
        Swatch("#D64545", "绛红"),
        Swatch("#5A6472", "石墨"),
    )

    /** 页面底色候选：一律浅色系 —— 深底会让现有深色正文看不清。 */
    val BASES = listOf(
        Swatch("#F4F6FA", "云白"),
        Swatch("#FFF5F8", "樱白"),
        Swatch("#F2FBF3", "草白"),
        Swatch("#FFF9F0", "暖白"),
        Swatch("#F5F3FF", "芋白"),
        Swatch("#F0FAFB", "湖白"),
        Swatch("#FBF7F0", "米白"),
        Swatch("#F1F3F7", "灰白"),
    )

    const val ACCENT_DEFAULT = "#2F66D8"
    const val BASE_DEFAULT = "#F4F6FA"

    /**
     * 整套配色风格：一次同时改「页面底色 + 主题色」。
     *
     * 这就是给以后留的尾巴 —— 想加"黑金""桃粉""墨蓝"这类成品皮肤，
     * 在 [STYLES] 里加一条就行，不用碰任何布局：
     * 底板/文字/描边/图标色都由 [tokensOf] 按底色明暗自动推导，
     * 所以深色皮肤不需要单独写一套深色样式（文字会自动翻白）。
     */
    data class ThemeStyle(
        val id: String,
        val label: String,
        /** 页面底色 */
        val base: String,
        /** 主题色 */
        val accent: String,
    )

    val STYLES = listOf(
        ThemeStyle("cloud", "云白", "#F4F6FA", "#2F66D8"),
        ThemeStyle("peach", "桃粉", "#FFF3F6", "#E75480"),
        ThemeStyle("gold", "黑金", "#15130F", "#C8A24A"),
        ThemeStyle("ink", "墨蓝", "#0F1620", "#6EA8FE"),
    )

    fun styleById(id: String?): ThemeStyle = STYLES.firstOrNull { it.id == id } ?: STYLES[0]

    /**
     * 把配置算成一整套颜色 token。
     *
     * 关键点：**深/浅不是开关，是底色亮度算出来的**。
     * 用户把底色选成 #15130F（黑金），dark 自动为 true，
     * 文字、描边、面板、图标全部跟着翻，不需要再维护一套深色主题。
     */
    fun tokensOf(cfg: AppearanceConfig): ThemeTokens {
        val base = parseHex(cfg.bgBaseColor, BASE_DEFAULT)
        val accent = parseHex(cfg.accentColor, ACCENT_DEFAULT)
        val dark = luminance(base) < 128
        val t = (cfg.panelOpacity.coerceIn(0, 100) / 100f)

        /*
         * 面板不透明度。
         *
         * 浅色：白面板，25%~100% —— 下限保 25%，滑到 0 也还能看出"这是张卡片"，
         *       否则文字直接糊在背景图上，读不了。
         * 深色：白 5%~18% —— 深色下"实心白卡片"会刺眼，深色设计惯例是
         *       用比底色略亮的半透明面来表示浮层，越往上越亮。
         */
        val panelAlpha = if (dark) 0.05f + 0.13f * t else 0.25f + 0.75f * t
        val panel = argbOf(panelAlpha, 0xFFFFFF)
        // 次级面板（图标底/输入框）：面板色上再掺一点主题色
        val panelOpaque = blendOver(panel, if (dark) base else 0xFFFFFF).let {
            blend(it, accent, if (dark) 0.14f else 0.07f)
        }
        val panelSoft = if (dark) panelOpaque else withAlpha(panelOpaque, 0.85f)

        return ThemeTokens(
            base = base,
            dark = dark,
            panel = panel,
            panelSoft = panelSoft,
            accent = accent,
            accentSoft = blend(panelOpaque, accent, if (dark) 0.22f else 0.13f),
            hairline = if (dark) argbOf(0.10f, 0xFFFFFF) else blend(base, accent, 0.10f),
            text = if (dark) 0xFFEDF1F7.toInt() else 0xFF1A2230.toInt(),
            textDim = if (dark) 0xFFA8B3C4.toInt() else 0xFF5A6B85.toInt(),
            textFaint = if (dark) 0xFF6C7788.toInt() else 0xFF96A0B0.toInt(),
            icon = if (dark) 0xFFA8B3C4.toInt() else 0xFF5A6B85.toInt(),
            onAccent = if (luminance(accent) > 170) 0xFF1A2230.toInt() else 0xFFFFFFFF.toInt(),
        )
    }

    fun accentHex(cfg: AppearanceConfig): String = cfg.accentColor.ifBlank { ACCENT_DEFAULT }
    fun baseHex(cfg: AppearanceConfig): String = cfg.bgBaseColor.ifBlank { BASE_DEFAULT }

    fun accent(cfg: AppearanceConfig): Int = parseHex(accentHex(cfg), ACCENT_DEFAULT)
    fun base(cfg: AppearanceConfig): Int = parseHex(baseHex(cfg), BASE_DEFAULT)

    fun parseHex(hex: String, fallback: String): Int =
        runCatching { Color.parseColor(hex) }.getOrDefault(Color.parseColor(fallback))

    fun labelOf(list: List<Swatch>, hex: String): String =
        list.firstOrNull { it.hex.equals(hex, true) }?.label ?: hex

    /** 字体型号预设（系统字体，零资产成本，跨设备都有回退）。 */
    data class FontSpec(val id: String, val label: String)

    val FONTS = listOf(
        FontSpec("", "系统默认"),
        FontSpec("sans", "无衬线"),
        FontSpec("serif", "衬线"),
        FontSpec("mono", "等宽"),
        FontSpec("black", "粗黑"),
    )

    /** id → Typeface；不认识回落系统默认。 */
    fun fontTypeface(id: String): Typeface = when (id) {
        "serif" -> Typeface.SERIF
        "mono" -> Typeface.MONOSPACE
        "black" -> Typeface.create("sans-serif-black", Typeface.NORMAL)
        // "sans" 与空串都走无衬线（系统默认本就无衬线，这里显式保证）
        else -> Typeface.SANS_SERIF
    }

    /** WebView 用的 CSS font-family（美化 HTML 也要跟着换字体）。 */
    fun fontCssFamily(id: String): String = when (id) {
        "serif" -> "serif"
        "mono" -> "monospace"
        "black" -> "'sans-serif-black', sans-serif"
        else -> "sans-serif"
    }

    /** 字体颜色：空串=自动（按页面底色明暗取对比色），否则解析 hex。 */
    fun fontColorInt(cfg: AppearanceConfig): Int {
        val hex = cfg.fontColor
        if (hex.isBlank()) {
            // 自动：底色偏亮用墨黑，偏暗用纯白，保证正文可读
            val base = base(cfg)
            val lum = (0.299f * Color.red(base) + 0.587f * Color.green(base) + 0.114f * Color.blue(base))
            return if (lum >= 140) 0xFF1A2230.toInt() else 0xFFFFFFFF.toInt()
        }
        return parseHex(hex, "#1A2230")
    }

    /** 字体颜色预设（空串=自动）。 */
    val FONT_COLORS = listOf(
        Swatch("", "自动"),
        Swatch("#1A2230", "墨黑"),
        Swatch("#5A6472", "深灰"),
        Swatch("#FFFFFF", "纯白"),
        Swatch("#6B5847", "暖棕"),
        Swatch("#7A1F3D", "酒红"),
    )
}

/**
 * 一整套界面颜色。所有原生 View 的着色都从这里取，**不再有第二个色值来源**。
 *
 * 之前的问题就是色值散在：XML drawable（#FFFFFF 卡片）、布局硬编码（#1A2230 文字）、
 * 代码里各写各的。同一个滑块当然只能带动其中一部分。
 */
data class ThemeTokens(
    val base: Int,
    val dark: Boolean,
    /** 面板（卡片/顶栏/输入栏/弹窗）底色，带 alpha —— 这才是"实心度"的落点 */
    val panel: Int,
    /** 次级面板（图标底、输入框、淡 chip） */
    val panelSoft: Int,
    val accent: Int,
    /** 主题色淡底（选中 chip、图标底） */
    val accentSoft: Int,
    /** 描边/发丝线 */
    val hairline: Int,
    val text: Int,
    val textDim: Int,
    val textFaint: Int,
    val icon: Int,
    val onAccent: Int,
)

/** 感知亮度 0..255 */
internal fun luminance(c: Int): Int =
    (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)).toInt()

/** 在 [rgb] 上套一个 0..1 的 alpha */
internal fun argbOf(alpha: Float, rgb: Int): Int =
    (rgb and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

internal fun withAlpha(c: Int, alpha: Float): Int =
    (c and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

/** 把 [fg] 按 [ratio] 混到 [bg] 上（都当不透明，忽略各自 alpha） */
internal fun blend(bg: Int, fg: Int, ratio: Float): Int {
    val r = ratio.coerceIn(0f, 1f)
    fun ch(i: Int) = (((bg shr i) and 0xFF) * (1 - r) + ((fg shr i) and 0xFF) * r).toInt()
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** 把 [fg]（带 alpha）压到不透明的 [bg] 上，得到不透明结果 */
internal fun blendOver(fg: Int, bg: Int): Int = blend(bg, fg, Color.alpha(fg) / 255f)

/**
 * 一层画完的背景 Drawable：底色 → 背景图（**保持原比例**居中）→ 遮罩 → 装饰。
 *
 * 图片不拉伸：按 `min(宽比, 高比)` 等比缩放后居中（FIT 语义），
 * 图没覆盖到的边条由底色填满 —— 底色正是为这个准备的。
 */
class BackdropDrawable(
    private val baseColor: Int,
    private val bitmap: android.graphics.Bitmap?,
    private val dim: Int,
    private val decoration: String,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val deco = DecorationDrawable(decoration)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        // 1) 底色：图没盖到的地方就是它
        paint.color = baseColor
        paint.alpha = 255
        canvas.drawRect(b, paint)

        // 2) 背景图：等比缩放居中，绝不拉长
        val bmp = bitmap
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            val scale = min(b.width().toFloat() / bmp.width, b.height().toFloat() / bmp.height)
            val w = bmp.width * scale
            val h = bmp.height * scale
            val left = b.left + (b.width() - w) / 2f
            val top = b.top + (b.height() - h) / 2f
            canvas.save()
            canvas.translate(left, top)
            canvas.scale(scale, scale)
            paint.alpha = 255
            canvas.drawBitmap(bmp, 0f, 0f, paint)
            canvas.restore()

            // 3) 遮罩：深色照片上正文才看得清（只在有图时盖）
            val a = (dim.coerceIn(0, 95) / 100f * 255).toInt()
            if (a > 0) {
                paint.color = baseColor
                paint.alpha = a
                canvas.drawRect(b, paint)
            }
        }

        // 4) 装饰最上层（花瓣/草叶本身半透明，不挡正文）
        deco.bounds = b
        deco.draw(canvas)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(cf: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 装饰层：樱花（花瓣沿对角飘落）/ 青草（底部一排草叶）。
 *
 * 全部用 Path 现画，不带图片资产；随机位置用「bounds 尺寸」做种子 ——
 * 同一尺寸每次重绘位置一致，不会每帧乱飘。
 */
class DecorationDrawable(private val style: String) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        when (style) {
            "sakura" -> drawSakura(canvas)
            "grass" -> drawGrass(canvas)
            // "none" 什么都不画
        }
    }

    /** 樱花：7 朵花 + 12 片飘落花瓣，沿右上→左下对角带散布。 */
    private fun drawSakura(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val rnd = Random(b.width() * 31L + b.height())
        val petal = 0xFFEFB9C9.toInt()   // 花瓣
        val petal2 = 0xFFF7D7E0.toInt()  // 浅花瓣
        val core = 0xFFE88BA6.toInt()    // 花蕊

        // 整朵花
        repeat(7) {
            val cx = b.left + b.width() * rnd.nextFloat()
            val cy = b.top + b.height() * rnd.nextFloat() * 0.55f  // 偏上部
            val r = (10 + rnd.nextInt(9)) * (b.width() / 900f).coerceIn(0.7f, 1.6f)
            drawFlower(canvas, cx, cy, r, if (rnd.nextBoolean()) petal else petal2, core)
        }
        // 飘落的单片花瓣
        repeat(12) {
            val cx = b.left + b.width() * rnd.nextFloat()
            val cy = b.top + b.height() * rnd.nextFloat()
            val r = (5 + rnd.nextInt(6)) * (b.width() / 900f).coerceIn(0.7f, 1.6f)
            canvas.save()
            canvas.rotate(rnd.nextFloat() * 360f, cx, cy)
            paint.color = petal
            paint.alpha = 200
            canvas.drawOval(cx - r, cy - r * 0.55f, cx + r, cy + r * 0.55f, paint)
            canvas.restore()
        }
    }

    /** 一朵五瓣花。 */
    private fun drawFlower(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, core: Int) {
        canvas.save()
        canvas.rotate((cx % 360), cx, cy)
        paint.color = color
        paint.alpha = 215
        repeat(5) { i ->
            canvas.save()
            canvas.rotate(i * 72f, cx, cy)
            canvas.drawOval(cx - r * 0.55f, cy - r * 1.25f, cx + r * 0.55f, cy - r * 0.15f, paint)
            canvas.restore()
        }
        paint.color = core
        paint.alpha = 235
        canvas.drawCircle(cx, cy, r * 0.28f, paint)
        canvas.restore()
    }

    /** 青草：底部一排草叶（贝塞尔叶尖），点缀几颗小花。 */
    private fun drawGrass(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val rnd = Random(b.width() * 17L + b.height())
        val dark = 0xFF5DAE6B.toInt()
        val light = 0xFF8FCC95.toInt()
        val flower = 0xFFFFE8F0.toInt()

        val baseY = b.bottom.toFloat()
        val n = (b.width() / 26f).toInt().coerceIn(14, 60)
        repeat(n) { i ->
            val x = b.left + b.width() * (i + rnd.nextFloat()) / n
            val h = b.height() * (0.05f + rnd.nextFloat() * 0.09f)
            val sway = (rnd.nextFloat() - 0.5f) * h * 1.2f
            val path = Path().apply {
                moveTo(x - h * 0.10f, baseY)
                quadTo(x - h * 0.05f + sway * 0.5f, baseY - h * 0.55f, x + sway, baseY - h)
                quadTo(x + h * 0.05f + sway * 0.5f, baseY - h * 0.55f, x + h * 0.10f, baseY)
                close()
            }
            paint.color = if (rnd.nextBoolean()) dark else light
            paint.alpha = 190
            canvas.drawPath(path, paint)
        }
        // 小花点
        repeat(6) {
            val x = b.left + b.width() * rnd.nextFloat()
            val y = baseY - b.height() * (0.02f + rnd.nextFloat() * 0.10f)
            paint.color = flower
            paint.alpha = 230
            canvas.drawCircle(x, y, (2.5f + rnd.nextInt(3)) * (b.width() / 900f).coerceIn(0.7f, 1.5f), paint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(cf: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 全局界面着色入口：每个 Activity 起来时调用，各页面零改动。
 *
 * 干四件事，顺序有讲究：
 *   1. 背景（底色 + 背景图 + 遮罩 + 装饰）
 *   2. 底板（卡片/按钮/顶栏/输入栏/弹窗）→ 统一换成 [ThemeTokens.panel]
 *   3. 文字与图标色（按底色明暗推导，深色底自动翻白）
 *   4. 字体型号
 *
 * 为什么全都塞进一次递归：这四件事都依赖同一个"当前是什么主题"的结论。
 * 分开写就会出现"底板已经变了我还没变"的中间态，之前那种
 * 有的实心有的空心，本质就是这个。
 */
object UiBackdrop {

    /**
     * 自己管背景的页面：这里**不铺**界面背景图。
     *
     * 聊天页：背景是"会话图"（`ChatBackground.applyTo` 设在 `chat_root` 上），
     * 铺界面图会把它盖掉。
     * 看图器：全黑沉浸。
     */
    private val NO_BACKDROP = setOf("NativeChatActivity", "ImageViewerActivity")

    /**
     * 连颜色都不染的页面：只有看图器（全黑，染色只会碍事）。
     *
     * 聊天页**要染**（顶栏/输入栏/菜单都要跟底板统一），
     * 但气泡那棵子树被打了 `dhp:keep-color`，由 ChatAdapter 保证不被覆盖。
     */
    private val NO_TINT = setOf("ImageViewerActivity")

    /**
     * 最近一次生效的配置。
     *
     * RecyclerView 的列表项是**滚动时现 inflate** 的，UiBackdrop 那次递归
     * 根本没见过它们。所以把当前配置留在外面，等新项挂上来时按它补一遍色，
     * 否则滚动出来的新卡片永远是白的 —— 这正是"有些实心有些空心"的另一半来源。
     */
    @Volatile
    private var lastCfg: AppearanceConfig? = null

    @Volatile
    private var lastBare = false

    /** 已经挂过监听器的 RecyclerView（apply 会被 onStart/onResume 多次调用，防重复） */
    private val hooked = java.util.WeakHashMap<View, Boolean>()

    fun apply(activity: Activity) {
        val cfg = Appearance.load(activity)
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) ?: content

        val tk = ThemePalette.tokensOf(cfg)
        val page = activity.javaClass.simpleName
        val bare = page in NO_TINT
        val paintBg = page !in NO_BACKDROP
        lastCfg = cfg
        lastBare = bare

        // 1) 背景：普通页面铺「界面图 + 底色 + 遮罩 + 装饰」；聊天页用自己的会话图
        if (paintBg) {
            root.background = BackdropDrawable(
                baseColor = tk.base,
                bitmap = ChatBackground.loadUi(activity),
                dim = cfg.bgDim,
                decoration = cfg.decoration,
            )
        }
        // 状态栏/导航栏在所有页面都跟着底色（聊天页顶栏也是同一套风格）
        paintSystemBars(activity, tk)

        // 2) 全界面 token 化。列表项是异步 inflate 的，补一遍（方法本身幂等）。
        val tf = ThemePalette.fontTypeface(cfg.fontFamily)
        val fontOverride = fontColorOverride(cfg)

        root.applyTokens(tk, tf, bare, fontOverride)
        root.postDelayed({ root.applyTokens(tk, tf, bare, fontOverride) }, 500L)
    }

    /** 给 RecyclerView 挂"新项进场就补色"的监听，重复调用安全。 */
    internal fun hookRecycler(rv: androidx.recyclerview.widget.RecyclerView) {
        if (hooked.containsKey(rv)) return
        hooked[rv] = true
        rv.addOnChildAttachStateChangeListener(
            object : androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(child: View) = applyToChild(child)
                override fun onChildViewDetachedFromWindow(child: View) = Unit
            },
        )
    }

    private fun applyToChild(child: View) {
        val cfg = lastCfg ?: return
        child.applyTokens(
            ThemePalette.tokensOf(cfg),
            ThemePalette.fontTypeface(cfg.fontFamily),
            lastBare,
            fontColorOverride(cfg),
        )
    }

    private fun fontColorOverride(cfg: AppearanceConfig): Int? =
        if (cfg.fontColor.isBlank()) null else ThemePalette.parseHex(cfg.fontColor, "#1A2230")

    /**
     * 状态栏/导航栏跟着底色走。
     *
     * 不跟着改的话，深色皮肤下会留一条浅色状态栏，像"戴了个白帽子"。
     * 只动 LIGHT_STATUS_BAR 这一位，不覆盖其它 systemUiVisibility 标志
     * （沉浸模式、布局标志都还在），避免把其它页面的设置冲掉。
     */
    private fun paintSystemBars(activity: Activity, tk: ThemeTokens) {
        runCatching {
            val win = activity.window ?: return
            win.statusBarColor = tk.base
            win.navigationBarColor = tk.base
            val dv = win.decorView
            var flags = dv.systemUiVisibility
            flags = if (tk.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            dv.systemUiVisibility = flags
        }
    }
}

/**
 * 递归套用一整套 token。
 *
 * 幂等：第二次调用时底板已经是 panel 色、文字已经是目标色，规则不再命中，
 * 不会越染越偏（所以可以放心用 postDelayed 补跑）。
 */
fun View.applyTokens(tk: ThemeTokens, tf: Typeface, bare: Boolean, fontOverride: Int?) {
    applyTokensInner(tk, tf, bare, fontOverride, keepFromAncestor = false)
}

private fun View.applyTokensInner(
    tk: ThemeTokens,
    tf: Typeface,
    bare: Boolean,
    fontOverride: Int?,
    keepFromAncestor: Boolean,
) {
    /*
     * tag="dhp:keep-color" 的节点**连同整棵子树**只换字体，不动颜色。
     *
     * 聊天页的气泡就是这样：顶栏/输入栏/菜单要跟底板统一，
     * 但气泡底色和文字色是 ChatAdapter 按 BubbleStyle 与「字体颜色」算的，
     * 再染一次会把用户选的"酒红"改回墨黑。
     */
    val keepColor = keepFromAncestor || tag == "dhp:keep-color"

    if (!bare && !keepColor) {
        val onAccentBg = paintPanel(tk)
        if (this is TextView) colorText(tk, onAccentBg, fontOverride)
        if (this is android.widget.ImageView) tintIcon(tk)
    }

    // 字体：所有页面都套（聊天页也要）
    if (this is TextView) {
        val style = typeface?.style ?: Typeface.NORMAL
        runCatching { typeface = Typeface.create(tf, style) }
    }

    if (this is ViewGroup) {
        // 列表要"新项进场补色"，否则滚动出来的卡片还是白的
        if (this is androidx.recyclerview.widget.RecyclerView) UiBackdrop.hookRecycler(this)
        for (i in 0 until childCount) {
            getChildAt(i).applyTokensInner(tk, tf, bare, fontOverride, keepColor)
        }
    }
}

/**
 * 把 View 的背景换成受控底板色。返回 true 表示这是"实心主题色底"，
 * 上面的文字该用 [ThemeTokens.onAccent] 而不是常规文字色。
 */
private fun View.paintPanel(tk: ThemeTokens): Boolean {
    val bg = background ?: return false
    // mutate() 必须有：XML drawable 共享 ConstantState，不 mutate 会把同款一起染色
    return retintDrawable(bg.mutate(), tk)
}

private fun retintDrawable(d: Drawable, tk: ThemeTokens): Boolean = when (d) {
    is GradientDrawable -> retintShape(d, tk)
    // 布局里直接写 android:background="#FFFFFF" 的实心块也要跟着变
    is android.graphics.drawable.ColorDrawable -> {
        val c = d.color
        val lum = luminance(c)
        val sat = maxOf(Color.red(c), Color.green(c), Color.blue(c)) -
            minOf(Color.red(c), Color.green(c), Color.blue(c))
        if (sat <= 26 && lum >= 247) {
            d.color = tk.panel
        }
        false
    }
    is android.graphics.drawable.LayerDrawable -> {
        var accentBg = false
        for (i in 0 until d.numberOfLayers) {
            val layer = d.getDrawable(i) ?: continue
            if (retintDrawable(layer, tk)) accentBg = true
        }
        accentBg
    }
    // selector（按压态）不处理：改了反而丢掉按压反馈
    else -> false
}

/**
 * 单个 shape 的重着色。
 *
 * 判据都是"亮度 + 饱和度"，不是逐个枚举 drawable 名字 ——
 * 以后新增一个白卡片不会因为忘了登记而漏掉。
 */
private fun retintShape(d: GradientDrawable, tk: ThemeTokens): Boolean {
    /*
     * 描边这里**不动**。
     *
     * GradientDrawable 只暴露 setStroke()，没有 getStrokeWidth()——
     * 读不到原宽度就没法"只换色不换粗细"，而无条件 setStroke 会给本来
     * 没有描边的 shape 硬加一圈框。所以描边保留 XML 原值（浅灰，
     * 在深色皮肤下相当于一条细亮边，观感可以接受）。
     */
    val fill = d.color?.defaultColor ?: return false
    val r = Color.red(fill)
    val g = Color.green(fill)
    val b = Color.blue(fill)
    val lum = luminance(fill)
    val sat = maxOf(r, g, b) - minOf(r, g, b)

    return when {
        // 实心主题色底（选中 chip）：换成当前主题色，白字改由 colorText 处理
        fill == 0xFF2F66D8.toInt() || fill == 0xFF6EA8FE.toInt() -> {
            d.setColor(tk.accent); true
        }
        // 纯白卡片：这是"实心/空心"的主战场
        sat <= 26 && lum >= 247 -> { d.setColor(tk.panel); false }
        // 近白（#F7F8FC / #F0F5FF 这类顶白）
        sat <= 40 && lum >= 236 -> { d.setColor(tk.panelSoft); false }
        // 淡蓝按钮底（#DCE9FF）：淡主题色
        sat > 40 && lum > 200 && b > r + 15 -> { d.setColor(tk.accentSoft); false }
        else -> false
    }
}

private fun TextView.colorText(tk: ThemeTokens, onAccentBg: Boolean, fontOverride: Int?) {
    if (onAccentBg) { setTextColor(tk.onAccent); return }
    // 用户显式指定的字体颜色优先（只影响普通文本，不影响主色按钮上的字）
    if (fontOverride != null) { setTextColor(fontOverride); return }
    val c = textColors?.defaultColor ?: return
    setTextColor(mapTextColor(c, tk))
}

/**
 * 把一个硬编码文字色映射到当前主题的对应档位。
 *
 * 按亮度分档而不是枚举色值：浅色主题下结果基本等于原色（几乎看不出变化），
 * 深色主题下整片文字自动翻白 —— 一套规则同时满足两种主题。
 */
private fun mapTextColor(c: Int, tk: ThemeTokens): Int {
    val lum = luminance(c)
    val r = Color.red(c)
    val g = Color.green(c)
    val b = Color.blue(c)
    val sat = maxOf(r, g, b) - minOf(r, g, b)

    // 语义色不改：危险红 / 成功绿
    if (sat > 60 && r > b + 30) return c
    if (sat > 40 && g > r + 20) return c
    // 蓝色系强调 → 当前主题色
    if (sat > 60 && b > r + 20 && lum > 50) return tk.accent

    return when {
        lum < 80 -> tk.text
        lum < 150 -> tk.textDim
        lum < 215 -> tk.textFaint
        else -> c // 浅色字（多半在主色实底上）保持
    }
}

/** 图标着色：只动"我们显式设成图标灰/主题蓝"的 tint，照片和彩色图不受影响。 */
private fun android.widget.ImageView.tintIcon(tk: ThemeTokens) {
    val cur = imageTintList?.defaultColor ?: return
    when (cur) {
        ICON_TINT, 0xFF1A2230.toInt() -> setImageTintList(android.content.res.ColorStateList.valueOf(tk.icon))
        ACCENT_TINT, 0xFF6EA8FE.toInt() ->
            setImageTintList(android.content.res.ColorStateList.valueOf(tk.accent))
    }
}

/** 布局里图标的默认色（与 ic_*.xml 的 strokeColor 保持一致）。 */
const val ICON_TINT = 0xFF5A6B85.toInt()
private const val ACCENT_TINT = 0xFF2F66D8.toInt()

/** 递归套字体：把每个 TextView 的字体换成 [tf]，保留它原来的粗体/斜体样式。 */
fun View.applyFontRecursive(tf: Typeface) {
    if (this is TextView) {
        val style = this.typeface?.style ?: Typeface.NORMAL
        runCatching { this.typeface = Typeface.create(tf, style) }
    }
    if (this is ViewGroup) {
        for (i in 0 until childCount) getChildAt(i).applyFontRecursive(tf)
    }
}

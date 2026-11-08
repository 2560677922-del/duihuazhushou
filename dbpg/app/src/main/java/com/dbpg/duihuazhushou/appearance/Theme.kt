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
 * 给**非聊天界面**统一贴背景（首页/设置/资产/生图/图库……）+ 全局主题色。
 *
 * 由 [com.dbpg.duihuazhushou.DhpApp] 的生命周期钩子在每个 Activity
 * 起来时调用，各页面零改动。聊天页自己管背景（会话图优先）所以不贴
 * 界面图，但主题色照样染。
 */
object UiBackdrop {

    private val noBackdrop = setOf(
        "NativeChatActivity",
        "ImageViewerActivity",
    )

    fun apply(activity: Activity) {
        val cfg = Appearance.load(activity)
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) ?: content

        // 1) 背景：非聊天界面铺「界面背景图 + 底色 + 装饰」
        if (activity.javaClass.simpleName !in noBackdrop) {
            root.background = BackdropDrawable(
                baseColor = ThemePalette.base(cfg),
                bitmap = ChatBackground.loadUi(activity),
                dim = cfg.bgDim,
                decoration = cfg.decoration,
            )
        }

        // 2) 主题色：把布局里硬编码的默认蓝染成用户色。
        //    列表项是异步 inflate 的，600ms 后再补一遍。
        val accent = ThemePalette.accent(cfg)
        if (accent != ThemePalette.parseHex(ThemePalette.ACCENT_DEFAULT, ThemePalette.ACCENT_DEFAULT)) {
            root.tintAccentRecursive(accent)
            root.postDelayed({ root.tintAccentRecursive(accent) }, 600L)
        }

        // 3) 字体型号：递归套到每个 TextView（保留原有粗体/斜体样式）。
        //    列表项异步 inflate，同样补一遍。聊天页自己也在 ChatAdapter 里套。
        val tf = ThemePalette.fontTypeface(cfg.fontFamily)
        root.applyFontRecursive(tf)
        root.postDelayed({ root.applyFontRecursive(tf) }, 600L)
    }
}

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

/** 递归上色工具：把等于默认主题色的控件染成用户主题色。 */
fun View.tintAccentRecursive(newAccent: Int) {
    for (from in DEFAULT_ACCENT_VALUES) tintOne(from, newAccent)
    if (this is ViewGroup) {
        for (i in 0 until childCount) getChildAt(i).tintAccentRecursive(newAccent)
    }
}

private val DEFAULT_ACCENT_VALUES = setOf(0xFF2F66D8.toInt(), 0xFF6EA8FE.toInt())

private fun View.tintOne(from: Int, to: Int) {
    if (this is TextView) {
        if (textColors?.defaultColor == from) setTextColor(to)
    }
    // mutate() 必须有：XML drawable 是共享实例，直接改色会把别处同款一起染了
    val bg = background?.mutate()
    if (bg is GradientDrawable) {
        // 只染纯色填充的 chip/按钮背景（描边样式不动，避免误伤）
        runCatching {
            val c = bg.color?.defaultColor ?: return@runCatching
            if (c == from) bg.setColor(to)
        }
    }
}

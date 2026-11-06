package com.dbpg.duihuazhushou.richtext

import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.widget.TextView
import com.dbpg.stcore.richtext.RichStyle
import com.dbpg.stcore.richtext.RichText

/**
 * 富文本渲染器：把 [RichText]（纯数据）映射成 SpannableString，并配置 TextView。
 *
 * 职责很单一 —— 只做「样式数据 → Span」和「TextView 排版参数」两件事，
 * 解析逻辑全部在 stcore 的 RichTextParser（已单测），这里不做任何字符串解析。
 *
 * 关于「超框 / 横向滑动」：
 *   网页端的问题是 CSS overflow 不被老 WebView 支持；
 *   原生 TextView 只要【不横向滚动】(isHorizontalScrolling = false)，
 *   再配合高质量断行，长串 / 长 URL 就会在气泡内自动折行，不可能横向溢出。
 *
 * 关于「长按复制」：
 *   用系统原生的可选中文本（setTextIsSelectable(true)），长按即可选词/全选并复制，
 *   自带系统 ActionMode，最顺滑也最符合用户习惯。
 *   代价是链接用「蓝色+下划线」做视觉提示（不额外接管点击），避免和选区手势打架。
 */
object RichTextRenderer {

    fun toSpannable(rich: RichText): CharSequence {
        val plain = rich.plain
        val sp = SpannableString(plain)
        for (span in rich.spans) {
            val start = span.start.coerceIn(0, plain.length)
            val end = span.end.coerceIn(0, plain.length)
            if (start >= end) continue
            applySpan(sp, start, end, span.style)
        }
        return sp
    }

    private fun applySpan(sp: SpannableString, start: Int, end: Int, style: RichStyle) {
        val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        when (style) {
            RichStyle.BOLD -> sp.setSpan(StyleSpan(Typeface.BOLD), start, end, flag)
            RichStyle.ITALIC -> sp.setSpan(StyleSpan(Typeface.ITALIC), start, end, flag)
            RichStyle.CODE -> {
                sp.setSpan(TypefaceSpan("monospace"), start, end, flag)
                sp.setSpan(BackgroundColorSpan(0x2280A0FF), start, end, flag)
            }
            RichStyle.STRIKE -> sp.setSpan(StrikethroughSpan(), start, end, flag)
            RichStyle.LINK -> {
                sp.setSpan(ForegroundColorSpan(0xFF76A9FF.toInt()), start, end, flag)
                sp.setSpan(UnderlineSpan(), start, end, flag)
            }
        }
    }

    /**
     * 把一段富文本贴到 TextView，并设好「自动换行 + 可长按复制」。
     * 复用 ViewHolder 时要先调用，确保上一次的文本/滚动状态被覆盖。
     */
    fun applyTo(tv: TextView, rich: RichText) {
        tv.text = toSpannable(rich)

        // 关键：绝不横向滚动，长内容只能在气泡宽度内折行
        tv.setHorizontallyScrolling(false)
        tv.setSingleLine(false)
        tv.maxLines = Int.MAX_VALUE
        tv.ellipsize = null
        // 高质量断行：对中英混排与长 URL 更友好（API 23+，本项目 minSdk 24）
        tv.breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
        tv.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL

        // 长按复制：交给系统选区（长按选词、拖拽手柄、菜单里「复制」）
        tv.setTextIsSelectable(true)
    }
}

package com.dbpg.stcore.richtext

/**
 * 原生富文本解析（纯 Kotlin，不含任何 Android 依赖，可在 JVM 上单测）。
 *
 * 输入是「网页正则处理后」的文本：可能带 Markdown 风格的极简格式
 * （**粗体** / *斜体* / `代码` / ~~删除线~~ / [文字](链接) / 裸链接），
 * 也可能夹带少量 HTML（<b> <i> <br>）。
 *
 * 输出是「纯文本 + 样式区间」：
 *   plain   —— 去掉所有标记符之后、真正要显示的文字
 *   spans   —— 每一段样式在 plain 中的 [start, end) 区间
 * Android 层只需把 spans 映射成 SpannableString 的 Span 即可，
 * 这样解析逻辑与渲染彻底解耦，也方便单测。
 *
 * 设计取舍：只做「简单格式」，不追求完整 Markdown（不做列表/表格/图片）。
 * 但一定要【稳】：解析不了的一律原样当普通文字，绝不吞字符。
 */
enum class RichStyle { BOLD, ITALIC, CODE, STRIKE, LINK }

/** 一段样式：plain 中 [start, end) 区间应用 style；LINK 时 url 非空。 */
data class RichSpan(
    val start: Int,
    val end: Int,
    val style: RichStyle,
    val url: String? = null,
)

/** 解析结果：纯文本 + 样式区间（区间已按 start 升序）。 */
data class RichText(
    val plain: String,
    val spans: List<RichSpan>,
)

object RichTextParser {

    private val MD_LINK = Regex("""\[([^\]]*)\]\(([^)\s]+)\)""")
    private val BARE_URL = Regex("""https?://[^\s<>"']+""")
    private val HTML_BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val HTML_BLOCK_END = Regex("""</(p|div|li|tr|h[1-6])>""", RegexOption.IGNORE_CASE)
    private val HTML_ANY_TAG = Regex("""</?[a-zA-Z][^>]*>""")
    private val URL_TRAILING = ".,;:!?)]}。，；：！？）】”’"

    /**
     * 解析入口。
     * @return plain 文本与样式区间。
     */
    fun parse(raw: String): RichText {
        if (raw.isEmpty()) return RichText("", emptyList())
        val src = normalizeHtml(raw)
        if (src.isEmpty()) return RichText("", emptyList())

        val plain = StringBuilder(src.length)
        val spans = ArrayList<RichSpan>()
        var i = 0
        while (i < src.length) {
            val c = src[i]

            // 代码 `xxx`（内部不再解析）
            if (c == '`') {
                val close = src.indexOf('`', i + 1)
                if (close > i + 1) {
                    val start = plain.length
                    plain.append(src, i + 1, close)
                    spans.add(RichSpan(start, plain.length, RichStyle.CODE))
                    i = close + 1
                    continue
                }
                if (close == i + 1) { // 空代码 ``，直接跳过两个反引号
                    i += 2
                    continue
                }
            }

            // 粗体 **xxx**
            if (src.startsWith("**", i)) {
                val close = src.indexOf("**", i + 2)
                if (close > i + 2) {
                    val start = plain.length
                    plain.append(src, i + 2, close)
                    spans.add(RichSpan(start, plain.length, RichStyle.BOLD))
                    i = close + 2
                    continue
                }
            }

            // 删除线 ~~xxx~~
            if (src.startsWith("~~", i)) {
                val close = src.indexOf("~~", i + 2)
                if (close > i + 2) {
                    val start = plain.length
                    plain.append(src, i + 2, close)
                    spans.add(RichSpan(start, plain.length, RichStyle.STRIKE))
                    i = close + 2
                    continue
                }
            }

            // 斜体 *xxx*（单个星号，且两侧不是空格，避免把 2 * 3 * 4 这种算式吃掉）
            if (c == '*' && !src.startsWith("**", i) && (i == 0 || src[i - 1] != '*') &&
                i + 1 < src.length && !src[i + 1].isWhitespace()
            ) {
                val close = findItalicClose(src, i + 1)
                if (close > i + 1) {
                    val start = plain.length
                    plain.append(src, i + 1, close)
                    spans.add(RichSpan(start, plain.length, RichStyle.ITALIC))
                    i = close + 1
                    continue
                }
            }

            // Markdown 链接 [文字](url)
            if (c == '[') {
                val m = MD_LINK.find(src, i)
                if (m != null && m.range.first == i) {
                    val label = m.groupValues[1]
                    val url = m.groupValues[2]
                    val start = plain.length
                    plain.append(label)
                    spans.add(RichSpan(start, plain.length, RichStyle.LINK, url))
                    i = m.range.last + 1
                    continue
                }
            }

            // 裸链接 http(s)://...
            if ((c == 'h' || c == 'H') && isUrlStart(src, i)) {
                val m = BARE_URL.find(src, i)
                if (m != null && m.range.first == i) {
                    val url = trimTrailingPunct(m.value)
                    if (url.length > 7) {
                        val start = plain.length
                        plain.append(url)
                        spans.add(RichSpan(start, plain.length, RichStyle.LINK, url))
                        i += url.length
                        continue
                    }
                }
            }

            plain.append(c)
            i++
        }

        spans.sortBy { it.start }
        return RichText(plain.toString(), spans)
    }

    // ---------------------------------------------------------------- helpers

    /** 把常见 HTML 标签归一成 Markdown 标记，其余标签直接剥掉（保留内部文字）。 */
    private fun normalizeHtml(s: String): String {
        var t = s
        t = t.replace(HTML_BR, "\n")
        t = t.replace(HTML_BLOCK_END, "\n")
        t = t.replace(Regex("""<(b|strong)>""", RegexOption.IGNORE_CASE), "**")
        t = t.replace(Regex("""</(b|strong)>""", RegexOption.IGNORE_CASE), "**")
        t = t.replace(Regex("""<(i|em)>""", RegexOption.IGNORE_CASE), "*")
        t = t.replace(Regex("""</(i|em)>""", RegexOption.IGNORE_CASE), "*")
        t = t.replace(Regex("""<code>""", RegexOption.IGNORE_CASE), "`")
        t = t.replace(Regex("""</code>""", RegexOption.IGNORE_CASE), "`")
        t = t.replace(Regex("""<(s|del|strike)>""", RegexOption.IGNORE_CASE), "~~")
        t = t.replace(Regex("""</(s|del|strike)>""", RegexOption.IGNORE_CASE), "~~")
        t = t.replace(HTML_ANY_TAG, "")
        return t
    }

    /**
     * 找斜体的闭合星号：跳过属于 ** 的星号；闭合星号前一个字符不能是空白。
     * 找不到返回 -1。
     */
    private fun findItalicClose(src: String, from: Int): Int {
        var j = from
        while (j < src.length) {
            if (src[j] == '*') {
                val isDouble = (j + 1 < src.length && src[j + 1] == '*') ||
                    (j - 1 >= 0 && src[j - 1] == '*')
                if (!isDouble && j > 0 && !src[j - 1].isWhitespace()) return j
                if (isDouble) { j += 2; continue }
            }
            j++
        }
        return -1
    }

    private fun isUrlStart(src: String, i: Int): Boolean {
        val prevOk = i == 0 || src[i - 1].isWhitespace() || src[i - 1] in "([{<'\""
        if (!prevOk) return false
        return src.startsWith("http://", i, true) || src.startsWith("https://", i, true)
    }

    private fun trimTrailingPunct(url: String): String {
        var end = url.length
        while (end > 0 && url[end - 1] in URL_TRAILING) end--
        return url.substring(0, end)
    }
}

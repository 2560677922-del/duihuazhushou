package com.dbpg.stcore.panel

/**
 * 通用交互面板解析（`<options>` / `<summary>` / `<comment>`）。
 *
 * ## 为什么是"通用"
 *
 * 这三个标签是 SillyTavern 生态的事实标准：预设提示词让模型用它们吐
 * 「选项 / 剧情摘要 / 留言」，酒馆助手（JS-Slash-Runner）的前端面板、
 * Meowssiah 的「神谕面板」认的都是同一套写法：
 *
 * ```xml
 * <options>
 *   <option>选项一</option>
 *   <option>选项二</option>
 *   也可以是不带 option 的裸行
 * </options>
 * <summary>剧情摘要</summary>
 * <comment>旁白留言</comment>
 * ```
 *
 * 以前这些标签只有在预设自带显示正则（把标签整块换成一份 HTML 皮肤）时
 * 才有 UI；没带正则的预设输出同样的标签，用户看到的就是一堆裸 XML。
 * 这里把标签解析成结构化数据，由原生弹窗渲染 —— 任何预设都能用，
 * 不绑定某一家的皮肤。
 *
 * ## 解析语义（对齐酒馆助手的 parseOptionsEntries / extractTagBlocks）
 *
 *   - 三类标签各收集所有块，块内 HTML 剥掉只留文字（script/style 整块丢）；
 *   - `<options>` 内：`<option>` 是一条选项；其余文字按行拆也是选项
 *     （预设常写"裸行选项"），行首的 `- ` / `* ` / `1. ` 等列表前缀去掉；
 *   - `<details><summary>` 是折叠块的标题，**不是**面板摘要（Meowssiah 的
 *     显示正则也专门用负向后行排除它）—— 这类 summary 连同内容留给
 *     FoldParser，面板不碰；
 *   - 标签未闭合时延续到文本末尾（内容不丢，与 FoldParser 同原则）；
 *   - 正文 = 剥掉面板标签后的剩余文本，面板与剧情互不干扰。
 *
 * 纯函数、零 Android 依赖，单测直接跑。
 */

/** 解析出来的面板内容。三个列表都空 = 没有面板。 */
data class PanelData(
    val options: List<String> = emptyList(),
    val summaries: List<String> = emptyList(),
    val comments: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = options.isEmpty() && summaries.isEmpty() && comments.isEmpty()
}

/** [rest] 是剥掉面板标签后的正文（可能为空：整条消息就是一个面板）。 */
data class PanelParse(
    val panel: PanelData?,
    val rest: String,
)

private val TAG_OPEN = Regex(
    """<(options|summary|comment)\b[^>]*>""",
    RegexOption.IGNORE_CASE,
)
private val NOISE = Regex(
    """<(script|style)\b[^>]*>[\s\S]*?</\1\s*>""",
    RegexOption.IGNORE_CASE,
)
private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val OPTION_ITEM = Regex(
    """<option\b[^>]*>([\s\S]*?)</option\s*>""",
    RegexOption.IGNORE_CASE,
)
private val ANY_TAG = Regex("""<[^>]{0,300}>""")
private val LIST_PREFIX = Regex("""^\s*(?:[-*•]\s+|\d{1,3}[.)、]\s+)""")
private val ENTITY = Regex("""&(#?\w+);""")

/**
 * 从一条 AI 回复里抽出交互面板。
 *
 * 没有任何标签时返回 `PanelParse(null, text)`，调用方无感。
 */
fun parseOptionPanel(text: String): PanelParse {
    if (!text.contains('<')) return PanelParse(null, text)

    val options = ArrayList<String>()
    val summaries = ArrayList<String>()
    val comments = ArrayList<String>()
    val rest = StringBuilder()

    var i = 0
    while (i < text.length) {
        val m = TAG_OPEN.find(text, i)
        if (m == null) {
            rest.append(text, i, text.length)
            break
        }
        val tag = m.groupValues[1].lowercase()
        if (tag == "summary" && followsDetails(text, m.range.first)) {
            // 折叠块的 <summary>：整个 details 内容留给正文（FoldParser 渲染）
            val end = findBlockEnd(text, m.range.last + 1, "summary")
            rest.append(text, i, end)
            i = end
            continue
        }
        rest.append(text, i, m.range.first)
        val body = readBlock(text, m.range.last + 1, tag)
        when (tag) {
            "options" -> options.addAll(parseOptionEntries(body.first))
            "summary" -> cleanText(body.first).takeIf { it.isNotEmpty() }?.let { summaries.add(it) }
            "comment" -> cleanText(body.first).takeIf { it.isNotEmpty() }?.let { comments.add(it) }
        }
        i = body.second
    }

    val panel = PanelData(options, summaries, comments)
    return PanelParse(
        panel = if (panel.isEmpty) null else panel,
        rest = rest.toString().trim(),
    )
}

/**
 * 读一个标签块的正文。返回（块内文本, 块结束位置）。
 *
 * 找不到闭合标签时延伸到文本末尾 —— 模型吐半截标签是常事，
 * 截断到末尾至少选项不丢。
 */
private fun readBlock(text: String, from: Int, tag: String): Pair<String, Int> {
    val end = findBlockEnd(text, from, tag)
    return text.substring(from, end) to end
}

/** 块结束位置（闭合标签之后；未闭合到末尾）。 */
private fun findBlockEnd(text: String, from: Int, tag: String): Int {
    val close = Regex("""</$tag\s*>""", RegexOption.IGNORE_CASE)
    val cm = close.find(text, from)
    return if (cm != null) cm.range.last + 1 else text.length
}

/**
 * 这个 `<summary>` 是不是 `<details>` 的第一个子元素（折叠块标题）。
 *
 * HTML 规定 summary 必须是 details 的首个子元素，所以只看紧邻的前一个
 * 开标签就够 —— 和 Meowssiah 显示正则的负向后行断言同一语义。
 */
private fun followsDetails(text: String, at: Int): Boolean {
    val head = text.substring(maxOf(0, at - 256), at).trimEnd()
    return DETAILS_TAIL.containsMatchIn(head)
}

private val DETAILS_TAIL = Regex("""<details\b[^>]*>\s*$""", RegexOption.IGNORE_CASE)

/** `<options>` 正文 → 选项列表：`<option>` 项 + 裸行。 */
private fun parseOptionEntries(body: String): List<String> {
    val out = ArrayList<String>()
    var cursor = 0
    for (m in OPTION_ITEM.findAll(body)) {
        looseLines(body.substring(cursor, m.range.first)).forEach { out.add(it) }
        cleanText(m.groupValues[1]).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        cursor = m.range.last + 1
    }
    looseLines(body.substring(cursor)).forEach { out.add(it) }
    return out
}

/** 一段文本按行拆成选项（预设的"裸行选项"写法）。 */
private fun looseLines(html: String): List<String> {
    val t = cleanText(html.replace(BR, "\n"))
    return t.lines()
        .map { LIST_PREFIX.replace(it, "").trim() }
        .filter { it.isNotEmpty() }
}

/** 剥掉 HTML 只留文字。script/style 整块丢弃，实体解码。 */
private fun cleanText(html: String): String {
    var s = NOISE.replace(html, "")
    s = BR.replace(s, "\n")
    s = ANY_TAG.replace(s, "")
    s = ENTITY.replace(s) { m ->
        when (m.groupValues[1].lowercase()) {
            "nbsp" -> " "
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "#39", "apos" -> "'"
            else -> m.value
        }
    }
    return s.trim()
}

package com.dbpg.stcore

/**
 * 文本内插图标记（对应 web imageMarker.ts）。
 * 约定：模型在正文插入 [[IMG: 画面描述]]，应用在其位置生成/插入图片。
 *
 * 这里只保留原生渲染真正需要的子集：解析标记、剥离标记、把正文切成「文本/图片」块。
 * 图片自身存储、版本管理那套（图片资产库）不在引擎层，由调用方通过 dataUrl 提供。
 */

data class NativeImage(
    val id: String,
    val dataUrl: String? = null,
    val assetId: String? = null,
    val remoteUrl: String? = null,
    val description: String = "配图",
    /** AI 插标记时记录的段落序号；用户直接配图的没有。 */
    val markdownIndex: Int? = null,
)

/** 匹配 [[IMG: 描述]] / ［［IMG: 描述］］（含全角方括号）。 */
val IMAGE_MARKER_RE: Regex = Regex("\\[\\[\\s*IMG\\s*[:：]\\s*([^\\]]+?)\\s*\\]\\]", RegexOption.IGNORE_CASE)

data class ParsedMarker(val description: String, val markdownIndex: Int, val index: Int)

fun parseImageMarkers(text: String): List<ParsedMarker> {
    val out = mutableListOf<ParsedMarker>()
    if (text.isEmpty()) return out
    val paras = mutableListOf<Pair<Int, Int>>() // (start, end) 不含边界空行
    val re = Regex("\\n\\s*\\n")
    var last = 0
    re.findAll(text).forEach { mm ->
        paras.add(last to mm.range.first)
        last = mm.range.last + 1
    }
    paras.add(last to text.length)
    val paraText = paras.map { text.substring(it.first, it.second) }
    val hasContent = paraText.map { stripImageMarkers(it).trim().isNotEmpty() }
    val renderNo = mutableListOf<Int>()
    var counter = -1
    for (i in paras.indices) {
        if (hasContent[i]) counter++
        renderNo.add(counter)
    }
    IMAGE_MARKER_RE.findAll(text).forEach { m ->
        var pi = paras.indexOfFirst { m.range.first >= it.first && m.range.first < it.second }
        if (pi < 0) pi = paras.size - 1
        var idx = renderNo[pi]
        if (!hasContent[pi]) {
            for (i in pi downTo 0) {
                if (hasContent[i]) { idx = renderNo[i]; break }
            }
            if (idx < 0) idx = 0
        }
        out.add(ParsedMarker(m.groupValues[1].trim(), maxOf(0, idx), m.range.first))
    }
    return out
}

/** 剥掉所有插图标记（必须用于要送给大模型 / 统计 token 的文本）。 */
fun stripImageMarkers(text: String): String {
    if (text.isEmpty()) return text
    return text
        .replace(IMAGE_MARKER_RE, "")
        // 内嵌 base64 图片的 markdown：整段删掉（用 [^)] 避免 O(n²) 回溯卡死）
        .replace(Regex("!\\[[^\\]]*\\]\\(\\s*data:image/[a-z+]+;base64,[^)]{100,}\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
}

fun hasImageMarker(text: String): Boolean {
    if (text.isEmpty()) return false
    return IMAGE_MARKER_RE.containsMatchIn(text)
}

sealed class ContentBlock {
    data class Text(val text: String, val index: Int) : ContentBlock()
    data class Image(val image: NativeImage?, val description: String, val index: Int, val ordinal: Int) : ContentBlock()
}

/**
 * 把正文切成「段落块」，并在标记所在位置插入图片块。
 * 与 web buildContentBlocks 对齐：按顺序配对标记与图片、兜底补末图、合并相邻文本块。
 */
fun buildContentBlocks(text: String, attachments: List<NativeImage> = emptyList()): List<ContentBlock> {
    val blocks = mutableListOf<ContentBlock>()
    if (text.isEmpty()) return blocks
    val parts = text.split(Regex("\\n\\s*\\n"))
    var index = 0
    var imageOrdinal = 0
    val used = mutableSetOf<String>()
    val queue = attachments.toMutableList()
    var consumed = 0

    fun pick(desc: String, atIndex: Int): NativeImage? {
        if (consumed >= queue.size) {
            val spare = attachments.firstOrNull { !used.contains(it.id) }
            if (spare != null) { used.add(spare.id); return spare }
            return null
        }
        while (consumed < queue.size && used.contains(queue[consumed].id)) consumed++
        val byOrder = if (consumed < queue.size) queue[consumed] else null
        if (byOrder == null) return null
        val exact = attachments.firstOrNull { !used.contains(it.id) && it.markdownIndex == atIndex && it.description == desc }
        if (exact != null) {
            used.add(exact.id)
            if (byOrder.id == exact.id) consumed++
            return exact
        }
        used.add(byOrder.id)
        consumed++
        return byOrder
    }

    for (part in parts) {
        val markers = parseImageMarkers(part)
        if (markers.isEmpty()) {
            if (part.trim().isNotEmpty()) {
                blocks.add(ContentBlock.Text(part, index))
                index++
            }
            continue
        }
        var cursor = 0
        IMAGE_MARKER_RE.findAll(part).forEach { m ->
            val before = part.substring(cursor, m.range.first)
            if (before.trim().isNotEmpty()) blocks.add(ContentBlock.Text(before, index))
            val description = m.groupValues[1].trim()
            blocks.add(ContentBlock.Image(pick(description, index), description, index, imageOrdinal++))
            cursor = m.range.last + 1
        }
        val rest = part.substring(cursor)
        if (rest.trim().isNotEmpty()) blocks.add(ContentBlock.Text(rest, index))
        index++
    }

    for (a in attachments) {
        if (used.contains(a.id)) continue
        used.add(a.id)
        blocks.add(ContentBlock.Image(a, a.description, maxOf(0, index - 1), imageOrdinal++))
    }

    // 合并相邻文本块，别把 HTML 美化产物（含空行的 <style>/<div>）切碎成可见正文。
    val merged = mutableListOf<ContentBlock>()
    for (b in blocks) {
        val last = merged.lastOrNull()
        if (b is ContentBlock.Text && last is ContentBlock.Text) {
            (merged[merged.size - 1] as ContentBlock.Text)
            // 以新对象替换，避免原列表引用问题
            merged[merged.size - 1] = ContentBlock.Text(last.text + "\n\n" + b.text, last.index)
        } else {
            merged.add(b)
        }
    }
    return merged
}

/**
 * 把美化正则产出的 HTML 变成"人话"。
 *
 * 背景：美化正则的替换串是整份 HTML（嵌套 div + span + style + script）。
 * web 有 DOM：style 生效、script 忽略、div 排成卡片。
 * 原生没有 DOM —— 只删 script/style 不够，剩下的 <div> 结构会变成
 * 一堆空行和零散文字（用户截图："平行事件**" 夹在空白块里）。
 *
 * 处理顺序：
 *   1. script/style 整块删除；
 *   2. 块级标签换成换行（div/p/details/h1~h6/li/table…），<br> 也换行；
 *   3. 剩余行内标签（span/b/i/…）直接去掉、保留文字；
 *   4. 解码常见 HTML 实体；
 *   5. 丢掉只由装饰符组成的行（**、---、~~ 之类），压缩连续空行。
 */
fun stripHtmlNoise(text: String): String {
    if (!text.contains("<") && !text.contains("&")) return text
    var out = text
    out = Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
        .replace(out, "")
    out = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE).replace(out, "\n")
    out = Regex(
        "</?(div|p|section|article|details|summary|h[1-6]|li|ul|ol|blockquote|tr|td|table|header|footer|hr)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    ).replace(out, "\n")
    out = Regex("<[^>]{0,300}>").replace(out, "")
    out = out
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
    out = out.lines()
        .map { it.trimEnd() }
        .filterNot { it.trim().matches(Regex("(\\*{1,3}|-{2,}|~{2,}|#{1,6})")) }
        .joinToString("\n")
    out = Regex("\\n{3,}").replace(out, "\n\n")
    return out.trim()
}

/**
 * 原生界面的一站式变换：剥思维链 → 跑显示正则 → 展开变量宏 → 剥图片标记 → 切块。
 * 对应 web 的 displayText + buildContentBlocks 组合。
 */
/** 显示变换结果：blocks 给纯文本渲染用，html 保留美化 HTML（给消息 WebView 用）。 */
data class DisplayResult(
    val blocks: List<ContentBlock>,
    val html: String,
)

fun transformForDisplay(
    text: String,
    attachments: List<NativeImage>,
    scripts: List<RawScript>,
    vars: VariableTables?,
    ctx: MacroContext?,
    hideReasoning: Boolean,
    reasoningCutMarker: String?,
    /** 这条消息距最新一条的深度（0 = 最新）。显示路径逐条传，对齐 web displayText 的 depthOverride。 */
    depthOverride: Int = 0,
): List<ContentBlock> = transformForDisplayRich(
    text, attachments, scripts, vars, ctx, hideReasoning, reasoningCutMarker, depthOverride,
).blocks

/**
 * 同 [transformForDisplay]，但额外保留"跑完正则/变量、尚未剥 HTML"的文本。
 *
 * 为什么保留：美化正则产出的是一整份 HTML+CSS。文本化（stripHtmlNoise）只
 * 能保证"读得懂"，排不出卡片/颜色/布局。原生聊天把这类消息交给内嵌 WebView
 * 渲染（与 Tavo 同路线），纯文本仍作兜底。
 */
fun transformForDisplayRich(
    text: String,
    attachments: List<NativeImage>,
    scripts: List<RawScript>,
    vars: VariableTables?,
    ctx: MacroContext?,
    hideReasoning: Boolean,
    reasoningCutMarker: String?,
    depthOverride: Int = 0,
): DisplayResult {
    var out = text
    if (hideReasoning) out = cleanOutput(out, reasoningCutMarker)
    if (scripts.isNotEmpty()) {
        val (msgs) = runRegex(
            listOf(ChatMessage(Role.ASSISTANT, out)),
            scripts,
            RegexEngineOptions(
                placement = RegexPlacementFilter(ai = true, reasoning = true),
                forDisplay = true,
                macroContext = ctx,
                variables = vars,
                depthOverride = depthOverride,
            ),
        )
        out = msgs.first().content
    }
    if (vars != null) out = readVariables(out, vars)
    // 保留一份"带 HTML"的版本给消息 WebView（脚本在渲染侧再剥）
    val richHtml = stripImageMarkers(out)
    // 纯文本兜底：美化正则常带 <style>/<script>，文本化时全部清掉
    val plain = stripHtmlNoise(richHtml)
    return DisplayResult(buildContentBlocks(plain, attachments), richHtml)
}

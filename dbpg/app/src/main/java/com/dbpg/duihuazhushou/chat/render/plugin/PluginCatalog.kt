package com.dbpg.duihuazhushou.chat.render.plugin

/**
 * 内置插件库（随 APK 打包）。
 *
 * ★ 这里列出的插件**永远存在**，设置页没有「删除」操作，只有
 *   「安装 / 卸载」运行时挂载。以后预设换了写法、或者社区插件换了约定，
 *   在 [ALL] 里加一条新插件、卸载旧的即可 —— 主渲染管线不用动。
 *
 * ★ 内置插件是一套**通用前端卡渲染器**，抄的是社区渲染插件的机制：
 *   - 判定：对齐「酒馆助手」`is_frontend` 与「小白X」`shouldRenderCodeBlock`
 *     —— 认 HTML 整页文档 / HTML 片段，不认某个预设的专有标签；
 *   - 渲染：对齐「酒馆助手」`createSrcContent` —— 补齐 charset / viewport /
 *     reset CSS / `--TH-viewport-height` / 横向溢出锁，把卡片包成一份
 *     **自包含、脚本可运行的隔离文档**，交给主 App 的 iframe 沙箱渲染。
 *
 *   预设把折叠窗 / 状态栏 / 选项面板写成 ` ```html ` 整页、非标准围栏、
 *   HTML 片段乃至带注释前缀，装上插件都能稳稳渲染成界面；主 App 那条
 *   WebView / iframe 通道完全复用，插件只负责"把卡片整理成能渲染的样子"。
 *
 * ★ 插件本体不碰正文、不碰正则、不碰请求体、不写全局状态。没装插件时
 *   `ChatController.decideRender` 的原有逻辑逐字节不变。
 */
object PluginCatalog {

    /**
     * 内置库只有一个「一体化」渲染器。
     *
     * ★ 为什么合并：早期按社区做法拆成「严格整页 / 宽松片段 / 面板适配」三个，
     *   但真实消息里三者会同时出现（一条消息 = 折叠栏卡片 + 正文 + 散文面板），
     *   拆开后只能整条二选一，用户还要自己判断装哪个。合并成一个后：
     *   装上即覆盖全部写法，卸载即回退，设置页也只剩一行。
     */
    val ALL: List<RenderPlugin> = listOf(FrontendCardPlugin)

    fun byId(id: String): RenderPlugin? = ALL.firstOrNull { it.descriptor.id == id }
}

// ---------------------------------------------------------------- 卡片识别

internal data class FenceMatch(val range: IntRange, val lang: String, val body: String)

/**
 * 卡片识别 + 隔离文档构造，与 `assets/msg/render.js` 的 FENCE / DOC_MARK
 * 判定保持同一套语义，保证插件产出一定能被主 App 切成 doc 段走 iframe。
 */
internal object FenceAdapter {

    private val FENCE = Regex(
        """^[ \t]*```[ \t]*([a-zA-Z0-9_-]*)[ \t]*\r?\n([\s\S]*?)^[ \t]*```[ \t]*$""",
        RegexOption.MULTILINE,
    )
    private val DOC_MARK = Regex("""<!DOCTYPE\s+html|<html\b|<head\b""", RegexOption.IGNORE_CASE)
    private val DOC_CLOSE = Regex("""</html\s*>|</head\s*>|</body\s*>""", RegexOption.IGNORE_CASE)
    private val SCRIPT = Regex("""<script\b""", RegexOption.IGNORE_CASE)
    private val HEAD_OPEN = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val HTML_OPEN = Regex("""<html\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val HAS_VIEWPORT = Regex("""<meta\s+name=["']?viewport""", RegexOption.IGNORE_CASE)

    /** 对齐小白X shouldRenderCodeBlock 的片段起始标签集合。 */
    private val FRAGMENT_START = Regex(
        """^\s*(?:<!--[\s\S]*?-->\s*)*<(?:style|link|meta|svg|iframe|canvas|img|video|audio|picture|div|section|main|article|header|footer|nav|aside|p|span|button|input|textarea|select|label|ul|ol|li|table|thead|tbody|tr|td|th|form|figure|figcaption|details|summary|dialog|h[1-6])\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 加固标记。注入到文档 head 里，既是「这份卡片已被渲染器整理过」的
     * 幂等凭据，也让设置页 / 排查时能一眼看出是谁渲染的。
     */
    private const val MARK = "dhp-render-plugin"

    private const val VIEWPORT =
        """<meta name="viewport" content="width=device-width,initial-scale=1">"""

    /**
     * 对齐 createSrcContent 的 reset 层：vh 变量、盒模型、横滚锁、媒体上限。
     * 预设自己的 CSS 仍优先（后加载者胜），这里只兜底"没写就补"。
     */
    private val GUARD =
        "<style data-dhp-plugin>" +
            ":root{--TH-viewport-height:100vh}" +
            "*,*::before,*::after{box-sizing:border-box}" +
            "html,body{margin:0;padding:0;max-width:100%;width:100%;overflow-x:hidden}" +
            "img,table,video,canvas,svg,iframe,pre{max-width:100%}" +
            "</style>"

    fun all(rich: String): List<FenceMatch> =
        FENCE.findAll(rich).map { FenceMatch(it.range, it.groupValues[1], it.groupValues[2]) }.toList()

    /** 完整 HTML 文档：有文档标记且有闭合标签。 */
    fun isFullDoc(body: String): Boolean =
        DOC_MARK.containsMatchIn(body) && DOC_CLOSE.containsMatchIn(body)

    /** HTML 片段：以常见容器标签开头，或直接带 <script>。 */
    fun isFragment(body: String): Boolean =
        FRAGMENT_START.containsMatchIn(body) || SCRIPT.containsMatchIn(body)

    /** 已被本渲染器整理过 → 幂等放行，不再二次包裹。 */
    fun isHardened(body: String): Boolean = body.contains(MARK)

    /**
     * 完整文档加固：只往 `<head>` 注入 reset / vh / 标记，**不碰 body**。
     *
     * ★ 预设模板的 `<template id="raw-data">$1</template>` 和生成折叠窗的
     *   `<script>` 全在 body 里，必须原样保留 —— 折叠窗正是靠它们跑出来的。
     *   这里绝不做转义、绝不做标签白名单，只在 head 里补样式。
     */
    fun hardenDoc(body: String, pluginId: String): String {
        if (isHardened(body)) return body
        val mark = """<meta name="$MARK" content="$pluginId">"""
        val vp = if (HAS_VIEWPORT.containsMatchIn(body)) "" else VIEWPORT
        val payload = mark + vp + GUARD
        return when {
            HEAD_OPEN.containsMatchIn(body) ->
                HEAD_OPEN.replace(body) { m -> m.value + payload }
            HTML_OPEN.containsMatchIn(body) ->
                HTML_OPEN.replace(body) { m -> m.value + "<head>" + payload + "</head>" }
            else -> toDocument(body, pluginId)
        }
    }

    /**
     * 片段 → 独立文档。补骨架、锁横向溢出、提供 `--TH-viewport-height`
     * （对齐酒馆助手的 vh 改写，避免卡片高度无限增长）。
     */
    fun toDocument(body: String, pluginId: String): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            VIEWPORT +
            """<meta name="$MARK" content="$pluginId">""" +
            GUARD +
            "</head><body><div data-render-plugin=\"$pluginId\" style=\"width:100%\">" +
            body +
            "</div></body></html>"

    /**
     * 重建围栏块，语言统一标成 html。
     *
     * ★ 必须保证闭合 ``` 独占一行：render.js 的 FENCE 要求
     *   `^[ \t]*```[ \t]*$`。产出的文档以 `</html>` 结尾、不带换行时，
     *   若直接拼上去，闭合围栏会黏在 `</html>` 后面匹配失败，整段被当
     *   Markdown 显示（实测踩过）。
     */
    fun rebuild(body: String): String {
        val b = if (body.endsWith("\n")) body else body + "\n"
        return "```html\n$b```"
    }

    /**
     * 逐个围栏改写；transform 返回 null 表示该块不动。
     * 一块都没动则返回 null（= 插件 Pass，回退内置渲染）。
     */
    fun rewrite(rich: String, transform: (FenceMatch) -> String?): String? {
        val matches = all(rich)
        if (matches.isEmpty()) return null
        val sb = StringBuilder()
        var last = 0
        var changed = false
        for (f in matches) {
            val rep = transform(f) ?: continue
            sb.append(rich, last, f.range.first)
            sb.append(rep)
            last = f.range.last + 1
            changed = true
        }
        if (!changed) return null
        sb.append(rich, last, rich.length)
        return sb.toString()
    }
}

// ---------------------------------------------------------------- 内置插件

/**
 * 早期这里有 StrictFrontendCard（只认整页文档）与 LooseFrontendCard
 * （片段也当界面）两个独立插件，靠优先级仲裁二选一。
 *
 * ★ 合并原因：真实消息里两类写法会同时出现（预设折叠栏是整页文档、模型
 *   自写的界面常是片段），拆成两个插件时用户必须猜着装哪个，装错就「没反应」。
 *   现在能力整体并入 FrontendCardPlugin 的围栏硬化分支，一个插件全覆盖。
 */

// ---------------------------------------------------------------- 一体化渲染器

/**
 * 通用前端卡渲染器（一体化）：一条消息里的所有「界面写法」都由它处理。
 *
 * 三类输入，按顺序：
 *   ① 散文面板 —— 模型没按预设约定吐标签，把「剧情小总结 + 连续选项 +
 *      结尾吐槽」写成散文。这正是「能看到一条横线、折叠窗却没出来」的成因：
 *      预设模板自带的解析只认 `<summary>` / `<comment>` / `<options>`。
 *   ② 围栏卡片（整页文档）—— 补 viewport / reset / vh / 横滚锁。
 *   ③ 围栏卡片（HTML 片段）—— 补成独立文档再交给 iframe。
 *
 * ★ 视觉部分抄的是预设自己那份 32KB 前端模板（随 APK 放在 assets/plugins/）。
 *   把散文整理成预设约定的标签，填进 `<template id="raw-data">`，折叠窗、
 *   选项按钮、状态栏、悄悄话、剧情小总结全由模板自带 JS 渲染 —— 插件不造 UI、
 *   不改模型输出、不改请求体、不改正则引擎，正文原样留在卡片之外。
 *
 * ★ 幂等：消息里已有承载面板数据的卡片（`<template id="raw-data">` /
 *   `<options>` / `<comment>`）就整体放行；输出不写回缓存，绝不二次包裹。
 */
private object FrontendCardPlugin : RenderPlugin {

    private const val ASSET = "plugins/meowssiah_panel.html"
    private const val PLACEHOLDER = "__DHP_PANEL_DATA__"

    override val descriptor = PluginDescriptor(
        id = "panel_data_adapter",
        name = "通用前端卡 · 折叠窗",
        convention = "围栏里的 HTML（整页 / 片段）+ 散文面板（剧情总结 / 连续选项行 / 结尾吐槽，分隔线可有可无）",
        description = "一个插件覆盖三类写法：① 围栏里是完整网页 → 补 viewport / reset / 横滚锁后交给 iframe；② 围栏里是 HTML 片段 → 补成独立网页再渲染；③ 模型把 summary / comment / options 写成散文 → 整理成标签填进随 APK 内置的模板，折叠窗、选项、状态栏都由模板自带脚本渲染。判据只看结构，不认预设名、不认专有标签，换预设只要还吐这几类写法就继续有效。不改正则、不改请求体、不改正文；装上才生效，卸载立即回退。",
        version = "2.0.0",
        priority = 120,
    )

    /** 行级分隔线（对单行文本做 matches，避免跨行锚点误命中卡片内部）。 */
    private val DIVIDER_LINE = Regex(
        """^[ \t]*(?:-{3,}|\*{3,}|_{3,}|<hr\b[^>]*/?>)[ \t]*$""",
    )

    /**
     * 围栏标记（``` / ~~~）。**按标记次数配对，不按「行」配对**。
     *
     * ★ 真机踩过的坑：闭合围栏可能黏在 `</html>` 后面、也可能整块被压成一行，
     *   按「行首是 ``` 」去数就只数到一个开围栏，于是它后面**所有正文都被判成
     *   围栏内**，选项行一个都扫不到 → 认领恒为 false。改为按出现次数两两配对，
     *   无论围栏独占一行还是黏在标签后面都成立。
     */
    private val FENCE_TOKEN = Regex("""(?:`{3,}|~{3,})""")

    /** 整页文档结束标记：卡片到哪里结束是确定的，之后一定是聊天正文。 */
    private val HTML_CLOSE = Regex("""</html\s*>""", RegexOption.IGNORE_CASE)

    /** 紧跟在 `</html>` 后面的闭合围栏（同一行或下一行，允许前面有空行）。 */
    private val CLOSE_FENCE = Regex("""^\s*```[^\n]*""")

    /**
     * 「这份卡片已经承载了面板数据」的通用标志。
     * 只认结构化数据容器 / 标签，不认任何预设专有 id 或 class —— 预设换模板、
     * 换写法时依旧成立。思维链折叠栏只有 `<details><summary>`，不会命中。
     */
    private val PANEL_CARD_MARK = Regex(
        """<template\s+id=["']?raw-data|<options\b|<comment\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * ★★ 真机断点的根因（本轮修正的核心）。
     *
     * 主 App `transformForDisplayRich`（`ImageMarker.kt:272`）送来的 rich 有**两种形态**：
     *   - 消息里含整页 HTML 卡片 → `isFullHtmlDocument` 命中，原样保留 `\n`；
     *   - 普通消息 → 走 `htmlifyNewlines`，每个 `\n` 变成 `<br>`，整条消息
     *     变成**一整行 HTML**，`---` 也不再独占一行。
     *
     * 旧插件只按 `\n` 分行、要求 `---` 独占一行 —— 沙盒单测喂的是原始 Markdown
     * 所以一路全绿，真机拿到 `<br>` 形态却恒为 `claim=false`。这正是「你那边
     * 复刻出来了、我这边 APK 里没出来」的原因，与气泡 / WebView / iframe 无关。
     *
     * 修法：**等长归一化**。把 `<br>` / 块级闭合标签换成 `\n` + 等长空格，
     * `<hr>` 换成 `\n---` + 等长空格。字符偏移与原始 rich 完全一致，于是
     * 「扫描用的行坐标」可以直接切原始字符串：head 保留 `<br>` 原样（正文格式
     * 不乱），tail 用归一化视图做散文拆分。两种输入、同一套判据。
     */
    private val BR_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val HR_TAG = Regex("""<hr\s*/?>""", RegexOption.IGNORE_CASE)
    private val BLOCK_END = Regex(
        """</(?:p|div|li|h[1-6]|section|tr|blockquote)\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val INLINE_TAG = Regex("""</?[a-zA-Z][a-zA-Z0-9-]*(?:\s[^>]*)?/?>""")
    private val ZERO_WIDTH = Regex("""&#8203;|&#x200b;|&ZeroWidthSpace;|\u200B""", RegexOption.IGNORE_CASE)

    private fun lineBreak(len: Int): String = "\n" + " ".repeat((len - 1).coerceAtLeast(0))

    private fun scanView(rich: String): String {
        var s = BR_TAG.replace(rich) { m -> lineBreak(m.value.length) }
        s = BLOCK_END.replace(s) { m -> lineBreak(m.value.length) }
        s = HR_TAG.replace(s) { m ->
            // `<hr>` 恰好 4 字符 → "\n---"（4 字符）；更长的写法尾部补空格保持等长
            "\n---" + " ".repeat((m.value.length - 4).coerceAtLeast(0))
        }
        return s
    }

    /** 面板区散文行：剥行内标签、解常见实体，别把 HTML 塞进 `<option>` 里显示成源码。 */
    private fun plainOf(line: String): String {
        var s = INLINE_TAG.replace(line, "")
        s = ZERO_WIDTH.replace(s, "")
        s = s.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&nbsp;", " ")
            .replace("&amp;", "&")
        return s.trim()
    }

    /** 面板区末尾的引导语，不算选项也不算正文。 */
    private val HINT_LINE = Regex("""下一步.{0,10}(怎么走|想怎么|选)|快选一个|自己写回复|请选择回应""")

    /** 元叙述段落（剧情总结）的特征词。 */
    private val SUMMARY_HINT = Regex("""本段剧情|剧情小总结|本回合|摘要|小结|详细描写了|本段发生""")

    private val NUM_LEAD = Regex("""^\d{1,2}[.)、]\s*""")

    /** 可能残留的裸标签外壳，提取纯文本时剥掉。 */
    private val TAG_SHELL = Regex(
        """</?(?:summary|comment|options|option)\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )

    private data class Panel(val options: List<String>, val summary: String, val comment: String)

    /** 保留字符偏移的行，便于「卡片外的行」与「面板区起点」精确定位。 */
    private data class Line(val start: Int, val end: Int, val text: String)

    private fun linesOf(s: String): List<Line> {
        val out = ArrayList<Line>()
        var i = 0
        while (i <= s.length) {
            val nl = s.indexOf('\n', i)
            val end = if (nl < 0) s.length else nl
            out.add(Line(i, end, s.substring(i, end)))
            if (nl < 0) break
            i = nl + 1
        }
        return out
    }

    /** 所有围栏块的范围（含标记本身），用于把卡片内容从散文扫描里屏蔽掉。 */
    private fun fenceRanges(rich: String): List<IntRange> {
        val marks = FENCE_TOKEN.findAll(rich).map { it.range }.toList()
        val out = ArrayList<IntRange>()
        var i = 0
        while (i + 1 < marks.size) {
            out.add(marks[i].first..marks[i + 1].last)
            i += 2
        }
        return out
    }

    /**
     * 把 rich 切成「卡片区」和「聊天正文」。
     *
     * ★ 定位基准是**最后一个 `</html>`**，不是围栏：
     *   整页卡片的结尾是确定的，而围栏可能被包裹、被黏住、被压成一行。
     *   没有整页文档时退化为「最后一对围栏之后」，再没有就从 0 开始。
     *
     * 返回 (正文起点, 正文)，正文起点与原始 rich 的字符偏移一致。
     */
    private fun splitTail(view: String): Pair<Int, String> {
        val closes = HTML_CLOSE.findAll(view).toList()
        if (closes.isNotEmpty()) {
            var i = closes.last().range.last + 1
            // 顺带把紧跟其后的闭合围栏一起划进卡片区，正文里就不留孤零零的 ```
            val rest = view.substring(i)
            val m = CLOSE_FENCE.find(rest)
            if (m != null && m.range.first == 0) i += m.range.last + 1
            return i to view.substring(i)
        }
        val ranges = fenceRanges(view)
        if (ranges.isNotEmpty()) {
            val i = ranges.last().last + 1
            return i to view.substring(i)
        }
        return 0 to view
    }

    /**
     * 只有「围栏卡片里已经有面板数据」才算已渲染。
     *
     * ★ 这里刻意不沿用旧版「消息里有任意 HTML 卡片就 Pass」：实测这条消息
     *   开头就有预设「思维链折叠 2.5」吐出的整页折叠栏，旧判据会让整条消息
     *   被放行，散文面板永远补不出来。折叠栏 / 状态栏与面板本就可以同条共存。
     */
    private fun hasPanelCard(rich: String): Boolean {
        val view = scanView(rich)
        return fenceRanges(view).any { r ->
            PANEL_CARD_MARK.containsMatchIn(view.substring(r.first, minOf(r.last + 1, view.length)))
        }
    }

    private data class PanelSpan(
        /** tail 起点（分隔线之后，或散文面板第一行）。 */
        val start: Int,
        /** head 终点：有分隔线时停在分隔线之前，不把那条 --- 留在正文里。 */
        val headEnd: Int,
        val optionCount: Int,
        val narrative: Boolean,
    )

    /**
     * 定位面板区起点：
     *   1. 围栏外有分隔线 → 取**最后一条**（正文里拿 --- 分段时，最后一条才是分界）；
     *   2. 一条分隔线都没有 → 靠「连续 >= 2 行选项」定位，再把紧邻其前的
     *      剧情小总结长段一并纳入，正文一行都不动。
     *
     * 两条路径都只扫围栏外的行，卡片内部的 `---` / 符号行不会误判。
     */
    private data class Run(val offset: Int, val count: Int, val index: Int)

    /** 在一组 (偏移, 文本) 里找最长的连续「选项行」。空行不算断开。 */
    private fun longestRun(items: List<Pair<Int, String>>, allowBlank: Boolean): Run? {
        var best: Run? = null
        var curStart = -1
        var curCount = 0
        var curIndex = -1
        for ((idx, item) in items.withIndex()) {
            val t = item.second.trim()
            if (t.isEmpty() && allowBlank) continue
            if (isOptionStart(t)) {
                if (curCount == 0) {
                    curStart = item.first
                    curIndex = idx
                }
                curCount++
                val b = best
                if (b == null || curCount > b.count) best = Run(curStart, curCount, curIndex)
            } else {
                curCount = 0
                curStart = -1
            }
        }
        return best
    }

    /** 句末标点切分，用于「整条正文被压成一行」时的兜底。 */
    private val SENT_END = Regex("""(?<=[。！？!?…])""")

    private fun sentencesOf(text: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var last = 0
        for (m in SENT_END.findAll(text)) {
            val end = m.range.last + 1
            if (end > last) {
                out.add(last to text.substring(last, end))
                last = end
            }
        }
        if (last < text.length) out.add(last to text.substring(last))
        return out
    }

    /**
     * 定位面板区。
     *
     * 1. 正文 = 最后一张整页卡片之后（[splitTail]），**不依赖围栏形态**；
     * 2. 在正文里找**最长的连续选项行**（空行不断开）；正文被压成一行时，
     *    退化为按句末标点切分再找一次；
     * 3. 选项块紧邻的上一条分隔线划进面板并从正文删掉（否则正文尾巴上
     *    会多出一条孤立横线）；隔了正文段落的 `---` 按原样保留，那本来就是
     *    作者用 `---` 分段。
     */
    private fun findPanelSpan(rich: String): PanelSpan? {
        val view = scanView(rich)
        val (base, tail) = splitTail(view)
        if (tail.isBlank()) return null
        val lines = linesOf(tail)

        val run = longestRun(lines.map { it.start to it.text }, allowBlank = true)
            ?: longestRun(sentencesOf(tail), allowBlank = false)
        // 首选「连续选项块」；选项之间被正文行隔开（模型常这么写）时，
        // 退化为「正文里所有选项行」—— 至少不让面板空着。
        val allOptIdx = lines.withIndex()
            .filter { isOptionStart(it.value.text) }
            .map { it.index }
        val anchor = when {
            run != null && run.count >= 2 -> run.offset
            allOptIdx.size >= 2 -> lines[allOptIdx.first()].start
            else -> return null
        }
        val optIdx = lines.indexOfFirst { it.start == anchor }

        /*
         * 面板起点：
         *   - 选项块（最长的连续选项行）；
         *   - 选项**上方紧邻**的「剧情小总结」一并纳入（只认特征词，正文段落不动）；
         *   - 再往上若是 `---` / `<hr>`，它就是面板分界线：划进面板、从正文里删掉，
         *     否则正文尾巴会多出一条孤立横线。隔了正文段落的 `---` 不动。
         */
        var panelStart = anchor
        var headEnd = anchor
        if (optIdx > 0) {
            var j = optIdx - 1
            var firstSummary = -1
            while (j >= 0) {
                val t = lines[j].text.trim()
                if (t.isEmpty()) { j--; continue }
                if (SUMMARY_HINT.containsMatchIn(t)) { firstSummary = j; j-- } else break
            }
            if (firstSummary >= 0) {
                panelStart = lines[firstSummary].start
                headEnd = panelStart
            }
            var k = (if (firstSummary >= 0) firstSummary else optIdx) - 1
            while (k >= 0 && lines[k].text.isBlank()) k--
            if (k >= 0 && DIVIDER_LINE.matches(lines[k].text.trim())) {
                panelStart = minOf(lines[k].end + 1, tail.length)
                headEnd = (lines[k].start - 1).coerceAtLeast(0)
            }
        }

        val bodyLines = linesOf(tail.substring(panelStart))
        val optionCount = bodyLines.count { isOptionStart(it.text) }
        // 防误伤：光有两行符号不算面板，还得有剧情总结 / 结尾吐槽 / 引导语
        val narrative = bodyLines.any {
            val t = it.text.trim()
            !isOptionStart(t) &&
                (SUMMARY_HINT.containsMatchIn(t) || HINT_LINE.containsMatchIn(t) || t.length > 60)
        }
        return PanelSpan(base + panelStart, base + headEnd, optionCount, narrative)
    }

    override fun claim(rich: String, meta: PluginRenderMeta): Boolean {
        // 已经有面板卡片（预设正则命中 / 本插件已产出）→ Pass，绝不重复包裹
        if (hasPanelCard(rich)) return false
        // ① 散文面板：模型没按约定吐标签
        val span = findPanelSpan(rich)
        if (span != null && span.optionCount >= 2 && span.narrative) return true
        // ② 围栏卡片：整页文档或 HTML 片段，还没被加固过
        return FenceAdapter.all(rich).any { f ->
            !FenceAdapter.isHardened(f.body) &&
                (FenceAdapter.isFullDoc(f.body) || FenceAdapter.isFragment(f.body))
        }
    }

    override fun render(rich: String, meta: PluginRenderMeta): String? {
        if (hasPanelCard(rich)) return null
        // ① 散文面板优先。head 里已有的卡片（思维链折叠栏 / 状态栏）**一字不动**：
        //    它们本来就能正常渲染，插件只在正文之后补一张模板卡片，同条共存。
        renderPanel(rich, meta)?.let { return it }
        // ② 没有面板数据 → 只做围栏卡片加固
        return hardenFences(rich)
    }

    /** 散文面板 → 内置模板卡片。读不到模板就 Pass（不硬编码兜底 UI）。 */
    private fun renderPanel(rich: String, meta: PluginRenderMeta): String? {
        val tmpl = meta.readAsset(ASSET)?.takeIf { it.contains(PLACEHOLDER) } ?: return null
        val span = findPanelSpan(rich) ?: return null
        if (span.optionCount < 2 || !span.narrative) return null
        // head 取原始 rich（保留主 App 的 <br> 排版），tail 取归一化视图（可按行拆）
        val head = rich.substring(0, span.headEnd).trimEnd()
        val p = splitPanel(scanView(rich).substring(span.start))
        if (p.options.size < 2) return null

        val tags = StringBuilder()
        if (p.summary.isNotBlank()) tags.append("<summary>").append(esc(p.summary)).append("</summary>\n")
        if (p.comment.isNotBlank()) tags.append("<comment>").append(esc(p.comment)).append("</comment>\n")
        tags.append("<options>\n")
        p.options.forEach { tags.append("<option>").append(esc(it)).append("</option>\n") }
        tags.append("</options>")

        // rebuild 统一标 ```html 并保证闭合围栏独占一行 → HtmlSanitizer 会
        // 保护文档内 <script>，render.js 会把它切成 doc 段进 iframe 沙箱。
        val card = FenceAdapter.rebuild(tmpl.replace(PLACEHOLDER, tags.toString()))
        return if (head.isBlank()) card else head + "\n\n" + card
    }

    /**
     * 围栏卡片硬化：整页补 head（viewport / reset / vh），片段包成独立文档。
     * 已硬化的块跳过 → 对插件输出再跑一次必为 null（幂等）。
     */
    private fun hardenFences(rich: String): String? =
        FenceAdapter.rewrite(rich) { f ->
            when {
                FenceAdapter.isHardened(f.body) -> null
                FenceAdapter.isFullDoc(f.body) ->
                    FenceAdapter.rebuild(FenceAdapter.hardenDoc(f.body, descriptor.id))
                FenceAdapter.isFragment(f.body) ->
                    FenceAdapter.rebuild(FenceAdapter.toDocument(f.body, descriptor.id))
                else -> null
            }
        }

    /** 设置页「渲染自检」用：把识别过程的关键数字摊开，不用再靠猜。 */
    override fun probe(rich: String): String {
        val view = scanView(rich)
        val (_, tail) = splitTail(view)
        val lines = linesOf(tail)
        val run = longestRun(lines.map { it.start to it.text }, allowBlank = true)
            ?: longestRun(sentencesOf(tail), allowBlank = false)
        val div = lines.count { DIVIDER_LINE.matches(it.text.trim()) }
        return "卡片后正文 ${lines.count { it.text.isNotBlank() }} 行 / " +
            "选项行 ${run?.count ?: 0} / 分隔线 $div / 正文长度 ${tail.length}"
    }

    /** 把面板区散文拆成「选项行 / 剧情总结 / 吐槽」，不认任何预设专有 id。 */
    private fun splitPanel(tail: String): Panel {
        val opts = ArrayList<String>()
        val summary = StringBuilder()
        val comment = StringBuilder()
        for (raw in tail.lines()) {
            val line = plainOf(raw)
            if (line.isEmpty() || HINT_LINE.containsMatchIn(line)) continue
            if (isOptionStart(line)) {
                val t = stripPrefix(line)
                if (t.length >= 2) opts.add(t)
                continue
            }
            val plain = TAG_SHELL.replace(line, "").trim()
            if (plain.isEmpty()) continue
            // 段落型的（>= 60 字 / 带总结特征词）进折叠小总结，短句进「悄悄话」
            if (plain.length >= 60 || SUMMARY_HINT.containsMatchIn(plain)) {
                if (summary.isNotEmpty()) summary.append('\n')
                summary.append(plain)
            } else {
                if (comment.isNotEmpty()) comment.append('\n')
                comment.append(plain)
            }
        }
        // 一条选项都没识别到时，退化为「按行当选项」——对齐模板自带的
        // looseOptionLinesFromHtml 兜底，至少不让面板空着。
        if (opts.isEmpty()) {
            tail.lines().map { plainOf(it) }
                .filter { it.isNotEmpty() && !HINT_LINE.containsMatchIn(it) }
                .map { stripPrefix(TAG_SHELL.replace(it, "").trim()) }
                .filter { it.length >= 2 }
                .forEach { opts.add(it) }
        }
        return Panel(opts, summary.toString(), comment.toString())
    }

    /** 行首是 emoji / 圈号 / 项目符号 / 数字编号 → 选项行。 */
    private fun isOptionStart(line: String): Boolean {
        val s = line.trimStart()
        if (s.isEmpty() || HINT_LINE.containsMatchIn(s)) return false
        val c = s[0]
        if (c.isDigit()) return NUM_LEAD.containsMatchIn(s)
        if (c.isLetter() || c in '\u4e00'..'\u9fff') return false
        // 括号 / 尖括号 / 中文引号开头的多半是引导语或标签外壳，不算选项
        if (c == '(' || c == '<' || c == '「' || c == '“' || c == '"') return false
        return true
    }

    /** 剥掉行首的编号和符号，保留选项正文。 */
    private fun stripPrefix(line: String): String {
        var s = NUM_LEAD.replace(line.trim(), "")
        while (s.isNotEmpty()) {
            val c = s[0]
            if (c.isLetterOrDigit() || c in '\u4e00'..'\u9fff' ||
                c.isWhitespace() || c == '(' || c == '<'
            ) break
            s = s.drop(1)
        }
        return s.trim()
    }

    /** 标签内容要塞进模板的 raw-data.innerHTML，特殊字符必须转义。 */
    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

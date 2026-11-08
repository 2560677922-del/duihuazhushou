package com.dbpg.duihuazhushou.chat.render

/**
 * 消息 HTML 的清洗。
 *
 * 威胁模型：消息正文来自模型输出 + 预设正则替换 —— 也就是**不可信输入**。
 * WebView 开了 JS（marked + 高度回传都要用），所以 on* 事件、
 * `javascript:` 链接、片段里的 `<script>` 都会真的执行。
 *
 * 行为：
 *   - 默认（`allowScript = false`）剥掉片段里的 `<script>` / `<iframe>` /
 *     `on*` 事件属性 / `javascript:` 链接。
 *   - **整页 HTML 文档块里的 `<script>` 一律保留**：它会被 render.js 抽进
 *     `iframe srcdoc`（sandbox 的 opaque origin），碰不到宿主 DHPBridge、
 *     读不了 file://、跳不走气泡。而 ST/Tavo 的美化卡片（选项按钮、状态栏、
 *     折叠面板）几乎全靠文档内脚本动态生成 —— 剥掉就等于"正则没生效"：
 *     卡片只剩一个空壳，选项变成裸文本。
 *   - 用户在外观设置里打开「允许美化脚本」时全部原样放行。
 *
 * 刻意不做的事：不去解析 DOM、不做完整白名单。角色卡美化用到大量自定义标签，
 * 白名单只会把正常内容一起删掉。这里只掐"能执行代码"的那几个口子，
 * 并把能执行代码的部分关进沙箱 iframe。
 */
object HtmlSanitizer {

    private val SCRIPT_BLOCK = Regex("<script\\b[^>]*>[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE)
    private val SCRIPT_TAG = Regex("<script\\b[^>]*/?>", RegexOption.IGNORE_CASE)
    private val IFRAME_BLOCK = Regex("<iframe\\b[^>]*>[\\s\\S]*?</iframe\\s*>", RegexOption.IGNORE_CASE)
    private val IFRAME_TAG = Regex("<iframe\\b[^>]*/?>", RegexOption.IGNORE_CASE)

    /** `onclick="..."` / `onerror='...'` / `onload=alert(1)` 三种写法都要吃掉。 */
    private val EVENT_ATTR = Regex(
        "\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)",
        RegexOption.IGNORE_CASE,
    )

    /** `href="javascript:..."` —— 点一下就执行，且不会被上面的规则覆盖。 */
    private val JS_URL = Regex(
        "\\s(href|src)\\s*=\\s*(\"\\s*javascript:[^\"]*\"|'\\s*javascript:[^']*')",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 与 render.js 的 segmentsOf 保持同一套判定：只有这些块会进 iframe，
     * 也就只有这些块里的脚本可以保留。保守一点没关系，多保护一段最多是
     * 让一个内联 script 留在片段里（innerHTML 插入的 script 本就不执行）。
     */
    private val FENCE = Regex(
        """(?m)^[ \t]*```[ \t]*([a-zA-Z0-9_-]*)[ \t]*\r?\n([\s\S]*?)^[ \t]*```[ \t]*$""",
    )
    private val DOC_MARK = Regex("""<!DOCTYPE\s+html|<html\b|<head\b""", RegexOption.IGNORE_CASE)
    private val DOC_START = Regex("""^\s*(?:<!DOCTYPE\s+html[^>]*>\s*)?<html\b""", RegexOption.IGNORE_CASE)
    private val DOC_CLOSE = Regex("""</html\s*>|</head\s*>|</body\s*>""", RegexOption.IGNORE_CASE)

    private const val HOLDER = "\u0000DHP_DOC_"
    private const val HOLDER_END = "\u0000"

    fun clean(html: String, allowScript: Boolean): String {
        if (allowScript) return html
        if (html.isEmpty()) return html

        /*
         * 先把整页文档块抠出来换占位符。占位符里不含 < 和引号，
         * 后面的正则不会误伤它；清洗完再原样塞回去。
         */
        val kept = ArrayList<String>()
        var work = html

        // 1) 围栏里的文档块（```html 标注 / 无标注但从头是完整文档）
        work = FENCE.replace(work) { m ->
            val lang = m.groupValues[1].lowercase()
            val body = m.groupValues[2]
            val isDoc = if (lang == "html" || lang == "htm") {
                DOC_MARK.containsMatchIn(body)
            } else {
                lang.isEmpty() && DOC_START.containsMatchIn(body) && DOC_CLOSE.containsMatchIn(body)
            }
            if (!isDoc) {
                m.value
            } else {
                val i = kept.size
                kept.add(m.value)
                "$HOLDER$i$HOLDER_END"
            }
        }

        // 2) 整条消息本身就是一份文档（无围栏）
        if (kept.isEmpty() && DOC_START.containsMatchIn(work)) {
            kept.add(work)
            work = "${HOLDER}0$HOLDER_END"
        }

        var out = work
        out = SCRIPT_BLOCK.replace(out, "")
        out = SCRIPT_TAG.replace(out, "")
        out = IFRAME_BLOCK.replace(out, "")
        out = IFRAME_TAG.replace(out, "")
        out = EVENT_ATTR.replace(out, "")
        out = JS_URL.replace(out, "")

        // 3) 还原：文档块原样回到消息里（脚本交给 iframe 沙箱）
        for ((i, original) in kept.withIndex()) {
            out = out.replace("$HOLDER$i$HOLDER_END", original)
        }
        return out
    }
}

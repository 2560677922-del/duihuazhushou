package com.dbpg.stcore

/**
 * 富文本进 WebView 前的换行处理。
 *
 * 【为什么必须有这一步】
 * 角色扮演的正文靠空行分段：一段动作、一段对白、一段内心。纯文本用
 * TextView 显示时 `\n` 天然保留；一旦交给 WebView（美化卡片），HTML 会
 * 把连续的空白**折叠成一个空格** —— 结果是整条消息挤成一大坨，读起来
 * 非常累，而且分不清哪句是对白。
 *
 * 实测（Chromium，390dp 宽）：
 *   未处理：`"你终于来了，"她的声音压得很低…` 紧接 `他看起来比记忆里更疲惫`
 *   处理后：两段之间正常断行
 *
 * 【边界】
 *   - `<style>` / `<script>` / `<pre>` 内部**原样保留**：CSS 里的换行、
 *     JS 里的行注释都依赖它，动一下就是样式失效或语法错误。
 *   - 纯空白文本节点（标签之间的缩进换行）**不动**：这些位置本来就该折叠，
 *     插 `<br>` 会凭空多出空行。
 *   - 文本节点首尾的空行去掉，只保留中间的 —— `</div>\n<div>` 这种
 *     结构换行不能变成可见空行。
 */
private val HTML_CHUNK = Regex(
    "(<script\\b[^>]*>[\\s\\S]*?</script>|<style\\b[^>]*>[\\s\\S]*?</style>" +
        "|<pre\\b[^>]*>[\\s\\S]*?</pre>|<[^>]+>)|([^<]+)",
    RegexOption.IGNORE_CASE,
)

fun htmlifyNewlines(html: String): String {
    if (html.indexOf('\n') < 0) return html
    return HTML_CHUNK.replace(html) { m ->
        val tag = m.groups[1]
        if (tag != null) return@replace tag.value // 标签 / 原样区

        val text = m.groups[2]?.value ?: return@replace m.value
        /*
         * 纯空白文本节点要看**换行个数**，不能一概不动：
         *
         *   `</div>\n<div>`   —— 一个换行，是排版缩进，必须原样（否则凭空多空行）
         *   `</span>\n\n<em>` —— 两个换行，是原文的段落空行，必须变 <br>
         *
         * 这个区分很关键：美化正则把段落用内联标签（span/em）包起来之后，
         * 段落间的空行就单独成了一个 `\n\n` 节点。之前一律跳过，
         * 结果两段挤在同一行（实测截图）。
         */
        if (text.isBlank()) {
            return@replace if (text.count { it == '\n' } >= 2) "<br>" else text
        }

        /*
         * 首尾换行的处理要区别对待，否则间距会难看：
         *
         *   开头：一律删。块级标签后面跟一个换行是排版缩进，不是空行。
         *   末尾：**一个**换行删掉（`</div>\n<div>` 的结构换行）；
         *         两个以上说明原文这里是空行分段，保留一个 —— 后面会变成
         *         一个 <br>，和内联标签（<em>/<span>）之间的段落才分得开。
         *
         * 不这么分的话，`他说完。\n\n[内心]…` 这种被内联标签截断的段落
         * 会挤成一行（实测截图就是这样）。
         */
        var t = text.replace("\r\n", "\n")
        t = t.dropWhile { it == '\n' }
        var trailing = 0
        while (t.isNotEmpty() && t.last() == '\n') {
            trailing++
            t = t.dropLast(1)
        }
        if (t.isEmpty()) return@replace ""
        if (trailing >= 2) t += "\n"
        // 连续空行压成一个：三连换行在原文里也是"一个空行"，不该变成两条空行
        t = Regex("\n{3,}").replace(t, "\n\n")

        t.split('\n').joinToString("<br>") { it.trim() }
    }
}

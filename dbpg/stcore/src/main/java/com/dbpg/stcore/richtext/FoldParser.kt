package com.dbpg.stcore.richtext

/**
 * 消息分块：把「带 <details> 的富 HTML」切成"普通段落"和"折叠栏"两类。
 *
 * 为什么要单独解析，而不是交给 RichTextParser：
 *   RichTextParser 只做行内样式（粗体/斜体/链接），它会把 `<details>` 当普通标签剥掉，
 *   结果就是标题和内容平铺在一起 —— 用户看到的「折叠栏」变成两行普通文字。
 *   ST / Tavo 里 `<details><summary>` 是**可交互**的折叠块，原生必须按块渲染。
 *
 * 为什么也不走卡片位图：
 *   卡片是离屏 WebView 画的静态图，`<details>` 默认收起而且点不动 ——
 *   内容会直接看不见。所以含折叠块的消息一律走原生视图渲染。
 *
 * 解析规则（对齐浏览器，但不求完整 HTML）：
 *   - `<details open>` → 默认展开，其余默认收起；
 *   - 没有 `<summary>` → 标题用「详情」；
 *   - 未闭合 → 一直延续到文本末尾（内容不丢，权重优先于"格式正确"）；
 *   - 嵌套 → 取最外层，内层标签在正文里由 RichTextParser 剥掉；
 *   - `<script>` / `<style>` 整块丢弃（美化正则常带，文字化会变成一堆 CSS）。
 */
sealed class MsgBlock {
    /** 原样 HTML 段（交给 RichTextParser 处理行内样式） */
    data class Text(val html: String) : MsgBlock()

    /** 折叠栏：title / body 都是 HTML 片段 */
    data class Fold(
        val title: String,
        val body: String,
        val open: Boolean,
    ) : MsgBlock()
}

object FoldParser {

    private val DETAILS_OPEN = Regex("""<details\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val DETAILS_CLOSE = Regex("""</details\s*>""", RegexOption.IGNORE_CASE)
    private val SUMMARY = Regex("""<summary\b[^>]*>([\s\S]*?)</summary\s*>""", RegexOption.IGNORE_CASE)
    private val NOISE = Regex("""<(script|style)\b[^>]*>[\s\S]*?</\1\s*>""", RegexOption.IGNORE_CASE)
    private val OPEN_ATTR = Regex("""\bopen\b""", RegexOption.IGNORE_CASE)

    /** 文本里是否真的存在折叠块（UI 用它决定走块渲染还是单 TextView）。 */
    fun hasFold(raw: String): Boolean = raw.contains("<details", ignoreCase = true)

    fun split(raw: String): List<MsgBlock> {
        if (raw.isBlank()) return emptyList()
        val opens = DETAILS_OPEN.findAll(raw).toList()
        if (opens.isEmpty()) {
            val t = clean(raw)
            return if (t.isBlank()) emptyList() else listOf(MsgBlock.Text(t))
        }
        val closes = DETAILS_CLOSE.findAll(raw).toList()

        val out = ArrayList<MsgBlock>()
        var cursor = 0
        var oi = 0
        while (oi < opens.size) {
            val open = opens[oi]
            if (open.range.first < cursor) { oi++; continue }

            // 从该 open 之后开始，按 depth 找配对的 close（支持嵌套取最外层）
            var depth = 1
            var p = oi + 1
            var q = closes.indexOfFirst { it.range.first > open.range.last }
            var closeStart = raw.length
            var bodyEnd = raw.length
            var closed = false
            while (true) {
                val nextOpen = if (p < opens.size) opens[p].range.first else Int.MAX_VALUE
                val nextClose = if (q >= 0 && q < closes.size) closes[q].range.first else Int.MAX_VALUE
                if (nextOpen == Int.MAX_VALUE && nextClose == Int.MAX_VALUE) break
                if (nextOpen < nextClose) {
                    depth++
                    p++
                } else {
                    depth--
                    if (depth == 0) {
                        closeStart = closes[q].range.first
                        bodyEnd = closes[q].range.last + 1
                        closed = true
                        break
                    }
                    q++
                }
            }

            if (open.range.first > cursor) {
                val t = clean(raw.substring(cursor, open.range.first))
                if (t.isNotBlank()) out.add(MsgBlock.Text(t))
            }

            val inner = raw.substring(open.range.last + 1, closeStart)
            val isOpen = OPEN_ATTR.containsMatchIn(open.value)
            val sum = SUMMARY.find(inner)
            // summary 必须在正文最前面（前面只允许空白），否则视为没有标题
            val isLeading = sum != null && inner.substring(0, sum.range.first).isBlank()
            val title = if (isLeading) clean(sum!!.groupValues[1]) else ""
            val body = if (isLeading) clean(inner.substring(sum!!.range.last + 1)) else clean(inner)
            out.add(
                MsgBlock.Fold(
                    title = title.ifBlank { "详情" },
                    body = body,
                    open = isOpen,
                ),
            )

            cursor = bodyEnd
            oi++
            // 跳过被这次折叠吞掉的嵌套 open
            while (oi < opens.size && opens[oi].range.first < cursor) oi++
            if (!closed) break
        }

        if (cursor < raw.length) {
            val t = clean(raw.substring(cursor))
            if (t.isNotBlank()) out.add(MsgBlock.Text(t))
        }
        return out
    }

    /** 去掉脚本/样式块，只留可显示的标记。 */
    private fun clean(s: String): String = NOISE.replace(s, "").trim()
}

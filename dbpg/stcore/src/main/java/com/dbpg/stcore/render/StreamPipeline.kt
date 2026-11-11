package com.dbpg.stcore.render

/**
 * 流式管线：只做"不会后悔"的轻量处理，保证每个片段 < 2ms。
 *
 * ★ 刻意的不做清单（每条都是踩过的坑）：
 *
 *   1. **不跑美化正则。** 正则可能跨整篇（比如整篇包一层 `<style>`/`<div>`），
 *      流式时文本只有半截，跑出来就是"半个 div"，定稿后一跑又全变，用户看到
 *      文字突然重排。SillyTavern 自己也是流式给原文、收尾再套正则。
 *
 *   2. **不做完整思维链剥离。** `stripReasoning` 要跑 10 组正则 × 全文，长回复
 *      每 80ms 跑一次就是几十毫秒。这里只做"尾部未闭合就截掉"的近似：
 *      已闭合的思考块跳过，未闭合的开标记之后全部不显示。定稿后由完整管线纠正。
 *
 *   3. **不做 HTML 清洗/图片转存。** 那些依赖完整文本，且都是 O(全文) 的重活。
 *
 * 这样流式期间只有 2~3 次线性扫描，且没有回溯风险。
 */
object StreamPipeline {

    /** 成对思考标记：只要"开"和"闭"两类，用一次扫描定位最后位置。 */
    private val THINK_OPEN = Regex(
        "<(?:thinking|think|reasoning|thought)\\b[^>]*>|\\[(?:thinking|思考|推理)\\]|<\\|(?:reasoning|thinking)\\|>",
        RegexOption.IGNORE_CASE,
    )
    private val THINK_CLOSE = Regex(
        "</(?:thinking|think|reasoning|thought)\\s*>|\\[/\\s*(?:thinking|思考|推理)\\s*\\]|<\\|/\\s*(?:reasoning|thinking)\\s*\\|>",
        RegexOption.IGNORE_CASE,
    )

    /** 流式里只要这一个：把插图标记删掉（不处理 base64 markdown，那玩意流式不会出现）。 */
    private val IMG_FAST = Regex("\\[\\[\\s*IMG\\s*[:：][^\\]]*\\]\\]", RegexOption.IGNORE_CASE)

    /**
     * @param text 累计全文（web 侧推的是全量，不是增量）
     * @param hideReasoning 用户是否开启了"隐藏思维链"
     * @param cutMarker 自定义分界（如"正文："），命中点之前算思考
     * @param streaming 是否还在生成：true 时尾部补光标 ▍
     */
    fun render(
        text: String,
        hideReasoning: Boolean,
        cutMarker: String? = null,
        streaming: Boolean = true,
    ): String {
        if (text.isEmpty()) return if (streaming) "▍" else ""

        var out = text

        if (hideReasoning) {
            // 1) 已闭合的思考块：跳到最后一个闭合标记之后
            val closes = THINK_CLOSE.findAll(out)
            var start = 0
            var lastCloseEnd = -1
            for (m in closes) lastCloseEnd = m.range.last + 1
            if (lastCloseEnd > 0) start = lastCloseEnd

            // 2) 尾部还有未闭合的开标记：截到它之前
            var end = out.length
            val open = THINK_OPEN.find(out, start)
            if (open != null) end = open.range.first

            // 3) 自定义分界（只取最后一次出现，流式里"正文："通常只出现一次）
            val cut = cutMarker?.trim().orEmpty()
            if (cut.isNotEmpty()) {
                val idx = out.lastIndexOf(cut, startIndex = (end - 1).coerceAtLeast(0))
                if (idx >= start) end = idx
            }
            out = if (start < end) out.substring(start, end) else ""
        }

        if (out.contains("[[") || out.contains("［")) {
            out = out.replace(IMG_FAST, "")
        }

        out = out.trimStart('\n')
        return if (streaming) out + "▍" else out
    }

}

package com.dbpg.stcore

/**
 * 思维链（推理过程）剥离（对应 web reasoning.ts）。
 * 推理模型会把"草稿纸"和正文一起返回，这里按标记/分界剥掉，用户不该看到那段分析。
 */

private val TAG_PAIRS: List<Pair<Regex, Regex>> = listOf(
    Regex("<thinking\\b[^>]*>", RegexOption.IGNORE_CASE) to Regex("</thinking>", RegexOption.IGNORE_CASE),
    Regex("<think\\b[^>]*>", RegexOption.IGNORE_CASE) to Regex("</think>", RegexOption.IGNORE_CASE),
    Regex("<reasoning\\b[^>]*>", RegexOption.IGNORE_CASE) to Regex("</reasoning>", RegexOption.IGNORE_CASE),
    Regex("<thought\\b[^>]*>", RegexOption.IGNORE_CASE) to Regex("</thought>", RegexOption.IGNORE_CASE),
    Regex("<\\|reasoning\\|>", RegexOption.IGNORE_CASE) to Regex("<\\|/reasoning\\|>", RegexOption.IGNORE_CASE),
    Regex("<\\|thinking\\|>", RegexOption.IGNORE_CASE) to Regex("<\\|/thinking\\|>", RegexOption.IGNORE_CASE),
    Regex("\\[thinking\\]", RegexOption.IGNORE_CASE) to Regex("\\[/thinking\\]", RegexOption.IGNORE_CASE),
    Regex("\\[思考\\]") to Regex("\\[/思考\\]"),
    Regex("\\[推理\\]") to Regex("\\[/推理\\]"),
)

// 与 web 一致：分界标记用 /gim（global+ignoreCase+multiline）。
// 缺了 MULTILINE 的话 ^…$ 只能匹配整段首尾，无法命中"正文："这种夹在中间的某一行，
// 于是"通用分界标记"整条失效。
private val BOUNDARY_OPTS = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
private val BOUNDARY_PATTERNS: List<Regex> = listOf(
    Regex("^\\s*(?:</?think(?:ing)?>|</reasoning>)\\s*$", BOUNDARY_OPTS),
    Regex("^\\s*(?:思考|推理|思维链)\\s*(?:完毕|结束|完成)\\s*[:：]?\\s*\$", BOUNDARY_OPTS),
    Regex("^\\s*-{3,}\\s*(?:正文|回复|以下是正文|以下是回复)\\s*-{0,}\\s*\$", BOUNDARY_OPTS),
    Regex("^\\s*(?:正文|回复)\\s*[:：]\\s*\$", BOUNDARY_OPTS),
)

data class ReasoningStripResult(val text: String, val stripped: Boolean, val reasoning: String)

fun stripReasoning(text: String, cutAfter: String? = null): ReasoningStripResult {
    if (text.isEmpty()) return ReasoningStripResult("", false, "")
    var out = text
    val reasoning = StringBuilder()

    // 1) 成对标记：整段（含标记）搬到 reasoning
    for ((open, close) in TAG_PAIRS) {
        val re = Regex("(${open.pattern})[\\s\\S]*?(${close.pattern})", open.options + close.options)
        out = re.replace(out) { whole ->
            reasoning.append(whole.value).append("\n")
            ""
        }
    }

    // 2) 未闭合的开标记：从它开始到结尾都算推理
    for ((open) in TAG_PAIRS) {
        val re = Regex("(${open.pattern})[\\s\\S]*\$", open.options)
        out = re.replace(out) { whole ->
            reasoning.append(whole.value)
            ""
        }
    }

    // 3) 用户自定义分界：命中点之前全部算推理
    val cut = cutAfter?.trim()
    if (!cut.isNullOrEmpty()) {
        val idx = out.lastIndexOf(cut)
        if (idx >= 0) {
            reasoning.append(out.substring(0, idx + cut.length))
            out = out.substring(idx + cut.length)
        }
    }

    // 4) 通用分界标记
    if (reasoning.isEmpty()) {
        for (p in BOUNDARY_PATTERNS) {
            val m = p.find(out)
            if (m != null && m.range.first >= 0) {
                reasoning.append(out.substring(0, m.range.first + m.value.length))
                out = out.substring(m.range.first + m.value.length)
                break
            }
        }
    }

    out = out.replace(Regex("^\\s+"), "")
    val stripped = out != text
    return ReasoningStripResult(out, stripped, reasoning.toString().trim())
}

/** 只做剥离，返回文本。用于保存/显示的主路径。 */
fun cleanOutput(text: String, cutAfter: String? = null): String = stripReasoning(text, cutAfter).text

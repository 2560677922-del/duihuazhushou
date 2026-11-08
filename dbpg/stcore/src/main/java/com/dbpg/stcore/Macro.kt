package com.dbpg.stcore

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * 宏替换（对应 web macro.ts）。
 *
 * 分三层，顺序不能乱：
 *   1. 注释宏 `{{// …}}`     —— 整段删掉（含内容），不能留给后面的规则
 *   2. 带参宏 `{{random::a,b}}` `{{pick::…}}` `{{roll::1d6}}`
 *   3. 基础宏 `{{char}}` `{{time}}` 等
 *
 * ★ 为什么带参宏要单独一趟：基础宏的正则只认 `[\w.]+`，遇到 `::` 和逗号
 *   就匹配不上，整段会原样漏到气泡里 —— 预设作者常写
 *   `{{random::好的,不好,随便}}` 来随机台词，漏出来就是一行花括号。
 *
 * ★ pick 与 random 的区别：random 每次都掷；pick 在 **同一段文本里稳定**，
 *   否则同一条消息滚动出入屏幕（缓存失效重渲染）会换一个值。
 */
private val MACRO_RE = Regex("\\{\\{\\s*([\\w.]+)\\s*\\}\\}")
private val COMMENT_RE = Regex("\\{\\{\\s*//[\\s\\S]*?\\}\\}")
private val ARG_MACRO_RE = Regex(
    "\\{\\{\\s*(random|pick)\\s*::\\s*([^{}]*?)\\s*\\}\\}",
    RegexOption.IGNORE_CASE,
)
private val ROLL_RE = Regex(
    "\\{\\{\\s*roll\\s*(?:::|:)\\s*([^{}]*?)\\s*\\}\\}",
    RegexOption.IGNORE_CASE,
)
private val TRIM_RE = Regex("\\{\\{\\s*trim\\s*\\}\\}\\s*\\n?", RegexOption.IGNORE_CASE)

private fun now(): Date = Calendar.getInstance().time

private fun fmt(pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(now())

/** `1d6` / `d20` / `2d6+1` / `2d6-1`；写法不合法返回 null（保留原文）。 */
private fun rollDice(expr: String): String? {
    val m = Regex("^\\s*(\\d*)\\s*[dD]\\s*(\\d+)\\s*([+-]\\s*\\d+)?\\s*$").find(expr) ?: return null
    val count = m.groupValues[1].toIntOrNull()?.coerceIn(1, 100) ?: 1
    val faces = m.groupValues[2].toIntOrNull()?.coerceIn(1, 1000) ?: return null
    var sum = 0
    repeat(count) { sum += Random.nextInt(1, faces + 1) }
    val mod = m.groupValues[3].replace(" ", "").toIntOrNull() ?: 0
    return (sum + mod).toString()
}

private fun pickOne(list: List<String>, seed: Int): String {
    if (list.isEmpty()) return ""
    return list[Random(seed).nextInt(list.size)]
}

/**
 * 带参宏。`seedBase` 用来让 pick 稳定：同一段文本、同一个出现序号的
 * pick 必须得到同一个结果。
 */
private fun expandArgMacros(text: String, seedBase: Int): String {
    // 先 random（每次掷），再从结果上跑 pick（稳定）
    var idx = 0
    var out = ARG_MACRO_RE.replace(text) { mr ->
        val kind = mr.groupValues[1].lowercase()
        val body = mr.groupValues[2]
        val items = body.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val i = idx++
        when {
            items.isEmpty() -> ""
            kind == "pick" -> pickOne(items, seedBase * 31 + i)
            else -> items[Random.nextInt(items.size)]
        }
    }
    idx = 0
    out = ROLL_RE.replace(out) { mr ->
        val r = rollDice(mr.groupValues[1])
        if (r == null) mr.value else r
    }
    return out
}

fun applyMacros(text: String, ctx: MacroContext): String = expandMacros(text, ctx, keepUnknown = false)

/**
 * 显示路径专用：**不认识的宏原样保留**。
 *
 * 区别很重要：组装提示词时把未知宏清空是对的（别把 `{{foo}}` 发给模型），
 * 但气泡里要是把作者写的自定义宏吃掉，用户看到的是"内容凭空少了一截"，
 * 而且没法判断是渲染坏了还是宏坏了。保留原文至少能看出问题。
 */
fun expandMacrosForDisplay(text: String, ctx: MacroContext): String =
    expandMacros(text, ctx, keepUnknown = true)

private fun expandMacros(text: String, ctx: MacroContext, keepUnknown: Boolean): String {
    if (!text.contains("{{")) return text

    // 1) 注释：整段消失
    var out = COMMENT_RE.replace(text, "")
    // 2) trim：连同它后面的换行一起吃掉，避免留下空行
    out = TRIM_RE.replace(out, "")

    val seed = out.hashCode()
    // 3) 带参宏
    out = expandArgMacros(out, seed)

    // 4) 基础宏
    return MACRO_RE.replace(out) { mr ->
        val name = mr.groupValues[1]
        val v = when (name) {
            "char" -> ctx.char
            "user" -> ctx.user
            "persona" -> ctx.persona
            "description" -> ctx.description
            "scenario" -> ctx.scenario
            "original" -> ctx.original
            "mes_example" -> ctx.mesExample
            "chat" -> ctx.chat
            "currentMessage" -> ctx.currentMessage
            "lastMessage" -> ctx.lastMessage
            "lastUserMessage" -> ctx.currentMessage ?: ctx.lastMessage
            "lastCharMessage" -> ctx.lastMessage
            "input" -> ctx.currentMessage
            "time" -> fmt("HH:mm")
            "isotime" -> fmt("HH:mm:ss")
            "date" -> fmt("yyyy-MM-dd")
            "isodate" -> fmt("yyyy-MM-dd")
            "weekday" -> fmt("EEEE")
            "newline" -> "\n"
            "space" -> " "
            "noop", "trim" -> ""
            "random" -> String.format(Locale.US, "%.2f", Random.nextDouble())
            else -> null
        }
        when {
            v != null -> v
            keepUnknown -> mr.value
            else -> ""
        }
    }
}

fun applyMacrosToMessages(msgs: List<ChatMessage>, ctx: MacroContext): List<ChatMessage> =
    msgs.map { it.copy(content = applyMacros(it.content, ctx)) }

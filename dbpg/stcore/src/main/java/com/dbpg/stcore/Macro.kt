package com.dbpg.stcore

/**
 * 宏替换（对应 web macro.ts）。
 * 规格支持 {{char}} {{user}} {{persona}} {{description}} {{scenario}} {{original}}
 * 等常用宏；不认识的宏替换成空串（注意：这与变量里的"原样保留"语义不同，
 * applyMacros 用于提示词组装/整段替换，未知宏清掉是合理的）。
 */
private val MACRO_RE = Regex("\\{\\{\\s*([\\w.]+)\\s*\\}\\}")

fun applyMacros(text: String, ctx: MacroContext): String {
    if (!text.contains("{{")) return text
    return MACRO_RE.replace(text) { mr ->
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
            else -> null
        }
        if (v == null) "" else v
    }
}

fun applyMacrosToMessages(msgs: List<ChatMessage>, ctx: MacroContext): List<ChatMessage> =
    msgs.map { it.copy(content = applyMacros(it.content, ctx)) }

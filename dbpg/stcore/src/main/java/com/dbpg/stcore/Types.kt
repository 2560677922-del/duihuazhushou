package com.dbpg.stcore

/**
 * ST 引擎共享类型。与 web 工程的 engine/types.ts 对齐，但去掉 Vue 依赖。
 */

enum class Role { SYSTEM, USER, ASSISTANT }

data class ChatMessage(
    val role: Role,
    var content: String,
    val promptId: String? = null,
    val name: String? = null,
    /** 消息来源，正则引擎靠它区分作用范围（placement）。 */
    val origin: MessageOrigin? = null,
    val index: Int? = null,
)

enum class MessageOrigin { PRESET, WORLDBOOK, HISTORY, AUTHOR_NOTE, REASONING, SLASH }

data class RegexHit(
    val id: String,
    val scriptName: String,
    val placement: List<Int>,
    val findRegex: String,
    val replaceString: String,
    val matchCount: Int,
    val sample: RegexSample?,
)

data class RegexSample(val before: String, val after: String)

/** 变量表：session / global 两张平级表（与 web variables.ts 一致）。 */
data class VariableTables(
    val session: MutableMap<String, String> = mutableMapOf(),
    val global: MutableMap<String, String> = mutableMapOf(),
)

/** 宏替换上下文（与 web macro.ts 一致）。 */
data class MacroContext(
    val char: String? = null,
    val user: String? = null,
    val persona: String? = null,
    val description: String? = null,
    val scenario: String? = null,
    val original: String? = null,
    val mesExample: String? = null,
    val chat: String? = null,
    val currentMessage: String? = null,
    val lastMessage: String? = null,
)

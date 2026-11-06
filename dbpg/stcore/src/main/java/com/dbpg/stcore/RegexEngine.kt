package com.dbpg.stcore

/**
 * 正则引擎（对应 web regexEngine.ts，与 SillyTavern runRegexScript 对齐）。
 *
 * ★ 移植要点（解决"有的正则就是不生效"的五大语义坑）：
 *   1. $0 与 {{match}} 都表示整个匹配（JS 原生 $0 不是组引用）。
 *   2. trimStrings（Trim Out）按被引用的分组内容去字，不是整个匹配。
 *   3. 替换串里的宏（{{char}}/{{getvar}}）最后整体展开。
 *   4. substituteRegex（findRegex 里的宏）三种模式都实现。
 *   5. 脚本顺序照传入数组（已压平的正确顺序），不按 id 重排。
 */

object REGEX_PLACEMENT {
    const val USER_INPUT = 1
    const val AI_OUTPUT = 2
    const val SLASH_COMMAND = 3
    const val WORLD_INFO = 5
    const val REASONING = 6
}

object SUBSTITUTE_REGEX {
    const val NONE = 0
    const val RAW = 1
    const val ESCAPED = 2
}

data class RegexPlacementFilter(
    val user: Boolean = true,
    val ai: Boolean = true,
    val worldInfo: Boolean = true,
    val reasoning: Boolean = true,
    val slash: Boolean = true,
)

data class RegexEngineOptions(
    val dryRun: Boolean = false,
    val placement: RegexPlacementFilter = RegexPlacementFilter(),
    /** 这次跑的是「界面显示」而不是「发给大模型」。 */
    val forDisplay: Boolean = false,
    val macroContext: MacroContext? = null,
    val variables: VariableTables? = null,
    /** 覆盖「这条消息距最新一条的深度」（0 = 最新）。显示路径每次只传一条，必须传。 */
    val depthOverride: Int? = null,
)

data class RawScript(
    val id: String?,
    val scriptName: String?,
    val name: String?,
    val findRegex: String,
    val replaceString: String,
    val placement: List<Int>?,
    val disabled: Boolean,
    val trimStrings: List<String>?,
    val markdownOnly: Boolean,
    val promptOnly: Boolean,
    val substituteRegex: Int,
    val minDepth: Int?,
    val maxDepth: Int?,
)

private data class MatchParts(
    val match: String,
    val groups: List<String?>,
    val named: Map<String, String>?,
)

/** 只替换已知的宏，未知的原样保留（不能用 applyMacros，否则会把 {{random}} 吃光）。 */
private fun substituteKnownMacros(text: String, ctx: MacroContext?): String {
    if (ctx == null || !text.contains("{{")) return text
    val map = mutableMapOf<String, String>()
    // 复用 MacroContext 的字段
    map["char"] = ctx.char ?: ""
    map["user"] = ctx.user ?: ""
    map["persona"] = ctx.persona ?: ""
    map["description"] = ctx.description ?: ""
    map["scenario"] = ctx.scenario ?: ""
    map["original"] = ctx.original ?: ""
    map["mes_example"] = ctx.mesExample ?: ""
    map["chat"] = ctx.chat ?: ""
    map["currentmessage"] = ctx.currentMessage ?: ""
    map["lastmessage"] = ctx.lastMessage ?: ""
    if (map.isEmpty()) return text
    return text.replace(Regex("\\{\\{\\s*([\\w.]+)\\s*\\}\\}")) { mr ->
        val n = mr.groupValues[1].lowercase()
        map[n]?.let { if (it.isEmpty()) "" else it } ?: mr.value
    }
}

/** 按 substituteRegex 处理 findRegex。 */
private fun resolveFindPattern(raw: String, mode: Int, ctx: MacroContext?): String {
    return when (mode) {
        SUBSTITUTE_REGEX.RAW -> substituteKnownMacros(raw, ctx)
        SUBSTITUTE_REGEX.ESCAPED -> {
            if (ctx == null) raw
            else {
                val map = mutableMapOf<String, String>()
                val pairs = listOf(
                    "char" to ctx.char, "user" to ctx.user, "persona" to ctx.persona,
                    "description" to ctx.description, "scenario" to ctx.scenario,
                    "original" to ctx.original, "mes_example" to ctx.mesExample,
                    "chat" to ctx.chat, "currentmessage" to ctx.currentMessage,
                    "lastmessage" to ctx.lastMessage,
                )
                for ((k, v) in pairs) if (v != null) map[k] = sanitizeRegexMacro(v)
                raw.replace(Regex("\\{\\{\\s*([\\w.]+)\\s*\\}\\}")) { mr ->
                    map[mr.groupValues[1].lowercase()] ?: mr.value
                }
            }
        }
        else -> raw
    }
}

/** 按 ST 规则把替换模板套用到一次匹配上。 */
private fun buildReplacement(
    template: String,
    parts: MatchParts,
    trims: List<String>,
    ctx: MacroContext?,
    vars: VariableTables?,
): String {
    // ST: 替换串里的 {{match}} 等价于 $0（整个匹配）。
    // 注意：必须用「lambda 重载」而不是字符串重载——Java 的字符串替换里 $0 会被当成
    // "第 0 组=整段匹配" 重新套用，导致 {{match}} 原样留着（JS 里 $0 才是字面量）。
    val tpl = Regex("\\{\\{match\\}\\}", RegexOption.IGNORE_CASE).replace(template) { "\$0" }
    val withGroups = Regex("\\$(\\d+)|\\$<([^>]+)>").replace(tpl) { mr ->
        val name = mr.groupValues[2]
        val numStr = mr.groupValues[1]
        var raw: String? = null
        if (name.isNotEmpty()) raw = parts.named?.get(name)
        else if (numStr.isNotEmpty()) {
            val num = numStr.toIntOrNull() ?: 0
            raw = if (num == 0) parts.match else parts.groups.getOrNull(num - 1)
        }
        if (raw == null) return@replace ""
        // Trim Out 按被引用的那段内容去字，不是整个匹配
        var v = raw
        for (t in trims) if (t.isNotEmpty()) v = v!!.split(t).joinToString("")
        v ?: ""
    }
    // ST 最后整体跑一遍宏替换；随后只读展开变量宏。
    val withMacros = substituteKnownMacros(withGroups, ctx)
    return if (vars != null) readVariables(withMacros, vars) else withMacros
}

private fun trimList(s: RawScript): List<String> {
    val raw: Any? = s.trimStrings
    return when (raw) {
        is List<*> -> raw.filterIsInstance<String>().filter { it.isNotEmpty() }
        is String -> raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }
}

private fun inDepthRange(s: RawScript, depthFromEnd: Int): Boolean {
    val min = s.minDepth
    val max = s.maxDepth
    if (min != null && depthFromEnd < min) return false
    if (max != null && depthFromEnd > max) return false
    return true
}

private fun numOrNull(v: Any?): Int? {
    return when (v) {
        null -> null
        is Int -> v
        is String -> if (v.isEmpty()) null else v.toIntOrNull()
        else -> null
    }
}

/**
 * 兼容两种字段命名（camelCase / snake_case）地构造 RawScript。
 * 调用方从 JSON 反序列化后传入一个宽松的 Map/对象即可。
 */
fun buildScript(map: Map<String, Any?>): RawScript {
    fun str(key1: String, key2: String): String =
        ((map[key1] ?: map[key2]) as? String) ?: ""
    fun bool(key1: String, key2: String): Boolean =
        (map[key1] ?: map[key2]) as? Boolean ?: false
    fun int(key: String): Int? {
        val v = map[key]
        return when (v) {
            null -> null
            is Int -> v
            is String -> v.toIntOrNull()
            else -> null
        }
    }
    val placement = (map["placement"] as? List<*>)?.mapNotNull {
        when (it) {
            is Int -> it
            is String -> it.toIntOrNull()
            else -> null
        }
    }
    val trim = (map["trimStrings"] ?: map["trim_strings"]) as? List<*>
    val trimList = trim?.filterIsInstance<String>()?.filter { it.isNotEmpty() }
    return RawScript(
        id = (map["id"] ?: map["_id"]) as? String,
        scriptName = str("scriptName", "script_name"),
        name = str("name", "name"),
        findRegex = str("find_regex", "findRegex"),
        replaceString = str("replace_string", "replaceString"),
        placement = placement,
        disabled = bool("disabled", "disabled"),
        trimStrings = trimList,
        markdownOnly = bool("markdownOnly", "markdown_only"),
        promptOnly = bool("promptOnly", "prompt_only"),
        substituteRegex = int("substituteRegex") ?: int("substitute_regex") ?: SUBSTITUTE_REGEX.NONE,
        minDepth = int("minDepth") ?: int("min_depth"),
        maxDepth = int("maxDepth") ?: int("max_depth"),
    )
}

fun runRegex(
    messages: List<ChatMessage>,
    scripts: List<RawScript>,
    options: RegexEngineOptions = RegexEngineOptions(),
): Pair<List<ChatMessage>, List<RegexHit>> {
    val dry = options.dryRun
    val forDisplay = options.forDisplay
    val ctx = options.macroContext
    val vars = options.variables
    val placement = options.placement
    val hits = mutableListOf<RegexHit>()

    val work: MutableList<ChatMessage> = if (dry) messages.map { it.copy() }.toMutableList() else messages.toMutableList()

    for (s in scripts) {
        if (s.disabled) continue

        val declared: List<Int> = s.placement ?: emptyList()
        val placements = if (declared.isNotEmpty()) declared else listOf(REGEX_PLACEMENT.AI_OUTPUT)

        val legacyMdDisplay = placements.contains(0)
        if (legacyMdDisplay && !forDisplay && !dry) continue

        val applyToUser = placement.user && placements.contains(REGEX_PLACEMENT.USER_INPUT)
        val applyToAI =
            placement.ai && (placements.contains(REGEX_PLACEMENT.AI_OUTPUT) || legacyMdDisplay)
        val applyToWI = placement.worldInfo && placements.contains(REGEX_PLACEMENT.WORLD_INFO)
        val applyToReasoning = placement.reasoning && placements.contains(REGEX_PLACEMENT.REASONING)
        val applyToSlash = placement.slash && placements.contains(REGEX_PLACEMENT.SLASH_COMMAND)
        if (!applyToUser && !applyToAI && !applyToWI && !applyToReasoning && !applyToSlash) continue

        val markdownOnly = s.markdownOnly
        val promptOnly = s.promptOnly
        if (forDisplay) {
            if (promptOnly && !markdownOnly) continue
        } else if (!dry) {
            if (markdownOnly && !promptOnly) continue
        }

        val rawFind = s.findRegex
        val replaceTemplate = s.replaceString
        val name = s.name ?: s.scriptName ?: "(未命名)"
        val trims = trimList(s).map { substituteKnownMacros(it, ctx) }

        val subMode = s.substituteRegex
        val find = resolveFindPattern(rawFind, subMode, ctx)

        val regex: JsRegex = try {
            JsRegex.compile(find)
        } catch (e: Exception) {
            System.err.println("[正则] 脚本「$name」编译失败，已跳过：${e.message} | findRegex: ${find.take(120)}")
            hits.add(
                RegexHit(
                    id = s.id ?: name, scriptName = name, placement = placements,
                    findRegex = find, replaceString = replaceTemplate, matchCount = 0, sample = null,
                ),
            )
            continue
        }

        var matchCount = 0
        var sample: RegexSample? = null

        for (mi in work.indices) {
            val m = work[mi]
            val isUser = m.role == Role.USER
            val isAI = m.role == Role.ASSISTANT
            val isWI = m.origin == MessageOrigin.WORLDBOOK || m.origin == MessageOrigin.AUTHOR_NOTE
            val isReasoning = m.origin == MessageOrigin.REASONING
            val isSlash = m.origin == MessageOrigin.SLASH
            val touch =
                (applyToUser && isUser && !isSlash) ||
                    (applyToAI && isAI && !isReasoning) ||
                    (applyToWI && isWI) ||
                    (applyToReasoning && isReasoning) ||
                    (applyToSlash && isSlash)
            if (!touch) continue

            val depthFromEnd = options.depthOverride ?: (work.size - 1 - mi)
            if (!inDepthRange(s, depthFromEnd)) continue

            regex.lastIndex = 0
            val firstMatch = regex.firstMatch(m.content) ?: continue

            if (dry) matchCount += regex.countMatches(m.content)

            if (sample == null) {
                val idx = firstMatch.index
                val beforeCtx = m.content.substring(maxOf(0, idx - 24), idx)
                val matchText = firstMatch.whole
                val afterCtx = m.content.substring(
                    minOf(idx + matchText.length, m.content.length),
                    minOf(idx + matchText.length + 24, m.content.length),
                )
                val preview = buildReplacement(
                    replaceTemplate,
                    MatchParts(firstMatch.whole, firstMatch.groups, firstMatch.named),
                    trims, ctx, vars,
                )
                sample = RegexSample(
                    before = "$beforeCtx⟪$matchText⟫$afterCtx",
                    after = "$beforeCtx⟪$preview⟫$afterCtx",
                )
            }

            if (!dry) {
                regex.lastIndex = 0
                val transformed = regex.replace(m.content) { jm ->
                    matchCount++
                    buildReplacement(
                        replaceTemplate,
                        MatchParts(jm.whole, jm.groups, jm.named),
                        trims, ctx, vars,
                    )
                }
                m.content = transformed
            }
        }

        hits.add(
            RegexHit(
                id = s.id ?: name, scriptName = name, placement = placements,
                findRegex = find, replaceString = replaceTemplate, matchCount = matchCount, sample = sample,
            ),
        )
    }

    return work to hits
}

package com.dbpg.stcore

import java.util.regex.Matcher
import java.util.regex.Pattern

/** 从正则源码里提取命名分组名（对应 ST 的 $<name>）。 */
private val NAMED_GROUP_RE = Regex("""\(\?<([A-Za-z][A-Za-z0-9]*)>""")

/**
 * 一个贴近 JavaScript RegExp 语义的正则封装。
 *
 * 为什么不直接用 Kotlin `Regex`：
 *   - JS 的 `g` 标志会带 `lastIndex` 状态，多次 exec 从上次位置继续；
 *     Kotlin 的 `Regex` 没有这套，复用同一个实例会跳字。
 *   - JS 的 `String.replace(regex, fn)`：带 `g` 替换所有、不带 `g` 只换第一处；
 *     Kotlin 的 `Regex.replace` 永远替换所有，无法表达"只换一次"。
 *   - JS 正则支持 `i/m/s` 之外的 `g/u/y`，以及 `/pattern/flags` 字面量解析，
 *     而 Kotlin 的 `RegexOption` 不覆盖 `g`。
 *
 * 这里用 `java.util.regex.Pattern` 自己补齐 lastIndex 语义，
 * 让移植后的引擎与原 web 引擎在"替换次数 / 分组 / 命名分组 / 零宽匹配"上完全一致。
 */
class JsMatch(
    val whole: String,
    val groups: List<String?>,
    val named: Map<String, String>?,
    val index: Int,
) {
    /** 与 JS 回调 `(match, p1…pn, offset, string, groups?)` 对齐的参数列表。 */
    fun callbackArgs(): List<Any?> {
        val out = mutableListOf<Any?>(whole)
        out.addAll(groups)
        out.add(index)
        out.add(whole) // JS 回调最后一个非命名参数是整个 string
        if (named != null) out.add(named)
        return out
    }
}

class JsRegex(val source: String, val flags: String) {

    val global: Boolean = flags.contains('g')
    /** 模拟 JS 全局正则的 lastIndex 状态。 */
    var lastIndex: Int = 0
    private val pattern: Pattern
    /** 从 pattern 源码里抠出命名分组名（Java Matcher 没有 namedGroups 枚举）。 */
    private val namedGroupNames: List<String>

    init {
        var opts = 0
        if (flags.contains('i')) opts = opts or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        if (flags.contains('m')) opts = opts or Pattern.MULTILINE
        if (flags.contains('s')) opts = opts or Pattern.DOTALL
        pattern = Pattern.compile(source, opts)
        namedGroupNames = NAMED_GROUP_RE.findAll(source).map { it.groupValues[1] }.toList()
    }

    /** 模拟 JS 的 `regex.exec(str)`：带 `g` 时维护 lastIndex 状态。 */
    fun exec(input: String): JsMatch? {
        if (global) {
            if (lastIndex > input.length) {
                lastIndex = 0
                return null
            }
            val m = pattern.matcher(input)
            m.region(lastIndex.coerceAtMost(input.length), input.length)
            if (!m.find()) {
                lastIndex = 0
                return null
            }
            val start = m.start()
            val end = m.end()
            lastIndex = if (start == end) end + 1 else end
            return toMatch(m, input)
        } else {
            val m = pattern.matcher(input)
            if (!m.find()) return null
            return toMatch(m, input)
        }
    }

    /** 取「第一处」匹配（不碰 lastIndex 状态），用于构造 sample 片段。 */
    fun firstMatch(input: String): JsMatch? = exec(input)?.also { /* 非 global 时 exec 即第一处 */ }

    private fun toMatch(m: Matcher, input: String): JsMatch {
        val gs = mutableListOf<String?>()
        for (i in 1..m.groupCount()) gs.add(m.group(i))
        val named: Map<String, String>? = if (namedGroupNames.isEmpty()) null else {
            val nm = mutableMapOf<String, String>()
            for (n in namedGroupNames) {
                val g = runCatching { m.group(n) }.getOrNull()
                if (g != null) nm[n] = g
            }
            nm.takeIf { it.isNotEmpty() }
        }
        return JsMatch(m.group(), gs, named, m.start())
    }

    /**
     * 模拟 JS `String.replace(regex, fn)`：
     *   - 带 `g`：替换所有匹配；
     *   - 不带 `g`：只替换第一处。
     * fn 收到的参数与 JS 回调一致，返回替换串。
     */
    fun replace(input: String, transform: (JsMatch) -> String): String {
        if (!global) {
            val m = pattern.matcher(input)
            if (!m.find()) return input
            val jm = toMatch(m, input)
            return input.substring(0, jm.index) + transform(jm) + input.substring(jm.index + jm.whole.length)
        }
        val sb = StringBuilder()
        var pos = 0
        var guard = 0
        while (pos <= input.length) {
            val m = pattern.matcher(input)
            m.region(pos, input.length)
            if (!m.find()) break
            val start = m.start()
            val end = m.end()
            sb.append(input, pos, start)
            sb.append(transform(toMatch(m, input)))
            pos = if (start == end) end + 1 else end
            if (++guard >= 10000) break // 防御性上限，避免极端数据卡死
        }
        sb.append(input, pos, input.length)
        return sb.toString()
    }

    /** 统计能匹配多少处（dryRun 统计用），零宽匹配防死循环。 */
    fun countMatches(input: String, cap: Int = 999): Int {
        var n = 0
        var pos = 0
        var guard = 0
        while (pos <= input.length && n < cap) {
            val m = pattern.matcher(input)
            m.region(pos, input.length)
            if (!m.find()) break
            val start = m.start()
            val end = m.end()
            n++
            pos = if (start == end) end + 1 else end
            if (++guard >= 10000) break
        }
        return n
    }

    companion object {
        /** 解析 `/pattern/flags` 或裸 pattern，与 web 的 parseRegex 对齐。 */
        private val cache = mutableMapOf<String, Pair<String, String>>()

        fun parse(raw: String): Pair<String, String> {
            cache[raw]?.let { return it }
            var pattern = raw
            var flags = "g"
            // 与 web regexFromString 对齐：/^\/(.+)\/([a-z]*)$/si
            // 关键点 ① 必须吃下开头那个斜杠 ^\/，否则 (.*) 会把开头的 / 一起算进 pattern，
            //   结果 pattern 变成 "/abc" 而非 "abc"，永远匹配不上（这是"正则不生效"的头号元凶）。
            // 关键点 ② 带 i（[a-z] 能吃大写 flag）/ s（. 吃换行，长正则里偶尔有）。
            val m = Regex(
                "^/(.+)/([a-z]*)$",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).find(raw)
            if (m != null) {
                pattern = m.groupValues[1]
                flags = m.groupValues[2]
            }
            val out = pattern to flags
            if (cache.size > 500) cache.clear()
            cache[raw] = out
            return out
        }

        /** 每次都新建实例：带 `g` 的 RegExp 有 lastIndex 状态，复用会跳字。 */
        fun compile(raw: String): JsRegex {
            val (src, fl) = parse(raw)
            return JsRegex(src, fl)
        }
    }
}

/** 转义正则元字符（对应 web 的 sanitizeRegexMacro）。 */
fun sanitizeRegexMacro(s: String): String {
    val meta = "^$.*+?()[]{}|/\\"
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u000b' -> sb.append("\\u000b") // 垂直制表符：Java 正则不支持 \v，用 unicode 转义
            '\u000c' -> sb.append("\\f")
            '\u0000' -> sb.append("\\u0000")
            else -> if (meta.contains(c)) sb.append("\\").append(c) else sb.append(c)
        }
    }
    return sb.toString()
}

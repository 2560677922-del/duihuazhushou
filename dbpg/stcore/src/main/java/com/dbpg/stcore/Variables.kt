package com.dbpg.stcore

/**
 * 变量引擎（对应 web variables.ts）。
 * 只做语法级 ST 变量宏的扁平子集：session / global 两张平级表，单次替换，不嵌套。
 */

private val VAR_RE = Regex("\\{\\{\\s*([a-z]+)\\s*::\\s*([^{}]*?)\\s*\\}\\}", RegexOption.IGNORE_CASE)

private val READ_NAMES = setOf("getvar", "getglobalvar", "hasvar", "hasglobalvar")
private val WRITE_NAMES = setOf(
    "setvar", "setglobalvar",
    "addvar", "addglobalvar",
    "incvar", "decvar", "incglobalvar", "decglobalvar",
    "deletevar", "deleteglobalvar",
)
private val ALL_NAMES = READ_NAMES + WRITE_NAMES

/**
 * 与 web 对齐的数字格式化：JS 里 Number(5)+Number(3)=8，String(8) 是 "8" 而不是 "8.0"；
 * 只有真正带小数时才出小数点。否则 {{addvar::lv::3}} 会渲染成 "8.0"，和 web 不一致。
 */
private fun formatNumber(d: Double): String =
    if (d.isFinite() && d % 1.0 == 0.0) d.toLong().toString() else d.toString()

/** ST 语义：两边都能转数字就加减，否则拼接；未定义首次当 0。 */
private fun addValue(cur: String?, delta: String): String {
    val a = cur?.takeIf { it.isNotBlank() }?.toDoubleOrNull()
    val b = delta.toDoubleOrNull()
    if (a != null && b != null) return formatNumber(a + b)
    if (a == null && (cur == null || cur.isBlank()) && b != null) return formatNumber(b)
    return (cur ?: "") + delta
}

/** inc/dec：非数字内容保持原样（和 ST 的警告行为一致）。 */
private fun bump(cur: String?, by: Int): String {
    val a = cur?.takeIf { it.isNotBlank() }?.toDoubleOrNull()
    if (a == null) return if (cur != null && cur.isNotBlank()) cur else by.toString()
    return formatNumber(a + by)
}

private fun splitArgs(body: String): List<String> =
    body.split("::").map { it.trim() }

private fun resolveOne(
    full: String,
    rawName: String,
    body: String,
    live: VariableTables,
    allowWrite: Boolean,
    markChanged: () -> Unit,
): String {
    val name = rawName.lowercase()
    if (name !in ALL_NAMES) return full
    val args = splitArgs(body)
    val key = args.firstOrNull() ?: return ""
    val isGlobal = name.contains("global")
    val table = if (isGlobal) live.global else live.session
    val value = args.drop(1).joinToString("::")

    when (name) {
        "getvar", "getglobalvar" -> return table[key] ?: ""
        "hasvar", "hasglobalvar" -> return if (table.containsKey(key)) "true" else "false"
    }

    if (!allowWrite) return "" // 只读路径：写宏抹掉，绝不执行

    when (name) {
        "setvar", "setglobalvar" -> {
            table[key] = value
            markChanged()
            return ""
        }
        "deletevar", "deleteglobalvar" -> {
            table.remove(key)
            markChanged()
            return ""
        }
        "addvar", "addglobalvar" -> {
            table[key] = addValue(table[key], value)
            markChanged()
            return ""
        }
        "incvar", "incglobalvar" -> {
            table[key] = bump(table[key], 1)
            markChanged()
            return table[key] ?: ""
        }
        "decvar", "decglobalvar" -> {
            table[key] = bump(table[key], -1)
            markChanged()
            return table[key] ?: ""
        }
    }
    return full
}

/** 只读展开。写宏被抹成空串，不改表。显示 / 提示词 / 正则替换串都走这里。 */
fun readVariables(text: String, tables: VariableTables): String {
    if (text.isEmpty() || !text.contains("{{")) return text
    return VAR_RE.replace(text) { mr ->
        resolveOne(mr.value, mr.groupValues[1], mr.groupValues[2], tables, false) { }
    }
}

/**
 * 执行一次写宏，返回替换后的文本和新表。
 * 只允许在"用户发送"和"AI 回复落库"这两个一次性入口调用。
 */
fun applyVariableWrites(text: String, tables: VariableTables): Pair<String, VariableTables> {
    val live = VariableTables(
        session = tables.session.toMutableMap(),
        global = tables.global.toMutableMap(),
    )
    if (text.isEmpty() || !text.contains("{{")) return text to live
    var changed = false
    val out = VAR_RE.replace(text) { mr ->
        resolveOne(mr.value, mr.groupValues[1], mr.groupValues[2], live, true) { changed = true }
    }
    return out to live
}

/** 短哈希，给显示缓存当版本号（对应 web variableVersion）。 */
fun variableVersion(tables: VariableTables): String {
    val raw = """[${tables.session},${tables.global}]"""
    var h = 5381
    for (c in raw) h = ((h shl 5) + h + c.code) or 0
    return (h ushr 0).toString(36)
}

package com.dbpg.stcore.imagegen

import org.json.JSONArray

/**
 * 把**资产页里的一套预设**压成一段能直接放进 system 的文本。
 *
 * ## 为什么不再自己存一套预设
 *
 * 之前（v1.3~v1.4）插件自带预设库、自带导入器、自带三段编辑。问题很实在：
 * 资产页本来就能导入酒馆预设（`importAsset` → `importAnyJson`）、逐条启停、
 * 看顺序 —— 再复制一套等于让用户在两处维护同一件事，而且插件那份拿不到
 * 社区预设的能力（几十条、带顺序、带开关）。
 *
 * 现在插件只做一件事：**从资产预设里挑一套，取它已启用的段拼成 system**。
 * 内容、顺序、启停都在资产页管，插件不存副本。
 *
 * ## 取哪些段
 *
 * `getAssets` 已经把每个预设的 prompts 按 `prompt_order` 排好（见
 * `listRankedPrompts`：`ordered` 表示在排序表里，`position` 是位置），
 * 这里只按顺序挑：
 *
 * - `ordered == true` —— 不在排序表里的属于「ST 里未激活的提示词」，不参与生成；
 * - `enabled == true` —— 以 order[].enabled 为准，这是 ST 的唯一权威；
 * - `marker == false` —— 占位标记（如 `chatHistory`）没有正文，只有位置意义；
 * - 洗完宏之后还剩正文。
 *
 * ## 洗宏
 *
 * 预设里大量使用 ST 变量，我们这边没有对应的表，照搬过去就是一段段
 * `{{setglobalvar::...}}` 噪声，白吃上下文还会让模型分心：
 *
 * | 形态 | 处理 |
 * | --- | --- |
 * | `{{setglobalvar::x::y}}` / `{{getglobalvar::x}}` | 整段删（我们不做变量） |
 * | `{{char}}` / `{{user}}` | 换成「角色」/「用户」 |
 * | 其它 `{{...}}`（scenario / persona / original …） | 删（没有对应内容，留着只是噪声） |
 * | `<\|no-trans\|>` / `<｜end▁of▁instance｜>` 这类特殊 token | 删 |
 * | `<!-- 注释 -->` | 删 |
 */
object PresetBlocks {

    /** 一次拼装的结果。 */
    data class Block(
        /** 拼好的正文；没有可用段时为空串。 */
        val text: String = "",
        /** 实际取用的段数。 */
        val picked: Int = 0,
        /** 这套预设里"可用段"的总数（已启用、有正文）。 */
        val total: Int = 0,
        /** 是否因为「只取破甲段」筛空了而回退到全部段。 */
        val fellBack: Boolean = false,
    )

    /**
     * 段名/正文里出现这些词，就当它是破甲段。
     *
     * 刻意收窄：`jailbreak` / `破甲` / `越狱` / `解除限制` / `uncensored` / `nsfw`。
     * 用宽泛的 `无限制`、`成人` 会把"写作风格"那类段也拉进来 —— 它们教模型
     * 分段写小说，对打标签纯属负担。
     */
    private val JAILBREAK_HINTS = listOf(
        "jailbreak", "jail break", "uncensored", "nsfw",
        "破甲", "越狱", "解除限制", "解除审查", "无审查", "绕过",
    )

    /**
     * 段的正文短于这个长度就没有信息量，直接丢。
     *
     * 8 是量出来的：洗完只剩「{{char}}：」这类占位的前缀通常 3~6 字，
     * 而一句真正的约束（"只输出 JSON，不要解释。"）有 12 字以上。
     */
    private const val MIN_CHARS = 8

    /**
     * 拼一套预设。
     *
     * @param prompts       `getAssets` 里 `presets[].prompts`（已按 prompt_order 排好）
     * @param jailbreakOnly 只取破甲相关段；筛空时自动回退到全部（并置 [Block.fellBack]）
     */
    fun compose(prompts: JSONArray?, jailbreakOnly: Boolean = false): Block {
        if (prompts == null || prompts.length() == 0) return Block()

        var total = 0
        val usable = ArrayList<Pair<String, String>>(prompts.length()) // name to body

        for (i in 0 until prompts.length()) {
            val p = prompts.optJSONObject(i) ?: continue
            if (!isUsable(p)) continue
            val body = sanitize(p.optString("content"))
            if (body.isBlank()) continue
            total++
            usable.add(p.optString("name") to body)
        }
        if (usable.isEmpty()) return Block()

        val source = if (jailbreakOnly) {
            usable.filter { (name, body) -> looksLikeJailbreak(name, body) }
        } else {
            emptyList()
        }
        val fellBack = jailbreakOnly && source.isEmpty()
        val picked = if (source.isEmpty()) usable else source

        val text = StringBuilder()
        for ((_, body) in picked) {
            if (text.isNotEmpty()) text.append("\n\n")
            text.append(body)
        }
        return Block(text.toString(), picked.size, total, fellBack)
    }

    /** 这条 prompt 会不会真的进生成。 */
    private fun isUsable(p: org.json.JSONObject): Boolean {
        if (p.optBoolean("marker", false)) return false
        if (!p.optBoolean("enabled", true)) return false
        // 字段缺失时按"在排序表里"处理：老数据没有 ordered 字段
        return p.optBoolean("ordered", true)
    }

    /** 段名或正文命中破甲关键词。段名权重更高，只命中正文也算（社区预设命名很随意）。 */
    fun looksLikeJailbreak(name: String, content: String): Boolean {
        val n = name.lowercase()
        if (JAILBREAK_HINTS.any { n.contains(it) }) return true
        val head = content.take(400).lowercase()
        return JAILBREAK_HINTS.any { head.contains(it) }
    }

    /**
     * 洗宏：把 ST 变量与特殊 token 变成空白或中文占位。
     *
     * 不用正则一次扫完，是因为写宏带 `::` 参数、可能跨行；按类型分开删更可读，
     * 也避免一条坏正则把整段正文吃掉（这个坑在导入器里踩过）。
     */
    /*
     * 洗宏用的正则，集中在这里有两个原因：
     *   1. `}}` 必须写成 `\}\}` —— Android 的正则引擎遇到裸 `}` 会抛
     *      "Syntax error in regexp pattern near index N"，而桌面 JVM 当普通字符，
     *      单测发现不了（`AutoImageSupport.renderTemplate` 就是这么炸的）；
     *   2. 抽成常量后 `RegexPortabilityTest` 能逐条检查写法，不用靠肉眼。
     */
    internal val RE_MACRO_VAR = Regex("\\{\\{\\s*(set|get|add|inc|dec)(global)?var[：:]?[\\s\\S]*?\\}\\}")
    internal val RE_MACRO_COMMENT = Regex("\\{\\{\\s*//[\\s\\S]*?\\}\\}")
    internal val RE_HTML_COMMENT = Regex("<!--[\\s\\S]*?-->")
    internal val RE_MACRO_CHAR = Regex("\\{\\{\\s*char\\s*\\}\\}", RegexOption.IGNORE_CASE)
    internal val RE_MACRO_USER = Regex("\\{\\{\\s*user\\s*\\}\\}", RegexOption.IGNORE_CASE)
    internal val RE_MACRO_ANY = Regex("\\{\\{[\\s\\S]{0,120}?\\}\\}")
    internal val RE_TOKEN_PIPE = Regex("<[|｜][\\s\\S]{0,40}?[|｜]>")
    internal val RE_TOKEN_ROLE = Regex("</?(start|end|sys|user|assistant)>", RegexOption.IGNORE_CASE)

    /** 所有内置宏正则 —— 测试拿它逐条检查。 */
    internal val MACRO_PATTERNS: List<Regex> = listOf(
        RE_MACRO_VAR, RE_MACRO_COMMENT, RE_HTML_COMMENT,
        RE_MACRO_CHAR, RE_MACRO_USER, RE_MACRO_ANY,
        RE_TOKEN_PIPE, RE_TOKEN_ROLE,
    )

    fun sanitize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw

        // 1. 写变量 / 读变量：内容整段丢（我们没有 session/global 表可查）
        s = s.replace(RE_MACRO_VAR, "")
        // 2. 注释 / 条件块
        s = s.replace(RE_MACRO_COMMENT, "")
        s = s.replace(RE_HTML_COMMENT, "")
        // 3. 角色名占位
        s = s.replace(RE_MACRO_CHAR, "角色")
        s = s.replace(RE_MACRO_USER, "用户")
        // 4. 其余未知宏（scenario / persona / original / random …）：没有对应值，删
        s = s.replace(RE_MACRO_ANY, "")
        // 5. 特殊 token：<|no-trans|> / <｜end▁of▁instance｜> / <start>
        s = s.replace(RE_TOKEN_PIPE, "")
        s = s.replace(RE_TOKEN_ROLE, "")

        // 6. 收尾：行尾空白、三连以上空行压成两行
        s = s.lines().joinToString("\n") { it.trimEnd() }
        s = s.replace(Regex("\n{3,}"), "\n\n")
        s = s.trim()
        return if (s.length < MIN_CHARS) "" else s
    }
}

package com.dbpg.duihuazhushou.imagegen

import org.json.JSONArray
import org.json.JSONObject
import com.dbpg.stcore.imagegen.PresetRegex
import com.dbpg.stcore.imagegen.PresetRule

/**
 * 把一段文本解析成「可以导入的预设」。
 *
 * ## 认什么
 *
 * | 格式 | 识别依据 | 典型来源 |
 * | --- | --- | --- |
 * | 酒馆对话预设 | 顶层有 `prompts[]` | 社区 99% 的预设（含 `prompt_order`） |
 * | 本应用旧版预设 | 有 `jailbreak` / `template` / `suffix` | v1.4 及之前导出的 |
 * | 通用 JSON | `{name, prompt\|system_prompt\|content}` | 手搓的小配置 |
 * | 纯文本 | 完全不是 JSON | 从网页复制的一段破甲 |
 *
 * ## 为什么先拆段再让用户勾
 *
 * 酒馆预设是**写作**预设：`Cemini Novel En` 有 43 条，大半在教模型"分段写小说、
 * 用 `<page>` 包裹、继续正文"。整坨塞进 system 有两个后果 —— 一是几万 token
 * 白烧，二是模型真的会开始续写小说，而不是回一串标签。
 *
 * 所以拆开、标字数、给出建议勾选，让用户自己定；默认只勾破甲相关的。
 *
 * ## 洗宏
 *
 * `{{setglobalvar::…}}` 这类宏在我们的 system 里没有变量表，留着就是乱码，
 * 还会白吃上下文。`{{char}}` / `{{user}}` 换成中文再留；其余 `{{…}}` 删。
 */
object PromptPresetImport {

    /** 段的正文短于这个长度就没有信息量（洗完只剩「角色：」这种占位）。 */
    private const val MIN_CHARS = 8

    /** 判重要求两边都够长，否则短段的 bigram 集合太小，会被误判成重复。 */
    private const val DEDUPE_MIN = 120

    /** 判重阈值：交集 / 并集。0.7 是拿真实预设调出来的（0.5 会把 19 段砍成 4 段）。 */
    private const val DEDUPE_JACCARD = 0.7

    /** 破甲关键词：出现在标题或正文前 400 字里就算。 */
    private val JB_WORDS = listOf(
        "jailbreak", "uncensored", "nsfw", "unfiltered", "no restriction",
        "without restriction", "explicit",
        "破甲", "越狱", "解除限制", "无审查", "绕过", "不受限制", "尺度", "无限制",
    )

    enum class Format(val label: String) {
        TAVERN("酒馆预设"),
        OURS("本应用预设"),
        SIMPLE("通用 JSON"),
        TEXT("纯文本"),
    }

    /** 一段可选内容（已洗宏）。 */
    data class Segment(
        val id: String,
        val title: String,
        val body: String,
        /** 标题或正文命中破甲词 —— 默认勾上。 */
        val suggested: Boolean,
        /** 是否命中破甲词，用来算"仅破甲段"那一版正文。 */
        val jailbreak: Boolean,
    ) {
        val chars: Int get() = body.length
    }

    data class Draft(
        val format: Format,
        val name: String,
        val segments: List<Segment>,
        /** 解析时丢掉/处理过什么，给界面说明用。 */
        val notes: List<String>,
        /** 预设自带的正则脚本（只在文件里有才会有）。 */
        val rules: List<PromptPresetStore.Rule> = emptyList(),
    ) {
        /** 默认勾选：建议的段；一条都没有时退而求其次勾最长的那段。 */
        fun defaultPicked(): Set<String> {
            val suggested = segments.filter { it.suggested }
            val pick = if (suggested.isNotEmpty()) {
                suggested
            } else {
                listOfNotNull(segments.maxByOrNull { it.chars })
            }
            return pick.map { it.id }.toSet()
        }

        /**
         * 用勾选的段生成预设；一段没勾返回 null。
         *
         * 正文拼法：段与段之间空一行 —— 这些段本来各自独立，粘在一起会让
         * 模型的"段落感"错乱。
         */
        fun build(picked: Set<String>): PromptPresetStore.Preset? {
            val chosen = segments.filter { it.id in picked }
            if (chosen.isEmpty()) return null
            val all = chosen.joinToString("\n\n") { it.body }
            val jbSegs = chosen.filter { it.jailbreak }
            val fellBack = jbSegs.isEmpty()
            val jb = if (fellBack) all else jbSegs.joinToString("\n\n") { it.body }
            return PromptPresetStore.Preset(
                uid = "",
                name = name.ifBlank { "导入的预设" },
                all = all,
                jb = jb,
                total = segments.size,
                countAll = chosen.size,
                countJb = if (fellBack) chosen.size else jbSegs.size,
                jbFellBack = fellBack,
                source = "${format.label} · ${segments.size} 段可选" +
                    (if (rules.isEmpty()) "" else " · ${rules.size} 条正则"),
                segments = segments.map {
                    PromptPresetStore.Seg(
                        id = it.id,
                        title = it.title,
                        body = it.body,
                        jailbreak = it.jailbreak,
                        on = it.id in picked,
                    )
                },
                rules = rules,
            )
        }
    }

    sealed interface Result {
        data class Ok(val draft: Draft) : Result
        data class Err(val message: String) : Result
    }

    /** 外部（单测、界面）也能问一句"这段字命中破甲词吗"。 */
    fun looksLikeJailbreak(text: String): Boolean {
        val low = text.lowercase()
        return JB_WORDS.any { low.contains(it) }
    }

    fun parse(raw: String): Result {
        val text = raw.trim().removePrefix("\uFEFF").trim()
        if (text.isEmpty()) return Result.Err("内容是空的")
        if (text.length > 8 * 1024 * 1024) return Result.Err("文件太大（超过 8M），不像是预设")

        val obj = if (text.startsWith("{")) {
            runCatching { JSONObject(text) }.getOrNull()
        } else if (text.startsWith("[")) {
            // 有的导出是数组包一层：[{...preset}]，取第一个认得出的
            runCatching { JSONArray(text) }
                .getOrNull()
                ?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.has("prompts") || o.has("prompt") || o.has("jailbreak")) {
                            return fromJson(o)
                        }
                    }
                    null
                }
        } else {
            null
        }
        if (obj == null) {
            // 不是 JSON 对象：整段当破甲文本。粘贴那种"只有一段话"的预设走这里。
            return plain(text)
        }
        return fromJson(obj)
    }

    // ------------------------------------------------------------ 各格式

    private fun plain(text: String): Result {
        val body = clean(text)
        if (body.length < MIN_CHARS) return Result.Err("内容太短，没有可用的提示词")
        val jb = looksLikeJailbreak(body)
        return Result.Ok(
            Draft(
                format = Format.TEXT,
                name = "粘贴的破甲",
                segments = listOf(Segment("s1", "整段文本", body, true, jb)),
                notes = listOf("整段作为破甲注入（${body.length} 字）"),
            ),
        )
    }

    /**
     * 解析 JSON 预设。
     *
     * 先在**当前层**收正则脚本，再往里走一层解析正文 —— 有些导出把
     * `regex_scripts` 放在外层 `data` 里，有些放在 `extensions` 里，两处都认。
     */
    private fun fromJson(o: JSONObject): Result {
        val outer = regexRules(o)
        val inner = fromJsonInner(o)
        if (inner !is Result.Ok || outer.isEmpty()) return inner
        val merged = (outer + inner.draft.rules).distinctBy { it.find to it.replace }
        return Result.Ok(inner.draft.copy(rules = merged))
    }

    private fun fromJsonInner(o: JSONObject): Result {
        /*
         * 包装层：资产页「导出当前」、备份文件、以及一些社区站的下载都把真正的
         * 预设塞在 data / preset 里。只要有 prompts 或 jailbreak 就往里走一层。
         */
        for (k in listOf("data", "preset", "raw", "json")) {
            val inner = o.optJSONObject(k) ?: continue
            if (inner.has("prompts") || inner.has("jailbreak") || inner.has("prompt")) {
                return fromJson(inner)
            }
        }

        o.optJSONArray("prompts")?.let { return tavern(o, it) }

        // 本应用 v1.4 及之前的格式
        if (o.has("jailbreak") || o.has("template") || o.has("suffix")) return ours(o)

        val single = listOf("system_prompt", "prompt", "content")
            .firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() } }
        if (single != null) return simple(o, single)

        return Result.Err(
            "没认出格式。支持：酒馆预设（有 prompts 数组）、" +
                "{name, prompt} 这种小配置，或者直接粘一段文本。",
        )
    }

    // ------------------------------------------------------------ 正则脚本

    /**
     * 收预设自带的正则脚本（酒馆 `extensions.regex_scripts`）。
     *
     * **只收字符串，不编译**。这些正则的写法五花八门，各家引擎对转义的要求
     * 也不一样；在这里编译等于把"能不能导入"赌在别人的正则上。
     */
    private fun regexRules(o: JSONObject): List<PromptPresetStore.Rule> {
        val arr = o.optJSONArray("regex_scripts")
            ?: o.optJSONObject("extensions")?.optJSONArray("regex_scripts")
            ?: return emptyList()

        val out = ArrayList<PromptPresetStore.Rule>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val raw = e.optString("findRegex").ifBlank { e.optString("find_regex") }
            if (raw.isBlank()) continue

            val find = raw.trim()
            if (find.isBlank()) continue

            // 纯渲染用的（markdownOnly 且不是 promptOnly）只影响聊天界面显示，
            // 对我们要拼的 system 没有意义，收进来只会平白改动提示词。
            val mdOnly = e.optBoolean("markdownOnly", false)
            val promptOnly = e.optBoolean("promptOnly", false)
            if (mdOnly && !promptOnly) continue

            out.add(
                PromptPresetStore.Rule(
                    id = e.optString("id").ifBlank { "r${i + 1}" },
                    name = e.optString("scriptName").ifBlank { e.optString("name") },
                    find = find,
                    replace = unescape(e.optString("replaceString")),
                    on = !e.optBoolean("disabled", false),
                ),
            )
        }
        return out
    }

    /** JSON 里写成 `"\\n"` 的替换串，读出来是反斜杠 + n，这里换成真换行。 */
    private fun unescape(s: String): String =
        s.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "")

    private fun tavern(o: JSONObject, prompts: JSONArray): Result {
        val byId = LinkedHashMap<String, JSONObject>()
        for (i in 0 until prompts.length()) {
            val p = prompts.optJSONObject(i) ?: continue
            val id = p.optString("identifier").ifBlank { "idx_$i" }
            byId[id] = p
        }

        // prompt_order 是 ST 唯一权威：顺序 + 启停都在它里面。
        // 没有它（个别手搓文件）就退化成 prompts 的原顺序。
        val order = o.optJSONArray("prompt_order")
            ?.optJSONObject(0)
            ?.optJSONArray("order")

        val seq = ArrayList<Pair<JSONObject, Boolean>>()
        if (order != null) {
            for (i in 0 until order.length()) {
                val it = order.optJSONObject(i) ?: continue
                val p = byId[it.optString("identifier")] ?: continue
                seq.add(p to it.optBoolean("enabled", true))
            }
        } else {
            for (p in byId.values) seq.add(p to p.optBoolean("enabled", true))
        }

        var droppedEmpty = 0
        var droppedMarker = 0
        var droppedDisabled = 0
        val raw = ArrayList<Segment>(seq.size)
        seq.forEachIndexed { i, (p, enabled) ->
            if (!enabled) {
                droppedDisabled++
                return@forEachIndexed
            }
            if (p.optBoolean("marker", false)) {
                droppedMarker++
                return@forEachIndexed
            }
            val body = clean(p.optString("content"))
            if (body.length < MIN_CHARS) {
                droppedEmpty++
                return@forEachIndexed
            }
            val title = p.optString("name").ifBlank { p.optString("identifier") }.ifBlank { "第 ${i + 1} 段" }
            val hit = looksLikeJailbreak("$title\n${body.take(400)}")
            raw.add(Segment("s${raw.size + 1}", title, body, hit, hit))
        }

        if (raw.isEmpty()) {
            return Result.Err("这份预设里没有可用段落（全部停用 / 只有宏 / 都是空段）")
        }

        val kept = dedupe(raw)
        val dup = raw.size - kept.size

        val notes = ArrayList<String>(4)
        notes.add("${prompts.length()} 条提示词，${seq.size} 条在发送顺序里")
        if (droppedDisabled > 0) notes.add("跳过 $droppedDisabled 条未启用")
        if (droppedMarker > 0) notes.add("跳过 $droppedMarker 条占位（marker）")
        if (droppedEmpty > 0) notes.add("丢弃 $droppedEmpty 条空白/纯宏")
        if (dup > 0) notes.add("合并 $dup 条重复文案")

        val name = o.optString("name").ifBlank { "酒馆预设" }
        return Result.Ok(Draft(Format.TAVERN, name, kept, notes))
    }

    private fun ours(o: JSONObject): Result {
        val parts = listOf("jailbreak", "template", "suffix")
            .map { clean(o.optString(it)) }
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return Result.Err("这套预设里没有正文")
        val body = parts.joinToString("\n\n")
        return Result.Ok(
            Draft(
                format = Format.OURS,
                name = o.optString("name").ifBlank { "导入的预设" },
                segments = listOf(
                    Segment("s1", "破甲 / 模板 / 约束", body, true, looksLikeJailbreak(body)),
                ),
                notes = listOf("三段合并为一段（${body.length} 字）"),
            ),
        )
    }

    private fun simple(o: JSONObject, body: String): Result {
        val clean = clean(body)
        if (clean.length < MIN_CHARS) return Result.Err("内容太短，没有可用的提示词")
        val title = o.optString("name").ifBlank { "整段文本" }
        return Result.Ok(
            Draft(
                format = Format.SIMPLE,
                name = o.optString("name").ifBlank { "导入的预设" },
                segments = listOf(
                    Segment("s1", title, clean, true, looksLikeJailbreak("$title\n$clean")),
                ),
                notes = listOf("整段作为破甲注入（${clean.length} 字）"),
            ),
        )
    }

    // ------------------------------------------------------------ 洗宏 / 去重

    /**
     * 洗宏。留 `{{char}}` / `{{user}}`（换中文），其余一律删 —— 我们没有
     * 变量表，`{{getglobalvar::x}}` 在 system 里就是一行乱码。
     *
     * ★ 每条规则**单独编译、单独容错**。这里吃的全是外部文件内容，而各家的
     *   正则引擎对"花括号要不要转义"这类细节并不一致（v1.7.1 就是在这一步
     *   整份导入失败）。坏一条就少洗一个宏，不该让整套预设进不来。
     */
    private val CLEAN_RULES: List<PresetRule> by lazy {
        listOf(
            // 写宏整条删（可能带长参数，先单独处理）
            PresetRule("c1", "写宏", "\\{\\{setglobalvar::[\\s\\S]{0,400}?\\}\\}", ""),
            PresetRule("c2", "读写变量", "\\{\\{(?:get|set|add|inc|dec)\\w*var::[^\\{\\}]{0,200}\\}\\}", ""),
            // 角色 / 用户：换成中文，语义还留着（`(?i)` 内联，省得依赖 RegexOption）
            PresetRule("c3", "角色占位", "(?i)\\{\\{\\s*char\\s*\\}\\}", "角色"),
            PresetRule("c4", "用户占位", "(?i)\\{\\{\\s*user\\s*\\}\\}", "用户"),
            // 其余 {{…}}（scenario / persona / original / random …）：没有值，删
            PresetRule("c5", "未知宏", "\\{\\{[^\\{\\}]{0,120}\\}\\}", ""),
            // 特殊 token：<|no-trans|>、<｜end▁of▁instance｜>
            PresetRule("c6", "特殊 token", "<\\|[^|]{0,60}\\|>", ""),
            PresetRule("c7", "特殊 token（全角）", "<[｜][^>]{0,60}[｜]>", ""),
            // HTML 注释
            PresetRule("c8", "HTML 注释", "<!--[\\s\\S]{0,2000}?-->", ""),
            // 空白收敛
            PresetRule("c9", "行尾空白", "[ \\t]+\\n", "\n"),
            PresetRule("c10", "连续空行", "\\n{3,}", "\n\n"),
        )
    }

    private fun clean(src: String): String {
        var t = src
        for (r in CLEAN_RULES) {
            t = PresetRegex.applyOne(t, r) ?: t
        }
        return t.trim()
    }

    /**
     * 去掉"换汤不换药"的重复段。
     *
     * 社区预设常见"原版 / 加强版 / 旧版"三份同文破甲。按开头 300 字的
     * bigram 集合算 Jaccard，两边都得 ≥[DEDUPE_MIN] 字才参与判断 ——
     * 短段集合太小，0.7 这种阈值对它没有意义。
     */
    private fun dedupe(list: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>(list.size)
        for (s in list) {
            val dup = if (s.body.length >= DEDUPE_MIN) {
                val a = shingles(s.body.take(300))
                out.any { prev ->
                    if (prev.body.length < DEDUPE_MIN) return@any false
                    val b = shingles(prev.body.take(300))
                    val inter = a.count { it in b }
                    val union = a.size + b.size - inter
                    union > 0 && inter.toDouble() / union >= DEDUPE_JACCARD
                }
            } else {
                false
            }
            if (!dup) out.add(s)
        }
        return out
    }

    private fun shingles(s: String): Set<String> {
        if (s.length < 2) return setOf(s)
        val set = HashSet<String>(s.length)
        for (i in 0 until s.length - 1) set.add(s.substring(i, i + 2))
        return set
    }
}

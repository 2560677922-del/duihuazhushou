package com.dbpg.duihuazhushou.imagegen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 「提示词预设」插件的**自带预设库**。
 *
 * ## 为什么不再读资产页
 *
 * v1.5 / v1.6 试过两条路，都不成立：
 *
 * 1. 出图时向引擎求值 `getAssets()` —— 那是**整套资产**（世界书全部条目 +
 *    正则脚本 + 角色卡），几百 KB 到几 MB，在聊天页/设置页（前台不是引擎页）
 *    经常 6 秒都回不来，界面只能显示"读不到"；
 * 2. 让引擎在 `assets.save()` 时推一份精简快照 —— 方向是对的，但预设活在
 *    网页存储里，推送时机依赖"用户刚好在资产页点过保存"，冷启动、导入后立刻
 *    进设置页、进程被杀重开…… 每一种都能让它落空。而且它把"能不能出图"
 *    绑在了另一个页面的生命周期上，这种耦合本身就是 bug 温床。
 *
 * 所以改成**完全自持**：预设存在本机一个 JSON 文件里，导入、编辑、删除都在
 * 这里，出图只读本地（毫秒级），跟资产页、跟引擎启动顺序都没有关系。
 *
 * ## 存的是"成品文本"
 *
 * 导入时就已经洗好宏、按顺序拼成整段。出图那几秒不该再算一遍 —— 代价是每套
 * 预设存两版（全部段 / 仅破甲段），见 [Preset.all] 与 [Preset.jb]。
 *
 * 为什么不存段落结构：用户要的是"这套预设写进 system 的那段话"，段落只在导入
 * 那一次有意义（勾选）。存段落会让"改一个字"变成重算全表，还会让几处显示
 * 逻辑各自理解一遍结构。
 */
object PromptPresetStore {

    private const val DIR = "prompt_presets"
    private const val NAME = "presets.json"

    /** 存储结构版本。1 = 自带库（v1.7 起）；2 = 带段结构与正则脚本（v1.8 起）。 */
    private const val SCHEMA = 2

    /** 单套预设拼完后的上限；超了截断（正常 5~15K，只有写作预设才冲到这里）。 */
    private const val MAX_BLOCK = 60_000

    /** 一套预设。正文已经是可入 system 的成品。 */
    data class Preset(
        val uid: String,
        val name: String,
        /** 全部勾选段拼出来的正文。 */
        val all: String,
        /** 只取破甲段拼出来的正文；筛空时等于 [all] 并由 [jbFellBack] 标记。 */
        val jb: String,
        /** 导入时可选的段总数（界面显示 "2/15 段" 的分母）。 */
        val total: Int,
        val countAll: Int,
        val countJb: Int,
        val jbFellBack: Boolean = false,
        /** 来源说明，例如「酒馆预设 · 43 条」。 */
        val source: String = "",
        /** 段结构；只有导入来的才有，手动编辑过的为空。 */
        val segments: List<Seg> = emptyList(),
        /** 预设自带的正则脚本；空 = 这套预设没有。 */
        val rules: List<Rule> = emptyList(),
    ) {
        fun body(jailbreakOnly: Boolean): String = if (jailbreakOnly) jb else all

        fun pickedCount(jailbreakOnly: Boolean): Int = if (jailbreakOnly) countJb else countAll

        /** 本次要跑的规则（用户可以在设置页逐条关掉）。 */
        fun activeRules(): List<Rule> = rules.filter { it.on }

        /** 有没有段结构 —— 没有就没法在设置页里重排。 */
        val structural: Boolean get() = segments.isNotEmpty()
    }

    /** 一套预设里的一段提示词（已洗宏）。 */
    data class Seg(
        val id: String,
        val title: String,
        val body: String,
        /** 命中破甲词 —— "仅破甲段"按它筛。 */
        val jailbreak: Boolean,
        /** 当前是否勾选。 */
        val on: Boolean,
    ) {
        val chars: Int get() = body.length
    }

    /**
     * 预设自带的一条正则脚本。
     *
     * 只存字符串，**绝不在这里编译**：v1.7.1 就是因为"把外部文本当正则直接
     * 编译"，一条引擎不认的写法让整份预设导入失败（`\{\{char\}\}` 就是活例）。
     * 运行时由 [com.dbpg.stcore.imagegen.PresetRegex] 逐条 runCatching。
     */
    data class Rule(
        val id: String,
        val name: String,
        /** 原始 findRegex，可能带 `/pattern/gi` 外壳。 */
        val find: String,
        val replace: String,
        val on: Boolean = true,
    ) {
        /** 界面上那一行显示什么。 */
        val label: String get() = name.ifBlank { find.take(48).ifBlank { "(空正则)" } }
    }

    data class State(
        /** 关掉就完全不注入，只用任务模板 —— 给"我就想裸跑"的人留的口子。 */
        val usePreset: Boolean = false,
        /** 选中的预设 uid；空 = 用第一套。 */
        val presetId: String = "",
        /** 只取破甲相关段。默认开：写作预设里大半段在教模型写小说，对打标签是负担。 */
        val jailbreakOnly: Boolean = true,
        val presets: List<Preset> = emptyList(),
    ) {
        /** 本次实际会用的那套。 */
        fun resolved(): Preset? =
            presets.firstOrNull { it.uid == presetId } ?: presets.firstOrNull()
    }

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dhp-prompt-preset") }

    @Volatile
    private var memo: State? = null

    private fun file(c: Context): File {
        val dir = File(File(c.applicationContext.filesDir, "data"), DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, NAME)
    }

    @Synchronized
    fun load(context: Context): State {
        memo?.let { return it }
        val f = file(context)
        val st = runCatching {
            if (f.isFile && f.length() > 1) parse(JSONObject(f.readText(Charsets.UTF_8))) else State()
        }.getOrDefault(State())
        // 一套预设都没有的时候，"开没开"没有意义。开关显示成"开"只会让人以为
        // 被强制要求用预设 —— 关掉它，等真导入一套时 upsert 自己会打开。
        val out = if (st.presets.isEmpty()) st.copy(usePreset = false) else st
        memo = out
        return out
    }

    @Synchronized
    private fun save(context: Context, state: State) {
        memo = state
        val text = toJson(state).toString()
        val target = file(context)
        io.execute {
            val tmp = File(target.parentFile, "$NAME.tmp")
            runCatching {
                tmp.writeText(text, Charsets.UTF_8)
                if (target.exists() && !target.delete()) {
                    target.writeText(text, Charsets.UTF_8)
                    tmp.delete()
                } else if (!tmp.renameTo(target)) {
                    target.writeText(text, Charsets.UTF_8)
                    tmp.delete()
                }
            }.onFailure { runCatching { tmp.delete() } }
        }
    }

    // ------------------------------------------------------------ 用户选择

    fun setUsePreset(context: Context, on: Boolean) =
        save(context, load(context).copy(usePreset = on))

    fun setPresetId(context: Context, uid: String) =
        save(context, load(context).copy(presetId = uid))

    fun setJailbreakOnly(context: Context, on: Boolean) =
        save(context, load(context).copy(jailbreakOnly = on))

    // ------------------------------------------------------------ 预设 CRUD

    /**
     * 新增或更新一套预设，返回它的 uid。
     *
     * [Preset.uid] 为空时生成新的；同 uid 就整体替换。
     */
    fun upsert(context: Context, preset: Preset): String {
        val st = load(context)
        val uid = preset.uid.ifBlank { "pp_" + UUID.randomUUID().toString().take(8) }
        val item = preset.copy(
            uid = uid,
            all = preset.all.take(MAX_BLOCK),
            jb = preset.jb.take(MAX_BLOCK),
        )
        val existed = st.presets.any { it.uid == uid }
        val list = st.presets.filterNot { it.uid == uid } + item
        // 新建的自动选中 + 打开开关（导进来就是为了用它）；
        // 更新时不碰开关 —— 用户特意关掉之后编辑正文，不该被悄悄打开。
        val pick = if (existed) st.presetId.ifBlank { uid } else uid
        save(
            context,
            st.copy(presets = list, presetId = pick, usePreset = st.usePreset || !existed),
        )
        return uid
    }

    fun remove(context: Context, uid: String) {
        val st = load(context)
        save(
            context,
            st.copy(
                presets = st.presets.filterNot { it.uid == uid },
                presetId = if (st.presetId == uid) "" else st.presetId,
            ),
        )
    }

    fun rename(context: Context, uid: String, name: String) {
        val st = load(context)
        save(
            context,
            st.copy(presets = st.presets.map { if (it.uid == uid) it.copy(name = name) else it }),
        )
    }

    /**
     * 手改正文。
     *
     * 改完两版同文 —— 段落结构在导入时已经用掉了，这里只剩一段用户自己写的
     * 话，"仅破甲段"再筛一次只会得到一堆残句。
     *
     * 段结构一并清掉（否则设置页会拿旧段去覆盖刚改的正文）；正则脚本保留，
     * 它们跟正文是两回事。
     */
    fun replaceBody(context: Context, uid: String, body: String) {
        val st = load(context)
        val text = body.trim().take(MAX_BLOCK)
        save(
            context,
            st.copy(
                presets = st.presets.map {
                    if (it.uid == uid) {
                        // 段落结构在导入时已经用掉了，手改后只剩一段 —— 统计也归到 1/1，
                        // 否则界面会显示"1/15 段"这种看着像导入出错的样子。
                        it.copy(
                            all = text,
                            jb = text,
                            jbFellBack = false,
                            total = 1,
                            countAll = 1,
                            countJb = 1,
                            source = "手动编辑",
                            segments = emptyList(),
                        )
                    } else {
                        it
                    }
                },
            ),
        )
    }

    /**
     * 设置页保存：哪些段勾上、哪些规则开着。
     *
     * 段勾选会重算 [Preset.all] / [Preset.jb]，让出图那几秒什么都不用算
     * （运行时仍然只读成品文本）；规则只改 on 标记，正文不动。
     */
    fun setSelection(
        context: Context,
        uid: String,
        picked: Set<String>,
        rulesOn: Set<String>,
    ) {
        val st = load(context)
        save(
            context,
            st.copy(
                presets = st.presets.map { p ->
                    if (p.uid != uid) return@map p
                    val marked = p.copy(rules = p.rules.map { it.copy(on = it.id in rulesOn) })
                    if (p.segments.isEmpty()) {
                        marked
                    } else {
                        recompose(
                            marked,
                            marked.segments.map { it.copy(on = it.id in picked) },
                        )
                    }
                },
            ),
        )
    }

    /** 按勾选重算两版正文与统计。段结构为空时原样返回。 */
    private fun recompose(p: Preset, segs: List<Seg>): Preset {
        val chosen = segs.filter { it.on }
        val all = chosen.joinToString("\n\n") { it.body }.take(MAX_BLOCK)
        val jbSegs = chosen.filter { it.jailbreak }
        val fellBack = jbSegs.isEmpty()
        val jb = if (fellBack) all else jbSegs.joinToString("\n\n") { it.body }.take(MAX_BLOCK)
        return p.copy(
            segments = segs,
            all = all,
            jb = jb,
            total = segs.size,
            countAll = chosen.size,
            countJb = if (fellBack) chosen.size else jbSegs.size,
            jbFellBack = fellBack,
        )
    }

    /** 测试/迁移用：丢掉内存缓存，下次 load 重新读盘。 */
    @Synchronized
    fun invalidate() {
        memo = null
    }

    /** 诊断用：库文件大小。 */
    fun size(context: Context): Long = runCatching { file(context).length() }.getOrDefault(0L)

    // ------------------------------------------------------------ 序列化

    private fun toJson(s: State): JSONObject {
        val arr = JSONArray()
        s.presets.forEach { p ->
            val segs = JSONArray()
            p.segments.forEach { g ->
                segs.put(
                    JSONObject()
                        .put("id", g.id)
                        .put("title", g.title)
                        .put("body", g.body)
                        .put("jb", g.jailbreak)
                        .put("on", g.on),
                )
            }
            val rules = JSONArray()
            p.rules.forEach { r ->
                rules.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("name", r.name)
                        .put("find", r.find)
                        .put("replace", r.replace)
                        .put("on", r.on),
                )
            }
            arr.put(
                JSONObject()
                    .put("uid", p.uid)
                    .put("name", p.name)
                    .put("all", p.all)
                    .put("jb", p.jb)
                    .put("total", p.total)
                    .put("countAll", p.countAll)
                    .put("countJb", p.countJb)
                    .put("jbFellBack", p.jbFellBack)
                    .put("source", p.source)
                    .put("segments", segs)
                    .put("rules", rules),
            )
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("use", s.usePreset)
            .put("presetId", s.presetId)
            .put("jailbreakOnly", s.jailbreakOnly)
            .put("presets", arr)
    }

    private fun parse(o: JSONObject): State {
        val arr = o.optJSONArray("presets") ?: JSONArray()
        val list = ArrayList<Preset>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val uid = e.optString("uid")
            if (uid.isBlank()) continue

            val segs = ArrayList<Seg>()
            e.optJSONArray("segments")?.let { sa ->
                for (k in 0 until sa.length()) {
                    val g = sa.optJSONObject(k) ?: continue
                    val body = g.optString("body")
                    if (body.isEmpty()) continue
                    segs.add(
                        Seg(
                            id = g.optString("id").ifBlank { "s${k + 1}" },
                            title = g.optString("title"),
                            body = body,
                            jailbreak = g.optBoolean("jb", false),
                            on = g.optBoolean("on", true),
                        ),
                    )
                }
            }

            val rules = ArrayList<Rule>()
            e.optJSONArray("rules")?.let { ra ->
                for (k in 0 until ra.length()) {
                    val r = ra.optJSONObject(k) ?: continue
                    val find = r.optString("find")
                    if (find.isEmpty()) continue
                    rules.add(
                        Rule(
                            id = r.optString("id").ifBlank { "r${k + 1}" },
                            name = r.optString("name"),
                            find = find,
                            replace = r.optString("replace"),
                            on = r.optBoolean("on", true),
                        ),
                    )
                }
            }

            list.add(
                Preset(
                    uid = uid,
                    name = e.optString("name").ifBlank { "未命名" },
                    all = e.optString("all"),
                    jb = e.optString("jb"),
                    total = e.optInt("total", segs.size),
                    countAll = e.optInt("countAll", segs.count { it.on }),
                    countJb = e.optInt("countJb", segs.count { it.on && it.jailbreak }),
                    jbFellBack = e.optBoolean("jbFellBack", false),
                    source = e.optString("source"),
                    segments = segs,
                    rules = rules,
                ),
            )
        }
        return State(
            usePreset = o.optBoolean("use", true),
            presetId = o.optString("presetId"),
            jailbreakOnly = o.optBoolean("jailbreakOnly", true),
            presets = list,
        )
    }
}

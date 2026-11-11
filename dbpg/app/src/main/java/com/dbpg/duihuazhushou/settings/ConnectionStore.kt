package com.dbpg.duihuazhushou.settings

import android.content.Context
import com.dbpg.duihuazhushou.DhpApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 运营商 / 模型 / 风格等「连接配置」的**原生权威数据源**。
 *
 * ## 为什么要有这个类
 *
 * 这些配置本质只是 `baseUrl / apiKey / model / 备注名` 几串字符串，
 * 跟 SillyTavern 兼容层（预设、世界书、正则、宏、变量）没有任何关系。
 * 早期为了让原生设置页和网页版共用一份状态，读写都绕到 WebView 里的
 * Pinia store，结果隐藏引擎一旦被 Chromium 判成后台页，设置页就整体失灵
 * （见 docs/原生化边界.md 二·五）。
 *
 * 现在改成：配置在原生落盘、原生改、原生渲染；引擎只在生成时收到一份
 * 只读镜像（[exportForEngine]），不再被当成数据库使用。**ST 兼容层一个字没动**。
 *
 * ## 数据形状
 *
 * 刻意与 web 端 `apiConfig` / `image` 两个 store 的原始持久化结构同构，
 * 这样首次迁移可以整份搬过来，也不会丢 NovelAI 的历史字段
 * （`novelaiToken` / `novelaiModel` / `endpoint` / `providerId` …）。
 * 不建 Kotlin data class 是有意的：老数据字段很多且在演进，用 JSON 直接
 * 透传比「建一堆可空字段再逐个映射」更不容易丢东西。
 *
 * ```json
 * {
 *   "llm":   { "configs": [], "currentConfigId": "", "providerNotes": {} },
 *   "image": { "apiConfigs": [], "styleConfigs": [], "currentApiId": "",
 *              "currentStyleId": "", "autoImage": {}, "quickContextWindow": 5,
 *              "quickAiOnly": false }
 * }
 * ```
 *
 * ## 三个渲染视图
 *
 * 现有的四个设置页都不直接读这份文档，而是读 `getConfig` / `getImageConfig` /
 * `getImageStyle` 三份「渲染后的快照」。为了页面零改动，这里用 Kotlin 复刻了
 * web 端 `apiConfigSummary` / `imageApiSummary` / `imageStyleSummary` 的字段。
 * 改渲染逻辑时请同步检查 `src/bridge/generateService.ts` 里对应函数。
 */
object ConnectionStore {

    private const val FILE_NAME = "connection_store.json"

    /** 配置类的三个同步取值函数：改走本地，不再问引擎。 */
    val LOCAL_FETCH = setOf("getConfig", "getImageConfig", "getImageStyle")

    /** 配置类写命令：本地执行，不再等引擎回包。其它命令（资产/显示/优化）仍走桥。 */
    val LOCAL_COMMANDS = setOf(
        "saveConn", "addModels", "useModel", "removeModel", "saveParams",
        "llmAddProvider", "llmRenameProvider",
        "imgSaveConn", "imgAddModels", "imgUseApi", "imgRemoveApi", "imgSaveParams",
        "imgAddProvider", "imgRenameProvider",
        "imgAddStyle", "imgUseStyle", "imgRemoveStyle", "imgRenameStyle", "imgSaveStyle",
        "imgSaveAuto", "imgSaveSampling",
        // 2026-11-07 新增：场景隔离 / 渲染引擎 / 尺寸快捷档位
        "imgSetEngine", "imgSaveSize",
        // 2026-11-07 新增：运营商整组删除 + 模型批量删除
        "llmRemoveProvider", "llmRemoveModels", "imgRemoveProvider", "imgRemoveApis",
        // 2026-11-08 新增：LLM 转标签模板库（命名保存，跨风格套用）+ 角色 DNA 库
        "tplSave", "tplRemove", "dnaSave", "dnaRemove",
        // 2026-11-08 新增：出图参考图（发给生图模型的那张，按场景存，实时可换）
        "genRefSet",
        // 2026-11-11 新增：运营商维度编辑（配置页改成「列表 → 详情」后，
        // 保存/加模型/启停都必须按 key 落到那一家，不能再依赖 currentConfigId）
        "llmSaveProviderConn", "llmAddModelsToProvider", "llmSetProviderEnabled",
    )

    private val lock = Any()
    private var file: File? = null
    private var doc: JSONObject = emptyDoc()

    // ─────────────────────────── 生命周期 ───────────────────────────

    /** 幂等。任何入口第一次用到本对象时调一下即可。 */
    fun init(context: Context) = synchronized(lock) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        file = f
        if (f.exists()) {
            runCatching { doc = JSONObject(f.readText()) }
        }
        normalize()
        healCurrentApi()
    }

    /**
     * 启动时把「当前接口」从空壳里捞出来。
     *
     * 老数据（或测试时新建的空运营商）常出现 `currentApiId` 指向一个只填了
     * 地址、还没拉模型的条目；网页自动配图读的正是它，会拿到空 model。
     * 这里自愈到第一个真的带模型的接口。**只在读盘时做一次**：如果在每次
     * 命令后都做，用户刚新建运营商还没来得及拉模型就会被切走。
     */
    private fun healCurrentApi() {
        val image = doc.optJSONObject("image") ?: return
        val apis = arr(image, "apiConfigs")
        fun usable(x: JSONObject?): Boolean =
            x != null && (x.optString("model").isNotEmpty() || x.optString("novelaiModel").isNotEmpty())
        if (usable(findByUid(apis, image.optString("currentApiId")))) return
        for (i in 0 until apis.length()) {
            val x = apis.optJSONObject(i) ?: continue
            if (usable(x)) {
                image.put("currentApiId", x.optString("_uid"))
                persist()
                return
            }
        }
    }

    private fun initFromApp() {
        if (file == null) init(DhpApp.instance)
    }

    /** 本地还没有配置（首次安装 / 清过数据）时返回 true，调用方负责从引擎迁移一次。 */
    fun isEmpty(): Boolean = synchronized(lock) {
        initFromApp()
        arr(doc.optJSONObject("llm"), "configs").length() == 0 &&
            arr(doc.optJSONObject("image"), "apiConfigs").length() == 0
    }

    /**
     * 一次性迁移：接收 web store 的原始 JSON（见 generateService 的 exportNativeConfig）。
     * 只在本地为空时写，避免用户改过配置之后又被旧数据覆盖。
     */
    fun migrateFromEngine(raw: JSONObject): Boolean = synchronized(lock) {
        initFromApp()
        if (!isEmpty()) return false
        val llm = raw.optJSONObject("llm")
        val image = raw.optJSONObject("image")
        if (llm == null && image == null) return false
        doc = emptyDoc()
        if (llm != null) doc.put("llm", llm)
        if (image != null) doc.put("image", image)
        normalize()
        healCurrentApi()
        persist()
        return true
    }

    /** 引擎注入用的只读镜像。风格缩略图/出图参考图体积很大，且引擎只做展示，故剥掉。 */
    fun exportForEngine(): JSONObject = synchronized(lock) {
        initFromApp()
        val copy = JSONObject(doc.toString())
        val img = copy.optJSONObject("image")
        val styles = arr(img, "styleConfigs")
        for (i in 0 until styles.length()) {
            styles.optJSONObject(i)?.apply {
                remove("thumb")
                remove("refImage") // 老数据遗留；风格参考图只作展示，镜像用不上
            }
        }
        img?.remove("genRefs") // 出图参考图是大图，生成走原生，镜像不需要
        return copy
    }

    /** 供生成链路直取当前 LLM 请求参数（不经过 Pinia store）。 */
    fun llmRuntime(): JSONObject = synchronized(lock) {
        initFromApp()
        val cur = currentLlm() ?: return JSONObject()
        return JSONObject()
            .put("baseUrl", cur.optString("baseUrl"))
            .put("apiKey", cur.optString("apiKey"))
            .put("model", cur.optString("model"))
            .put("temperature", cur.optDouble("temperature", 0.8))
            .put("maxTokens", cur.optInt("maxTokens", 8192))
            .put("contextWindow", cur.optInt("contextWindow", 1000000))
            .put("userLookbackTokens", cur.optInt("userLookbackTokens", 200000))
    }

    /**
     * 角色 DNA 的英文外观标签（逗号拼接）。
     *
     * @param ids 本对话勾选的 DNA id；null = 取全部启用项（未配置的对话走默认）。
     * 资产页停用/删除的条目自动跳过（读不到就当没选）。
     */
    fun dnaTags(ids: List<String>?): String = synchronized(lock) {
        initFromApp()
        val list = arr(doc.optJSONObject("image"), "dnaList")
        val out = ArrayList<String>()
        for (i in 0 until list.length()) {
            val d = list.optJSONObject(i) ?: continue
            if (!d.optBoolean("enabled", true)) continue
            if (ids != null && !ids.contains(d.optString("_uid"))) continue
            val tags = d.optString("tags").trim()
            if (tags.isNotEmpty()) out.add(tags)
        }
        return out.joinToString(", ")
    }

    // ─────────────────────────── 同步取值（本地） ───────────────────────────

    fun snapshot(fn: String): JSONObject? = synchronized(lock) {
        initFromApp()
        return when (fn) {
            "getConfig" -> renderConfig()
            "getImageConfig" -> renderImageConfig()
            "getImageStyle" -> renderImageStyle()
            else -> null
        }
    }

    /** 复刻 web 端 apiConfigSummary（generateService.ts:476）。 */
    private fun renderConfig(): JSONObject {
        val llm = doc.optJSONObject("llm") ?: JSONObject()
        val configs = arr(llm, "configs")
        val notes = llm.optJSONObject("providerNotes") ?: JSONObject()
        val curId = llm.optString("currentConfigId")
        val cur = findByUid(configs, curId) ?: configs.optJSONObject(0)

        // 运营商分组规则与 store 的 providers getter 一致：
        // 有 providerId 用之（addModelsToProvider 建出来的一组），否则各自独立。
        val keys = ArrayList<String>()
        val groups = LinkedHashMap<String, JSONArray>()
        for (i in 0 until configs.length()) {
            val c = configs.optJSONObject(i) ?: continue
            val key = llmKey(c)
            if (!groups.containsKey(key)) {
                groups[key] = JSONArray()
                keys.add(key)
            }
            groups[key]!!.put(c)
        }
        val providers = JSONArray()
        val disabled = disabledLlmKeys(llm)
        for (key in keys) {
            val list = groups[key]!!
            val models = JSONArray()
            var hasKey = false
            for (j in 0 until list.length()) {
                val c = list.optJSONObject(j) ?: continue
                if (c.optString("apiKey").isNotEmpty()) hasKey = true
                models.put(
                    JSONObject()
                        .put("uid", c.optString("_uid"))
                        .put("model", c.optString("model"))
                        .put("name", c.optString("_name"))
                        .put("temperature", c.optDouble("temperature", 0.8))
                        .put("maxTokens", c.optInt("maxTokens", 8192))
                        .put("current", c.optString("_uid") == curId),
                )
            }
            providers.put(
                JSONObject()
                    .put("key", key)
                    .put("note", notes.optString(key))
                    .put("baseUrl", list.optJSONObject(0)?.optString("baseUrl") ?: "")
                    // 详情页要能显示/编辑这一家的密钥（本机 UI，默认掩码展示）；
                    // 以前只有顶层当前模型的 apiKey，编辑非当前运营商时取不到。
                    .put("apiKey", list.optJSONObject(0)?.optString("apiKey") ?: "")
                    .put("hasKey", hasKey)
                    .put("enabled", key !in disabled)
                    .put("models", models),
            )
        }
        return JSONObject()
            .put("baseUrl", cur?.optString("baseUrl") ?: "")
            .put("apiKey", cur?.optString("apiKey") ?: "")
            .put("model", cur?.optString("model") ?: "")
            .put("modelName", cur?.optString("_name") ?: "")
            .put("uid", cur?.optString("_uid") ?: "")
            .put(
                "params", JSONObject()
                    .put("temperature", cur?.optDouble("temperature", 0.8) ?: 0.8)
                    .put("maxTokens", cur?.optInt("maxTokens", 8192) ?: 8192)
                    .put("contextWindow", cur?.optInt("contextWindow", 1000000) ?: 1000000)
                    .put("userLookbackTokens", cur?.optInt("userLookbackTokens", 200000) ?: 200000),
            )
            .put("providers", providers)
    }

    /** 复刻 web 端 imageApiSummary（generateService.ts:1086）。 */
    private fun renderImageConfig(): JSONObject {
        val image = doc.optJSONObject("image") ?: JSONObject()
        val apis = arr(image, "apiConfigs")
        val curId = image.optString("currentApiId")
        val cur = findByUid(apis, curId) ?: apis.optJSONObject(0)
        val auto = image.optJSONObject("autoImage") ?: JSONObject()
        val llm = doc.optJSONObject("llm") ?: JSONObject()

        val flat = JSONArray()
        val keys = ArrayList<String>()
        val groups = LinkedHashMap<String, JSONArray>()
        for (i in 0 until apis.length()) {
            val a = apis.optJSONObject(i) ?: continue
            val key = imageKey(a)
            if (!groups.containsKey(key)) {
                groups[key] = JSONArray()
                keys.add(key)
            }
            groups[key]!!.put(a)
            flat.put(
                JSONObject()
                    .put("uid", a.optString("_uid"))
                    .put("name", a.optString("name"))
                    .put("provider", a.optString("provider", "custom"))
                    .put("baseUrl", a.optString("baseUrl"))
                    .put("hasKey", a.optString("apiKey").isNotEmpty() || a.optString("novelaiToken").isNotEmpty())
                    .put("model", a.optString("model").ifEmpty { a.optString("novelaiModel") })
                    .put("current", a.optString("_uid") == curId),
            )
        }
        val providers = JSONArray()
        for (key in keys) {
            val list = groups[key]!!
            val models = JSONArray()
            var hasKey = false
            var current = false
            for (j in 0 until list.length()) {
                val a = list.optJSONObject(j) ?: continue
                if (a.optString("apiKey").isNotEmpty() || a.optString("novelaiToken").isNotEmpty()) hasKey = true
                val isCur = a.optString("_uid") == curId
                if (isCur) current = true
                val model = a.optString("model").ifEmpty { a.optString("novelaiModel") }
                if (model.isNotEmpty() || list.length() == 1) {
                    models.put(
                        JSONObject()
                            .put("uid", a.optString("_uid"))
                            .put("model", model)
                            .put("name", a.optString("name").ifEmpty { model })
                            .put("current", isCur),
                    )
                }
            }
            val first = list.optJSONObject(0)
            providers.put(
                JSONObject()
                    .put("key", key)
                    .put("baseUrl", first?.optString("baseUrl") ?: "")
                    .put("name", first?.optString("name").orEmpty().ifEmpty { hostOf(first?.optString("baseUrl") ?: "").ifEmpty { "(未填地址)" } })
                    .put("hasKey", hasKey)
                    .put("current", current)
                    .put("models", models),
            )
        }
        val llmConfigs = JSONArray()
        val llmArr = arr(llm, "configs")
        val llmNotes = llm.optJSONObject("providerNotes") ?: JSONObject()
        for (i in 0 until llmArr.length()) {
            val c = llmArr.optJSONObject(i) ?: continue
            // 提示词模型选择器要按「运营商 → 模型」两级展开。
            // 运营商名优先用用户自己起的名（providerNotes），没起过就退回域名。
            val key = llmKey(c)
            val providerName = llmNotes.optString(key).ifEmpty { hostOf(c.optString("baseUrl")) }
                .ifEmpty { c.optString("_name") }.ifEmpty { "默认运营商" }
            llmConfigs.put(
                JSONObject()
                    .put("uid", c.optString("_uid"))
                    .put("name", c.optString("_name").ifEmpty { c.optString("model") }.ifEmpty { "未命名" })
                    .put("model", c.optString("model"))
                    .put("providerKey", key)
                    .put("providerName", providerName)
                    .put("current", c.optString("_uid") == llm.optString("currentConfigId")),
            )
        }
        val curKey = if (cur != null) imageKey(cur) else ""
        val apiName = groups[curKey]?.optJSONObject(0)?.optString("name").orEmpty()
            .ifEmpty { hostOf(cur?.optString("baseUrl") ?: "") }
        val style = findByUid(arr(image, "styleConfigs"), image.optString("currentStyleId"))
        val sceneIds = image.optJSONObject("sceneApiIds") ?: JSONObject()

        /**
         * 场景视图：只为 UI 显示，地址/密钥供内部发请求用，界面不展示。
         *
         * 老数据里常有「只填了地址、还没拉模型」的空壳接口，若直接回落当前
         * 接口，界面会显示「未选模型」而用户明明配好了砂糖。没显式选过场景时
         * 优先挑一个真的带模型的接口。
         */
        fun sceneView(scene: String): JSONObject {
            fun hasModel(x: JSONObject?): Boolean =
                x != null && (x.optString("model").isNotEmpty() || x.optString("novelaiModel").isNotEmpty())
            fun firstWithModel(): JSONObject? {
                for (i in 0 until apis.length()) {
                    val x = apis.optJSONObject(i)
                    if (hasModel(x)) return x
                }
                return null
            }
            val a = findByUid(apis, sceneIds.optString(scene))
                ?: if (hasModel(cur)) cur else firstWithModel() ?: cur
            val groupName = if (a != null) groups[imageKey(a)]?.optJSONObject(0)?.optString("name").orEmpty() else ""
            return JSONObject()
                .put("uid", a?.optString("_uid") ?: "")
                .put("apiName", groupName.ifEmpty { a?.optString("name").orEmpty() }
                    .ifEmpty { hostOf(a?.optString("baseUrl") ?: "") }.ifEmpty { "未选运营商" })
                .put("baseUrl", a?.optString("baseUrl") ?: "")
                .put("apiKey", a?.optString("apiKey") ?: "")
                .put("model", a?.optString("model").orEmpty().ifEmpty { a?.optString("novelaiModel") ?: "" })
                .put("engine", if (a != null) engineOf(a) else "custom")
                .put("width", style?.optInt("width", 832) ?: a?.optInt("width", 832) ?: 832)
                .put("height", style?.optInt("height", 1216) ?: a?.optInt("height", 1216) ?: 1216)
                // 优化开关挂在接口上：读同一个场景，写才不会落到别的接口去
                .put("useLlmOptimization", a?.optBoolean("useLlmOptimization", true) ?: true)
                .put("llmConfigId", a?.optString("llmConfigId") ?: "")
        }
        return JSONObject()
            .put("apis", flat)
            .put("providers", providers)
            .put("scenes", JSONObject().put("chat", sceneView("chat")).put("tag", sceneView("tag")))
            .put("sizePreset", image.optString("sizePreset").ifEmpty { "832x1216" })
            .put(
                "current", JSONObject()
                    .put("uid", cur?.optString("_uid") ?: "")
                    .put("apiName", apiName)
                    .put("baseUrl", cur?.optString("baseUrl") ?: "")
                    .put("apiKey", cur?.optString("apiKey") ?: "")
                    .put("model", cur?.optString("model").orEmpty().ifEmpty { cur?.optString("novelaiModel") ?: "" })
                    .put("engine", if (cur != null) engineOf(cur) else "custom")
                    .put("width", style?.optInt("width", 832) ?: cur?.optInt("width", 832) ?: 832)
                    .put("height", style?.optInt("height", 1216) ?: cur?.optInt("height", 1216) ?: 1216)
                    .put("negativePrompt", style?.optString("negativePrompt") ?: "")
                    .put("useLlmOptimization", cur?.optBoolean("useLlmOptimization", true) ?: true)
                    .put("llmConfigId", cur?.optString("llmConfigId") ?: ""),
            )
            .put(
                "auto", JSONObject()
                    .put("enabled", auto.optBoolean("enabled", false))
                    .put("maxPerReply", auto.optInt("maxPerReply", 2))
                    .put("quickContextWindow", image.optInt("quickContextWindow", 5))
                    .put("skipOptimization", auto.optBoolean("skipOptimization", true)),
            )
            .put("llmConfigs", llmConfigs)
    }

    /** 复刻 web 端 imageStyleSummary（generateService.ts:1248）。 */
    private fun renderImageStyle(): JSONObject {
        val image = doc.optJSONObject("image") ?: JSONObject()
        val styles = arr(image, "styleConfigs")
        val curId = image.optString("currentStyleId")
        val style = findByUid(styles, curId) ?: styles.optJSONObject(0)
        val list = JSONArray()
        for (i in 0 until styles.length()) {
            val s = styles.optJSONObject(i) ?: continue
            val thumb = s.optString("thumb")
            list.put(
                JSONObject()
                    .put("uid", s.optString("_uid"))
                    .put("name", s.optString("name").ifEmpty { "未命名风格" })
                    .put("current", s.optString("_uid") == curId)
                    .put("hasThumb", thumb.isNotEmpty())
                    .put("thumb", if (thumb.startsWith("data:")) thumb else ""),
            )
        }
        val curApi = findByUid(arr(image, "apiConfigs"), image.optString("currentApiId"))
            ?: arr(image, "apiConfigs").optJSONObject(0)
        return JSONObject()
            .put("styles", list)
            .put("engine", if (curApi != null) engineOf(curApi) else "custom")
            .put("sizePreset", image.optString("sizePreset").ifEmpty { "832x1216" })
            .put("styleUid", style?.optString("_uid") ?: "")
            .put("styleName", style?.optString("name").orEmpty().ifEmpty { "默认风格" })
            .put("artistTags", style?.optString("artistTags") ?: "")
            .put("qualityTags", style?.optString("qualityTags") ?: "")
            .put("negativePrompt", style?.optString("negativePrompt") ?: "")
            .put("promptTemplate", style?.optString("promptTemplate") ?: "")
            .put("thumb", style?.optString("thumb") ?: "")
            // 出图参考图（NAI 参考图 / Vibe Transfer）：发给生图模型的那张。
            // 按场景存（chat/tag），随时可换；风格里的 thumb 只是给用户看的风格示意。
            .put("genRefs", image.optJSONObject("genRefs") ?: JSONObject())
            // LLM 转标签模板库：命名保存，跨风格套用
            .put("promptTemplates", arr(image, "promptTemplates"))
            // 角色 DNA 库：英文外观锚定，生图时给 LLM 当人物参考
            .put("dnaList", arr(image, "dnaList"))
            .put("sampler", style?.optString("sampler") ?: "")
            .put("steps", style?.optInt("steps", 28) ?: 28)
            .put("cfgScale", style?.optDouble("cfgScale", 6.0) ?: 6.0)
            .put("noiseSchedule", style?.optString("noiseSchedule") ?: "")
            .put("width", style?.optInt("width", 832) ?: 832)
            .put("height", style?.optInt("height", 1216) ?: 1216)
    }

    // ─────────────────────────── 写命令（本地执行） ───────────────────────────

    /**
     * 就地执行一条配置命令并落盘，返回要广播给页面的额外字段。
     *
     * 语义逐条对齐 web 端 `apiCommand` 的各个 case；改这里时请同步检查
     * `src/bridge/generateService.ts` 里同名 case，避免网页版与原生不一致。
     */
    fun apply(cmd: String, a: JSONObject): JSONObject = synchronized(lock) {
        initFromApp()
        val llm = doc.optJSONObject("llm")!!
        val image = doc.optJSONObject("image")!!
        val configs = arr(llm, "configs")
        val apis = arr(image, "apiConfigs")
        val styles = arr(image, "styleConfigs")
        val sceneIds = image.optJSONObject("sceneApiIds") ?: JSONObject().also { image.put("sceneApiIds", it) }
        val reply = JSONObject()
        var curLlm = findByUid(configs, llm.optString("currentConfigId")) ?: configs.optJSONObject(0)
        var curApi = findByUid(apis, image.optString("currentApiId")) ?: apis.optJSONObject(0)

        when (cmd) {
            "saveConn" -> {
                if (curLlm == null) curLlm = addLlmConfig("默认配置")
                patchConnection(curLlm!!, a)
                syncLlmGroup(configs, curLlm!!, a)
                llm.put("currentConfigId", curLlm!!.optString("_uid"))
            }
            "addModels" -> {
                if (curLlm == null) curLlm = addLlmConfig("默认配置")
                patchConnection(curLlm!!, a)
                syncLlmGroup(configs, curLlm!!, a)
                val oldKey = llmKey(curLlm!!)
                val groupId = curLlm!!.optString("providerId").ifBlank { curLlm!!.optString("_uid") }
                curLlm!!.put("providerId", groupId)
                // 老数据第一次加模型时分组 key 会变，把备注/停用标记一起搬过去
                migrateLlmProviderKey(llm, oldKey, llmKey(curLlm!!))
                val models = stringArray(a.optJSONArray("models"))
                var added = 0
                var skipped = 0
                for (m in models) {
                    val exists = (0 until configs.length()).any {
                        val c = configs.optJSONObject(it)
                        c?.optString("providerId") == groupId && c.optString("model") == m
                    }
                    if (exists) { skipped++; continue }
                    if (curLlm!!.optString("model").isEmpty() && added == 0) {
                        curLlm!!.put("model", m).put("_name", m)
                    } else {
                        val c = JSONObject(curLlm!!.toString())
                        c.put("_uid", newId("api")).put("_name", m).put("model", m)
                        c.put("providerId", groupId)
                        configs.put(c)
                    }
                    added++
                }
                reply.put("added", added).put("skipped", skipped)
            }
            "useModel" -> llm.put("currentConfigId", a.optString("uid"))
            "removeModel" -> {
                val uid = a.optString("uid")
                if (removeByUid(configs, uid) && llm.optString("currentConfigId") == uid) {
                    llm.put("currentConfigId", configs.optJSONObject(0)?.optString("_uid") ?: "")
                }
            }
            /**
             * 批量删模型：一次勾选、一条命令，避免几十个模型要长按几十次。
             * 删完把 currentConfigId 指到还活着的第一个，别留悬空 id。
             */
            "llmRemoveModels" -> {
                val uids = stringArray(a.optJSONArray("uids")).toSet()
                for (uid in uids) {
                    if (removeByUid(configs, uid) && llm.optString("currentConfigId") == uid) {
                        llm.put("currentConfigId", "")
                    }
                }
                if (llm.optString("currentConfigId").isBlank()) {
                    llm.put("currentConfigId", configs.optJSONObject(0)?.optString("_uid") ?: "")
                }
                reply.put("removed", uids.size)
            }
            /** 删除整个 LLM 运营商：按 llmKey 分组，连用户起的名一起删。 */
            "llmRemoveProvider" -> {
                val key = a.optString("key")
                var removed = 0
                for (i in configs.length() - 1 downTo 0) {
                    val c = configs.optJSONObject(i) ?: continue
                    if (llmKey(c) == key) {
                        configs.remove(i)
                        removed++
                    }
                }
                llm.optJSONObject("providerNotes")?.remove(key)
                llm.put("currentConfigId", configs.optJSONObject(0)?.optString("_uid") ?: "")
                reply.put("removed", removed)
            }
            /**
             * 保存生成参数。
             *
             * 温度：只写当前模型（用户偶尔对某张卡微调）。
             * 单次上限 / 上下文窗口 / 历史预算：**写入全部模型** ——
             * 用户明确要求「都是百万上下文的模型了，别让我一个一个改」。
             * 填 0 = 不限制（请求里不发 max_tokens / 不做历史裁剪）。
             */
            "saveParams" -> {
                val p = a.optJSONObject("params") ?: JSONObject()
                val c = curLlm ?: return reply
                if (p.has("temperature")) c.put("temperature", p.optDouble("temperature"))
                val shared = listOf("maxTokens", "contextWindow", "userLookbackTokens")
                for (i in 0 until configs.length()) {
                    val x = configs.optJSONObject(i) ?: continue
                    for (k in shared) if (p.has(k)) x.put(k, p.optInt(k))
                }
            }
            "llmAddProvider" -> {
                val name = a.optString("name").ifBlank { hostOf(a.optString("baseUrl")).ifBlank { "新运营商" } }
                val c = addLlmConfig(name)
                c.put("baseUrl", a.optString("baseUrl")).put("apiKey", a.optString("apiKey"))
                llm.put("currentConfigId", c.optString("_uid"))
                val notes = llm.optJSONObject("providerNotes") ?: JSONObject().also { llm.put("providerNotes", it) }
                notes.put(llmKey(c), name)
                reply.put("uid", c.optString("_uid"))
            }
            "llmRenameProvider" -> {
                val notes = llm.optJSONObject("providerNotes") ?: JSONObject().also { llm.put("providerNotes", it) }
                notes.put(a.optString("key"), a.optString("name").trim())
            }
            /**
             * 运营商维度保存连接信息。
             *
             * 旧版 `saveConn` 只改 currentConfigId 指向的那一家，配置页一旦允许
             * 直接编辑「非当前」的运营商，保存就会串台。这里按 key 精确找到整组，
             * 连接信息同步到组内每个模型（与 [syncLlmGroup] 同一语义）。
             */
            "llmSaveProviderConn" -> {
                val key = a.optString("key")
                val group = llmConfigsOfKey(configs, key)
                if (group.isEmpty()) {
                    reply.put("error", "运营商不存在或已被删除")
                } else {
                    for (c in group) patchConnection(c, a)
                    if (a.has("name")) {
                        val notes = llm.optJSONObject("providerNotes")
                            ?: JSONObject().also { llm.put("providerNotes", it) }
                        notes.put(key, a.optString("name").trim())
                    }
                    reply.put("updated", group.size)
                }
            }
            /**
             * 往「指定运营商」批量加模型，不走 currentConfigId。
             *
             * 详情页的模型 Tab 有「+ 添加新模型」，点的是当前正在看的那家；
             * 若沿用 addModels（作用于当前模型所在的组），在非当前运营商下
             * 添加会把模型塞进别家 —— 与「拉取模型永远拉第一家」同源的串台 bug。
             */
            "llmAddModelsToProvider" -> {
                val key = a.optString("key")
                val group = llmConfigsOfKey(configs, key)
                if (group.isEmpty()) {
                    reply.put("error", "运营商不存在或已被删除")
                } else {
                    val seed = group[0]
                    if (a.has("baseUrl") || a.has("apiKey")) patchConnection(seed, a)
                    val oldKey = llmKey(seed)
                    val groupId = seed.optString("providerId").ifBlank { seed.optString("_uid") }
                    seed.put("providerId", groupId)
                    // 同上：老运营商的 key 会从 __own__:uid 变成 uid，
                    // 不搬备注的话用户起的名字会在加完模型后凭空消失。
                    migrateLlmProviderKey(llm, oldKey, llmKey(seed))
                    val models = stringArray(a.optJSONArray("models"))
                    var added = 0
                    var skipped = 0
                    for (m in models) {
                        val exists = (0 until configs.length()).any {
                            val c = configs.optJSONObject(it)
                            c?.optString("providerId") == groupId && c.optString("model") == m
                        }
                        if (exists) { skipped++; continue }
                        if (seed.optString("model").isEmpty() && added == 0) {
                            seed.put("model", m).put("_name", m)
                        } else {
                            val c = JSONObject(seed.toString())
                            c.put("_uid", newId("api")).put("_name", m).put("model", m)
                            c.put("providerId", groupId)
                            configs.put(c)
                        }
                        added++
                    }
                    reply.put("added", added).put("skipped", skipped)
                }
            }
            /**
             * 启用 / 停用一家运营商。
             *
             * 停用不是只改个徽章：如果当前模型正好在这一家，就把 currentConfigId
             * 挪到第一家仍启用的运营商（都停用了就留空，生成侧自然报「未连接」）。
             * 这样开关有真实效果，不会出现「显示停用但还能用」的假状态。
             */
            "llmSetProviderEnabled" -> {
                val key = a.optString("key")
                val enabled = a.optBoolean("enabled", true)
                val dis = llm.optJSONArray("providerDisabled")
                    ?: JSONArray().also { llm.put("providerDisabled", it) }
                for (i in dis.length() - 1 downTo 0) if (dis.optString(i) == key) dis.remove(i)
                if (!enabled) dis.put(key)
                if (!enabled) {
                    val cur = findByUid(configs, llm.optString("currentConfigId"))
                    if (cur != null && llmKey(cur) == key) {
                        var next = ""
                        for (i in 0 until configs.length()) {
                            val c = configs.optJSONObject(i) ?: continue
                            if (!jsonArrayContains(dis, llmKey(c))) {
                                next = c.optString("_uid")
                                break
                            }
                        }
                        llm.put("currentConfigId", next)
                    }
                }
                reply.put("enabled", enabled)
            }

            "imgSaveConn" -> {
                val uid = a.optString("uid").ifBlank { image.optString("currentApiId") }
                val target = findByUid(apis, uid) ?: addImageApi("新接口")
                patchConnection(target, a)
                image.put("currentApiId", target.optString("_uid"))
            }
            "imgAddModels" -> {
                if (curApi == null) curApi = addImageApi("新接口")
                patchConnection(curApi!!, a)
                val baseUrl = a.optString("baseUrl").ifBlank { curApi!!.optString("baseUrl") }
                val models = stringArray(a.optJSONArray("models"))
                var added = 0
                var skipped = 0
                var anchorEmpty = curApi!!.optString("model").isEmpty() && curApi!!.optString("novelaiModel").isEmpty()
                for (m in models) {
                    val exists = (0 until apis.length()).any {
                        val x = apis.optJSONObject(it)
                        x?.optString("baseUrl") == baseUrl &&
                            (x.optString("model").ifEmpty { x.optString("novelaiModel") }) == m
                    }
                    if (exists) { skipped++; continue }
                    if (anchorEmpty) {
                        curApi!!.put("model", m)
                        anchorEmpty = false
                    } else {
                        val x = JSONObject(curApi!!.toString())
                        x.put("_uid", newId("imgapi")).put("name", m).put("model", m)
                        x.put("baseUrl", baseUrl)
                        apis.put(x)
                    }
                    added++
                }
                reply.put("added", added).put("skipped", skipped)
            }
            "imgUseApi" -> {
                val uid = a.optString("uid")
                // 场景隔离：标签生图换运营商/模型不该把对话生图跟着改掉，反之亦然。
                // chat 同时写 currentApiId（网页自动配图与 optimizeImagePrompt 认它）。
                when (a.optString("scope")) {
                    "chat" -> {
                        image.put("currentApiId", uid)
                        sceneIds.put("chat", uid)
                    }
                    "tag" -> sceneIds.put("tag", uid)
                    else -> image.put("currentApiId", uid)
                }
            }
            "imgRemoveApi" -> {
                val uid = a.optString("uid")
                if (removeByUid(apis, uid) && image.optString("currentApiId") == uid) {
                    image.put("currentApiId", apis.optJSONObject(0)?.optString("_uid") ?: "")
                }
                if (sceneIds.optString("chat") == uid) sceneIds.put("chat", "")
                if (sceneIds.optString("tag") == uid) sceneIds.put("tag", "")
            }
            /** 生图模型批量删；同时清掉两个场景槽位里指向被删 uid 的引用。 */
            "imgRemoveApis" -> {
                val uids = stringArray(a.optJSONArray("uids")).toSet()
                for (uid in uids) {
                    removeByUid(apis, uid)
                    if (image.optString("currentApiId") == uid) image.put("currentApiId", "")
                    if (sceneIds.optString("chat") == uid) sceneIds.put("chat", "")
                    if (sceneIds.optString("tag") == uid) sceneIds.put("tag", "")
                }
                if (image.optString("currentApiId").isBlank()) {
                    image.put("currentApiId", apis.optJSONObject(0)?.optString("_uid") ?: "")
                }
                reply.put("removed", uids.size)
            }
            /** 删除整个生图运营商：按 baseUrl 分组（imageKey）整组清掉。 */
            "imgRemoveProvider" -> {
                val key = a.optString("key")
                var removed = 0
                for (i in apis.length() - 1 downTo 0) {
                    val x = apis.optJSONObject(i) ?: continue
                    if (imageKey(x) == key) {
                        val uid = x.optString("_uid")
                        if (sceneIds.optString("chat") == uid) sceneIds.put("chat", "")
                        if (sceneIds.optString("tag") == uid) sceneIds.put("tag", "")
                        apis.remove(i)
                        removed++
                    }
                }
                image.put("currentApiId", apis.optJSONObject(0)?.optString("_uid") ?: "")
                reply.put("removed", removed)
            }
            "imgSetEngine" -> {
                // 同一家运营商（同一 baseUrl）下的模型共享渲染器，避免逐条设。
                val target = findByUid(apis, a.optString("uid").ifBlank { image.optString("currentApiId") }) ?: curApi
                val key = target?.let { imageKey(it) }
                for (i in 0 until apis.length()) {
                    val x = apis.optJSONObject(i) ?: continue
                    if (x === target || (key != null && imageKey(x) == key)) {
                        x.put("engine", a.optString("engine"))
                    }
                }
            }
            "imgSaveSize" -> {
                // 尺寸是「一套配置两处用」：风格页和标签生图页选了同一个尺寸，
                // 同时写进接口与当前风格，读快照时就不会出现两处不一致。
                val w = a.optInt("width", 832)
                val h = a.optInt("height", 1216)
                image.put("sizePreset", "${w}x${h}")
                curApi?.put("width", w)?.put("height", h)
                currentStyle(image)?.put("width", w)?.put("height", h)
            }
            "imgSaveParams" -> {
                val p = a.optJSONObject("params") ?: JSONObject()
                curApi?.let {
                    if (p.has("width")) it.put("width", p.optInt("width"))
                    if (p.has("height")) it.put("height", p.optInt("height"))
                }
                // 风格页保存时同步尺寸，兼容老页面的 imgSaveParams 写法
                currentStyle(image)?.let {
                    if (p.has("width")) it.put("width", p.optInt("width"))
                    if (p.has("height")) it.put("height", p.optInt("height"))
                    listOf("negativePrompt", "artistTags", "qualityTags", "promptTemplate").forEach { k ->
                        if (p.has(k)) it.put(k, p.optString(k))
                    }
                }
                if (p.has("width") && p.has("height")) {
                    image.put("sizePreset", "${p.optInt("width")}x${p.optInt("height")}")
                }
            }
            "imgAddProvider" -> {
                val name = a.optString("name").ifBlank { hostOf(a.optString("baseUrl")).ifBlank { "新运营商" } }
                val x = addImageApi(name)
                x.put("baseUrl", a.optString("baseUrl")).put("apiKey", a.optString("apiKey"))
                x.put("model", "").put("novelaiModel", "")
                image.put("currentApiId", x.optString("_uid"))
                reply.put("uid", x.optString("_uid"))
            }
            "imgRenameProvider" -> {
                val key = a.optString("key")
                val name = a.optString("name").trim()
                for (i in 0 until apis.length()) {
                    val x = apis.optJSONObject(i) ?: continue
                    if (imageKey(x) == key) x.put("name", name)
                }
            }

            "imgAddStyle" -> {
                val s = addStyle(a.optString("name").ifBlank { "新风格" })
                image.put("currentStyleId", s.optString("_uid"))
                reply.put("uid", s.optString("_uid"))
            }
            "imgUseStyle" -> image.put("currentStyleId", a.optString("uid"))
            "imgRemoveStyle" -> {
                if (styles.length() > 1) {
                    val uid = a.optString("uid")
                    if (removeByUid(styles, uid) && image.optString("currentStyleId") == uid) {
                        image.put("currentStyleId", styles.optJSONObject(0)?.optString("_uid") ?: "")
                    }
                }
            }
            "imgRenameStyle" -> findByUid(styles, a.optString("uid"))?.put("name", a.optString("name"))
            "imgSaveStyle" -> {
                val uid = a.optString("uid").ifBlank { image.optString("currentStyleId") }
                val s = findByUid(styles, uid) ?: return reply
                val p = a.optJSONObject("style") ?: JSONObject()
                // 风格只管提示词 + 展示用参考图（thumb 只给人看，不发模型）。
                // 发给模型的「出图参考图」在 genRefs 里，按场景存，不跟风格走。
                listOf("artistTags", "qualityTags", "negativePrompt", "promptTemplate", "thumb").forEach { k ->
                    if (p.has(k)) s.put(k, p.optString(k))
                }
            }
            "imgSaveAuto" -> {
                val p = a.optJSONObject("auto") ?: JSONObject()
                val auto = image.optJSONObject("autoImage") ?: JSONObject().also { image.put("autoImage", it) }
                if (p.has("enabled")) auto.put("enabled", p.optBoolean("enabled"))
                if (p.has("maxPerReply")) auto.put("maxPerReply", p.optInt("maxPerReply"))
                if (p.has("quickContextWindow")) image.put("quickContextWindow", p.optInt("quickContextWindow"))
                if (p.has("aiOnly")) image.put("quickAiOnly", p.optBoolean("aiOnly"))
                findByUid(apis, a.optString("uid").ifBlank { image.optString("currentApiId") })?.let {
                    if (p.has("useLlmOptimization")) it.put("useLlmOptimization", p.optBoolean("useLlmOptimization"))
                    if (p.has("llmConfigId")) it.put("llmConfigId", p.optString("llmConfigId"))
                }
            }
            "imgSaveSampling" -> {
                val uid = a.optString("uid").ifBlank { image.optString("currentStyleId") }
                val s = findByUid(styles, uid) ?: return reply
                val p = a.optJSONObject("sampling") ?: JSONObject()
                if (p.has("steps")) s.put("steps", p.optInt("steps"))
                // CFG 允许小数（NovelAI 常用 5.5 / 6.5），别用 optInt 截断
                if (p.has("cfgScale")) s.put("cfgScale", p.optDouble("cfgScale"))
                if (p.has("sampler")) s.put("sampler", p.optString("sampler"))
                if (p.has("noiseSchedule")) s.put("noiseSchedule", p.optString("noiseSchedule"))
            }

            // ── LLM 转标签模板库：命名保存，风格页套用（跨风格共享） ──
            "tplSave" -> {
                val name = a.optString("name").trim()
                if (name.isEmpty()) return reply
                val tpls = arr(image, "promptTemplates")
                val text = a.optString("text")
                val uid = a.optString("uid")
                val hit = if (uid.isNotBlank()) findByUid(tpls, uid)
                else (0 until tpls.length()).mapNotNull { tpls.optJSONObject(it) }
                    .firstOrNull { it.optString("name") == name }
                if (hit != null) {
                    hit.put("name", name).put("text", text).put("updatedAt", System.currentTimeMillis())
                    reply.put("uid", hit.optString("_uid"))
                } else {
                    val t = JSONObject()
                        .put("_uid", newId("tpl")).put("name", name).put("text", text)
                        .put("createdAt", System.currentTimeMillis())
                    tpls.put(t)
                    reply.put("uid", t.optString("_uid"))
                }
            }
            "tplRemove" -> {
                removeByUid(arr(image, "promptTemplates"), a.optString("uid"))
                reply.put("removed", true)
            }

            // ── 角色 DNA 库：英文外观锚定，生图时给 LLM 当人物参考 ──
            "dnaSave" -> {
                val name = a.optString("name").trim()
                if (name.isEmpty()) return reply
                val list = arr(image, "dnaList")
                val uid = a.optString("uid")
                val hit = if (uid.isNotBlank()) findByUid(list, uid)
                else (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                    .firstOrNull { it.optString("name") == name }
                if (hit != null) {
                    if (a.has("name")) hit.put("name", name)
                    if (a.has("tags")) hit.put("tags", a.optString("tags"))
                    if (a.has("note")) hit.put("note", a.optString("note"))
                    if (a.has("enabled")) hit.put("enabled", a.optBoolean("enabled"))
                    hit.put("updatedAt", System.currentTimeMillis())
                    reply.put("uid", hit.optString("_uid"))
                } else {
                    val d = JSONObject()
                        .put("_uid", newId("dna")).put("name", name)
                        .put("tags", a.optString("tags")).put("note", a.optString("note"))
                        .put("enabled", a.optBoolean("enabled", true))
                        .put("createdAt", System.currentTimeMillis())
                    list.put(d)
                    reply.put("uid", d.optString("_uid"))
                }
            }
            "dnaRemove" -> {
                removeByUid(arr(image, "dnaList"), a.optString("uid"))
                reply.put("removed", true)
            }
            "genRefSet" -> {
                // 出图参考图槽位：scene=chat/tag，image 传 "" 表示清图；
                // strength/info 不传则保持不变。每次改完立即落盘，生图即时生效。
                val scene = a.optString("scene").ifBlank { "chat" }
                val root = image.optJSONObject("genRefs") ?: JSONObject().also { image.put("genRefs", it) }
                val slot = root.optJSONObject(scene) ?: JSONObject().also { root.put(scene, it) }
                if (a.has("image")) slot.put("image", a.optString("image"))
                if (a.has("strength")) slot.put("strength", a.optDouble("strength", 0.6))
                if (a.has("info")) slot.put("info", a.optDouble("info", 0.7))
                reply.put("ok", true)
            }
        }
        normalize()
        persist()
        return reply
    }

    // ─────────────────────────── 内部工具 ───────────────────────────

    private fun emptyDoc() = JSONObject()
        .put("llm", JSONObject().put("configs", JSONArray()).put("currentConfigId", "").put("providerNotes", JSONObject()))
        .put(
            "image", JSONObject()
                .put("apiConfigs", JSONArray()).put("styleConfigs", JSONArray())
                .put("currentApiId", "").put("currentStyleId", "")
                .put("autoImage", JSONObject().put("enabled", false).put("maxPerReply", 2).put("skipOptimization", true))
                .put("quickContextWindow", 5).put("quickAiOnly", false)
                // 对话生图 / 标签生图各记各的选中接口，互不干扰。
                // 为空时回落到 currentApiId（老数据、网页自动配图都走它）。
                .put("sceneApiIds", JSONObject().put("chat", "").put("tag", ""))
                .put("sizePreset", "832x1216"),
        )

    /** 补齐缺省容器与默认条目，保证任何渲染/命令都不会踩空。 */
    private fun normalize() {
        val llm = doc.optJSONObject("llm") ?: JSONObject().also { doc.put("llm", it) }
        if (llm.optJSONArray("configs") == null) llm.put("configs", JSONArray())
        if (llm.optJSONObject("providerNotes") == null) llm.put("providerNotes", JSONObject())
        val image = doc.optJSONObject("image") ?: JSONObject().also { doc.put("image", it) }
        if (image.optJSONArray("apiConfigs") == null) image.put("apiConfigs", JSONArray())
        if (image.optJSONArray("styleConfigs") == null) image.put("styleConfigs", JSONArray())
        if (image.optJSONArray("promptTemplates") == null) image.put("promptTemplates", JSONArray())
        if (image.optJSONArray("dnaList") == null) image.put("dnaList", JSONArray())
        if (image.optJSONObject("autoImage") == null) image.put("autoImage", JSONObject())
        if (image.optJSONObject("sceneApiIds") == null) {
            image.put("sceneApiIds", JSONObject().put("chat", "").put("tag", ""))
        }
        // 老数据没有 engine 字段：从 web 端原有的 provider 推断，
        // 这样「砂糖」这类 NovelAI 中转不用用户重选就能走对渲染器。
        val apis = arr(image, "apiConfigs")
        for (i in 0 until apis.length()) {
            val a = apis.optJSONObject(i) ?: continue
            if (a.optString("engine").isEmpty()) a.put("engine", engineOf(a))
        }
    }

    /**
     * 渲染引擎默认值：优先看用户显式选过的 `engine`，其次复刻 web 端
     * `provider` 的语义（`novelai-relay` / `local` / `dall-e` / `custom`）。
     * 模型名以 `nai-diffusion` 开头也算 NovelAI 格式，避免老接口漏判。
     */
    private fun engineOf(a: JSONObject): String {
        val explicit = a.optString("engine").trim()
        if (explicit.isNotEmpty()) return explicit
        val p = a.optString("provider").trim()
        if (p in setOf("novelai", "novelai-relay", "local", "custom", "dall-e")) return p
        val model = a.optString("model").ifEmpty { a.optString("novelaiModel") }
        return when {
            p.contains("novelai") || model.startsWith("nai-diffusion") -> "novelai-relay"
            a.optString("baseUrl").contains("novelai.net") -> "novelai"
            else -> "custom"
        }
    }

    private fun persist() {
        runCatching { file?.writeText(doc.toString()) }
    }

    private fun currentLlm(): JSONObject? {
        val llm = doc.optJSONObject("llm") ?: return null
        return findByUid(arr(llm, "configs"), llm.optString("currentConfigId"))
            ?: arr(llm, "configs").optJSONObject(0)
    }

    private fun currentStyle(image: JSONObject): JSONObject? =
        findByUid(arr(image, "styleConfigs"), image.optString("currentStyleId"))
            ?: arr(image, "styleConfigs").optJSONObject(0)

    private fun addLlmConfig(name: String): JSONObject {
        val llm = doc.optJSONObject("llm")!!
        val uid = newId("api")
        val c = JSONObject()
            .put("_uid", uid).put("_name", name)
            // 从创建起就把 providerId 定死成自己的 uid：分组 key 以后永远不会
            // 因为「加了第一个模型」而从 __own__:uid 漂移到 uid（那样会丢掉
            // providerNotes 里的名字，详情页的 key 也会失效被踢回列表）。
            .put("providerId", uid)
            .put("baseUrl", "").put("apiKey", "").put("model", "")
            .put("temperature", 0.8).put("maxTokens", 8192)
            .put("contextWindow", 1000000).put("userLookbackTokens", 200000)
        arr(llm, "configs").put(c)
        llm.put("currentConfigId", uid)
        return c
    }

    private fun addImageApi(name: String): JSONObject {
        val image = doc.optJSONObject("image")!!
        val x = JSONObject()
            .put("_uid", newId("imgapi")).put("name", name).put("provider", "custom")
            .put("baseUrl", "").put("apiKey", "").put("model", "")
        arr(image, "apiConfigs").put(x)
        image.put("currentApiId", x.optString("_uid"))
        return x
    }

    private fun addStyle(name: String): JSONObject {
        val image = doc.optJSONObject("image")!!
        val s = JSONObject()
            .put("_uid", newId("sty")).put("name", name)
            .put("sampler", "k_euler_ancestral").put("steps", 28)
            .put("width", 832).put("height", 1216).put("cfgScale", 6)
            .put("noiseSchedule", "karras")
            .put("artistTags", "").put("qualityTags", "very aesthetic, masterpiece, no text, best quality, absurdres")
            .put("negativePrompt", "").put("promptTemplate", "").put("thumb", "")
        arr(image, "styleConfigs").put(s)
        return s
    }

    /** 改一家运营商的连接信息时，同步到它下面所有模型（web 的 updateProvider 语义）。 */
    private fun syncLlmGroup(configs: JSONArray, cur: JSONObject, a: JSONObject) {
        val key = llmKey(cur)
        for (i in 0 until configs.length()) {
            val c = configs.optJSONObject(i) ?: continue
            if (c === cur) continue
            if (llmKey(c) == key || c.optString("providerId") == cur.optString("providerId")) {
                patchConnection(c, a)
            }
        }
    }

    private fun patchConnection(target: JSONObject, a: JSONObject) {
        if (a.has("baseUrl")) target.put("baseUrl", a.optString("baseUrl"))
        if (a.has("apiKey")) target.put("apiKey", a.optString("apiKey"))
    }

    private fun arr(o: JSONObject?, key: String): JSONArray = o?.optJSONArray(key) ?: JSONArray()

    private fun findByUid(a: JSONArray, uid: String): JSONObject? {
        if (uid.isEmpty()) return null
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("_uid") == uid) return o
        }
        return null
    }

    private fun removeByUid(a: JSONArray, uid: String): Boolean {
        for (i in 0 until a.length()) {
            if (a.optJSONObject(i)?.optString("_uid") == uid) {
                a.remove(i)
                return true
            }
        }
        return false
    }

    private fun stringArray(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            val s = a.optString(i)
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }

    /** 与 apiConfig.ts 的 providerKeyOf 一致：显式 providerId 优先，否则各自独立。 */
    private fun llmKey(c: JSONObject): String =
        c.optString("providerId").ifBlank { "__own__:" + c.optString("_uid") }

    /** 按分组 key 取出这一家下面的全部模型配置（顺序与落盘一致）。 */
    private fun llmConfigsOfKey(configs: JSONArray, key: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (i in 0 until configs.length()) {
            val c = configs.optJSONObject(i) ?: continue
            if (llmKey(c) == key) out.add(c)
        }
        return out
    }

    /** 停用的运营商 key 集合；老数据没有这个字段 = 全部启用（向上兼容）。 */
    private fun disabledLlmKeys(llm: JSONObject): Set<String> {
        val a = llm.optJSONArray("providerDisabled") ?: return emptySet()
        val out = HashSet<String>()
        for (i in 0 until a.length()) {
            val k = a.optString(i)
            if (k.isNotEmpty()) out.add(k)
        }
        return out
    }

    private fun jsonArrayContains(a: JSONArray, value: String): Boolean {
        for (i in 0 until a.length()) if (a.optString(i) == value) return true
        return false
    }

    /**
     * 老运营商第一次加模型时，分组 key 会从 `__own__:uid` 变成 `uid`。
     * 按 key 存的备注与停用标记必须跟着搬，否则改名丢失、停用状态失效。
     */
    private fun migrateLlmProviderKey(llm: JSONObject, oldKey: String, newKey: String) {
        if (oldKey == newKey) return
        llm.optJSONObject("providerNotes")?.let { notes ->
            if (notes.has(oldKey)) {
                notes.put(newKey, notes.optString(oldKey))
                notes.remove(oldKey)
            }
        }
        llm.optJSONArray("providerDisabled")?.let { dis ->
            for (i in 0 until dis.length()) if (dis.optString(i) == oldKey) dis.put(i, newKey)
        }
    }

    /** 与 imageApiSummary 一致：生图按去尾斜杠的 baseUrl 分组，空地址各自独立。 */
    private fun imageKey(a: JSONObject): String {
        val u = a.optString("baseUrl").trim().trimEnd('/')
        return u.ifEmpty { "__own__:" + a.optString("_uid") }
    }

    private fun hostOf(url: String): String = runCatching {
        val s = url.trim()
        if (s.isEmpty()) return ""
        java.net.URI(s).host ?: ""
    }.getOrDefault("")

    private fun newId(prefix: String): String = "$prefix-" + UUID.randomUUID().toString()
}

package com.dbpg.duihuazhushou.session

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 每个对话自己的「资产配置」：引用了哪几本世界书、哪个预设、哪些正则组、哪些 DNA。
 *
 * ## 为什么不塞进会话 JSON
 * 会话文件是 ST 兼容数据（导入/导出/分支都按它算），资产引用是**观看/生成偏好**，
 * 改引用不应该弄脏会话的 updatedAt，也不该跟着导出走。单开一个小文件最干净。
 *
 * ## 与资产页「启用」的关系（重要）
 *   - 资产页的「启用」＝**这个资产在对话里可不可选**（可见性基准）；
 *   - 本文件记录**每个对话勾了哪几项**（一次配置长期留存，换对话各归各）；
 *   - 生效集合＝本文件的选择 ∩ 资产页仍启用的项。资产页停用/删除后，
 *     对话里该项自动失效（读不到就当没选），再启用回来选择还在。
 *
 * 世界书只记「用不用这本书」，书内条目怎么改、怎么触发，全归书自己管。
 */
object SessionBindingStore {

    private const val FILE_NAME = "session_bindings.json"

    private fun file(ctx: Context): File =
        File(ctx.applicationContext.filesDir, FILE_NAME)

    /** 一个对话的资产引用。空集合＝跟随全局（资产页当前启用的全部）。 */
    data class Binding(
        /**
         * 这个对话扮演谁（角色卡 uid）。
         *
         * ★ 以前会话完全不记角色：先跟 A 聊、再跟 B 新建，回 A 的旧会话继续发，
         *   用的却是 B 的卡。这个字段就是修它的落点 —— 打开会话时读出来，
         *   生成时按请求带给引擎，不再依赖网页的全局 currentCharacterId。
         *
         * null = 老会话没记过（按全局当前角色走）。
         */
        val characterId: String? = null,
        /** 角色显示名：聊天头像按名字去原生卡库找图，不用再问网页 */
        val characterName: String? = null,
        /** 引用的世界书 id；null = 没配置过（跟随全局），空列表 = 明确不引任何书 */
        val worldBookIds: List<String>? = null,
        /** 引用的预设 id；null = 跟随全局 */
        val presetId: String? = null,
        /** 引用的正则组 id */
        val regexGroupIds: List<String>? = null,
        /** 引用的角色 DNA id */
        val dnaIds: List<String>? = null,
        /**
         * 这个对话单独用的 LLM 运营商（`llm.configs[]._uid`）。
         *
         * null = 跟随（群成员先跟群、再跟全局）。用户要的是「默认跟随，
         * 想单独改的时候留个口子」，所以只有显式选过才会写进来。
         */
        val apiConfigId: String? = null,
    ) {
        val isDefault: Boolean
            get() = characterId == null && characterName == null && worldBookIds == null && presetId == null &&
                regexGroupIds == null && dnaIds == null && apiConfigId == null
    }

    private val lock = Any()

    fun load(ctx: Context, sessionId: String): Binding = synchronized(lock) {
        if (sessionId.isBlank()) return Binding()
        val root = readRoot(ctx)
        val o = root.optJSONObject(sessionId) ?: return Binding()
        return Binding(
            characterId = if (o.has("characterId")) o.optString("characterId").ifEmpty { null } else null,
            characterName = if (o.has("characterName")) o.optString("characterName").ifEmpty { null } else null,
            worldBookIds = o.optJSONArray("worldBookIds")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } },
            presetId = if (o.has("presetId")) o.optString("presetId") else null,
            regexGroupIds = o.optJSONArray("regexGroupIds")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } },
            dnaIds = o.optJSONArray("dnaIds")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } },
            apiConfigId = if (o.has("apiConfigId")) o.optString("apiConfigId").ifEmpty { null } else null,
        )
    }

    fun save(ctx: Context, sessionId: String, b: Binding): Unit = synchronized(lock) {
        if (sessionId.isBlank()) return
        val root = readRoot(ctx)
        val o = JSONObject()
        b.characterId?.let { o.put("characterId", it) }
        b.characterName?.let { o.put("characterName", it) }
        b.worldBookIds?.let { o.put("worldBookIds", JSONArray(it)) }
        b.presetId?.let { o.put("presetId", it) }
        b.regexGroupIds?.let { o.put("regexGroupIds", JSONArray(it)) }
        b.dnaIds?.let { o.put("dnaIds", JSONArray(it)) }
        b.apiConfigId?.let { o.put("apiConfigId", it) }
        root.put(sessionId, o)
        runCatching { file(ctx).writeText(root.toString()) }
    }

    /** 会话删除时清掉它的绑定，不留孤儿数据。 */
    fun clear(ctx: Context, sessionId: String): Unit = synchronized(lock) {
        if (sessionId.isBlank()) return
        val root = readRoot(ctx)
        if (root.remove(sessionId) != null) {
            runCatching { file(ctx).writeText(root.toString()) }
        }
    }

    private fun readRoot(ctx: Context): JSONObject {
        val f = file(ctx)
        if (!f.exists()) return JSONObject()
        return runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
    }
}

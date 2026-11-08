package com.dbpg.stcore.session

import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话数据模型（原生权威存储用）。
 *
 * 设计目标只有一个：**过一遍原生不能丢任何字段**。
 *
 * 做法：
 *   - 只把原生真正会读会改的字段强类型化（id / title / user / ai / 时间…）；
 *   - 每个对象额外保留一份 `raw`（原始 JSON 文本），写回时先铺 raw 再覆盖已知字段。
 *
 * 这样即使 web 侧以后加了新字段（比如新的摘要策略、配图存储位置），
 * 老版本 App 打开再保存也不会把它抹掉 —— 这是迁移能回退的前提。
 *
 * 为什么不用 data class 的 copy 语义做合并：字段一多，容易漏。
 * 用 raw 兜底是"默认全保留、显式才覆盖"，漏不了。
 */

/** 一条消息的历史版本（web: MessageVariant）。 */
data class MessageVariant(
    val user: String = "",
    val ai: String = "",
    val timestamp: Long = 0L,
    /** 原始 JSON，兜底用 */
    val raw: String? = null,
) {
    fun toJson(): JSONObject {
        val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        o.put("user", user)
        o.put("ai", ai)
        o.put("timestamp", timestamp)
        return o
    }

    companion object {
        fun parse(o: JSONObject?): MessageVariant {
            if (o == null) return MessageVariant()
            return MessageVariant(
                user = o.optString("user", ""),
                ai = o.optString("ai", ""),
                timestamp = optLong(o, "timestamp"),
                raw = o.toString(),
            )
        }
    }
}

/** 配图附件（web: ImageAttachment）。 */
data class ActionImage(
    val id: String = "",
    val description: String = "",
    val markdownIndex: Int = 0,
    /** 原始生成提示词（重新生图时原样重用，不能拿截断过的 description 代替） */
    val prompt: String? = null,
    /** 图片资产库 id（新流程会话里只存这个） */
    val assetId: String? = null,
    /** 内联图片地址（老数据靠它显示） */
    val dataUrl: String? = null,
    /**
     * 原生落盘后的本地文件路径。
     *
     * web 不认识这个字段，所以写回时**只在非空时**加上，且不会覆盖 web 的字段；
     * 它只是给原生渲染用的一条捷径（避免每次都从 dataUrl 解码）。
     */
    val localPath: String? = null,
    val raw: String? = null,
) {
    fun toJson(): JSONObject {
        val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        o.put("id", id)
        o.put("description", description)
        o.put("markdownIndex", markdownIndex)
        putIfNotNull(o, "prompt", prompt)
        putIfNotNull(o, "assetId", assetId)
        putIfNotNull(o, "dataUrl", dataUrl)
        // localPath 是原生私有字段：web 读到会忽略，但为了"交回 web 也不出错"，
        // 只用带前缀的键名，避免和将来的 web 字段撞名。
        putIfNotNull(o, "nativeLocalPath", localPath)
        return o
    }

    companion object {
        fun parse(o: JSONObject?): ActionImage {
            if (o == null) return ActionImage()
            return ActionImage(
                id = o.optString("id", ""),
                description = o.optString("description", ""),
                markdownIndex = o.optInt("markdownIndex", 0),
                prompt = o.optStringOrNull("prompt"),
                assetId = o.optStringOrNull("assetId"),
                dataUrl = o.optStringOrNull("dataUrl"),
                localPath = o.optStringOrNull("nativeLocalPath"),
                raw = o.toString(),
            )
        }

        fun parseArray(arr: JSONArray?): List<ActionImage> {
            if (arr == null || arr.length() == 0) return emptyList()
            val out = ArrayList<ActionImage>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(parse(it)) }
            }
            return out
        }
    }
}

/** 一轮对话（web: Action）。 */
data class SessionAction(
    val id: String = "",
    val user: String = "",
    val ai: String = "",
    val timestamp: Long = 0L,
    val images: List<ActionImage> = emptyList(),
    val variants: List<MessageVariant> = emptyList(),
    val variantIndex: Int = 0,
    val raw: String? = null,
) {
    fun toJson(): JSONObject {
        val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        o.put("id", id)
        o.put("user", user)
        o.put("ai", ai)
        o.put("timestamp", timestamp)
        if (images.isEmpty()) {
            // 空数组也写：web 的读取路径有时会 Array.isArray 判断
            o.put("images", JSONArray())
        } else {
            o.put("images", JSONArray().also { a -> images.forEach { a.put(it.toJson()) } })
        }
        if (variants.isNotEmpty()) {
            o.put("variants", JSONArray().also { a -> variants.forEach { a.put(it.toJson()) } })
            o.put("variantIndex", variantIndex)
        }
        return o
    }

    companion object {
        fun parse(o: JSONObject?): SessionAction {
            if (o == null) return SessionAction()
            val vs = o.optJSONArray("variants")
            val variants = if (vs == null) emptyList() else
                List(vs.length()) { i -> MessageVariant.parse(vs.optJSONObject(i)) }
            return SessionAction(
                id = o.optString("id", ""),
                user = o.optString("user", ""),
                ai = o.optString("ai", ""),
                timestamp = optLong(o, "timestamp"),
                images = ActionImage.parseArray(o.optJSONArray("images")),
                variants = variants,
                variantIndex = o.optInt("variantIndex", 0),
                raw = o.toString(),
            )
        }
    }
}

/** 生成中的草稿（web: AdventureSession.draft）。 */
data class SessionDraft(
    val user: String = "",
    val ai: String = "",
    val updatedAt: Long = 0L,
)

/** 会话文档（web: AdventureSession）。 */
data class SessionDoc(
    val id: String = "",
    val title: String = "",
    val sceneDescription: String = "",
    val actions: List<SessionAction> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val draft: SessionDraft? = null,
    /**
     * 自动摘要（原生私有扩展，JSON 键名 `nativeSummaries`）。
     *
     * web 不认识这个字段，但 raw 兜底机制保证：网页写回会话时不会把它抹掉，
     * 老版本 App 读到也不会崩（parse 拿不到就是空列表）。
     */
    val summaries: List<SummaryBlock> = emptyList(),
    /**
     * 当前格式版本。
     *
     * 现在恒为 1；加字段不需要升版本（靠 raw 兜底），
     * 只有"语义变了、老代码读会出错"时才升，并在读取处做兼容分支。
     */
    val schemaVersion: Int = 1,
    val raw: String? = null,
) {
    /** 列表页需要的最小信息。 */
    fun toIndexEntry(): SessionIndexEntry = SessionIndexEntry(
        id = id,
        title = title,
        count = actions.size,
        updatedAt = updatedAt,
        createdAt = createdAt,
        preview = actions.lastOrNull()?.let { it.ai.ifBlank { it.user } }?.take(80).orEmpty(),
    )

    fun toJson(): JSONObject {
        val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        o.put("id", id)
        o.put("title", title)
        o.put("sceneDescription", sceneDescription)
        o.put("actions", JSONArray().also { a -> actions.forEach { a.put(it.toJson()) } })
        o.put("createdAt", createdAt)
        o.put("updatedAt", updatedAt)
        o.put("schemaVersion", schemaVersion)
        if (draft == null) o.remove("draft") else
            o.put(
                "draft",
                JSONObject()
                    .put("user", draft.user)
                    .put("ai", draft.ai)
                    .put("updatedAt", draft.updatedAt),
            )
        // 摘要为空时删键而不是写空数组：避免让"从没用过摘要"的会话多背一个字段
        if (summaries.isEmpty()) o.remove(KEY_SUMMARIES) else
            o.put(KEY_SUMMARIES, JSONArray().also { a -> summaries.forEach { a.put(it.toJson()) } })
        return o
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        /** 原生私有键名前缀：和 nativeLocalPath 一样，避免和 web 将来字段撞名。 */
        private const val KEY_SUMMARIES = "nativeSummaries"

        /** 解析失败返回 null（坏文件不该让整个列表崩）。 */
        fun parse(text: String?): SessionDoc? {
            val t = text?.trim()
            if (t.isNullOrEmpty()) return null
            val o = runCatching { JSONObject(t) }.getOrNull() ?: return null
            return parse(o)
        }

        fun parse(o: JSONObject): SessionDoc {
            val arr = o.optJSONArray("actions")
            val actions = if (arr == null) emptyList() else
                List(arr.length()) { i -> SessionAction.parse(arr.optJSONObject(i)) }
            val d = o.optJSONObject("draft")
            return SessionDoc(
                id = o.optString("id", ""),
                title = o.optString("title", ""),
                sceneDescription = o.optString("sceneDescription", ""),
                actions = actions,
                createdAt = optLong(o, "createdAt"),
                updatedAt = optLong(o, "updatedAt"),
                draft = d?.let {
                    SessionDraft(
                        user = it.optString("user", ""),
                        ai = it.optString("ai", ""),
                        updatedAt = optLong(it, "updatedAt"),
                    )
                },
                summaries = SummaryBlock.parseArray(o.optJSONArray(KEY_SUMMARIES)),
                schemaVersion = o.optInt("schemaVersion", 1),
                raw = o.toString(),
            )
        }
    }
}

/** 列表索引条目（index.json 里的一项）。 */
data class SessionIndexEntry(
    val id: String,
    val title: String = "",
    val count: Int = 0,
    val updatedAt: Long = 0L,
    val createdAt: Long = 0L,
    /** 最后一条消息的开头，列表页做副标题 */
    val preview: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("count", count)
        .put("updatedAt", updatedAt)
        .put("createdAt", createdAt)
        .put("preview", preview)

    companion object {
        fun parse(o: JSONObject?): SessionIndexEntry? {
            if (o == null) return null
            val id = o.optString("id", "")
            if (id.isEmpty()) return null
            return SessionIndexEntry(
                id = id,
                title = o.optString("title", ""),
                count = o.optInt("count", 0),
                updatedAt = optLong(o, "updatedAt"),
                createdAt = optLong(o, "createdAt"),
                preview = o.optString("preview", ""),
            )
        }

        fun parseArray(text: String?): List<SessionIndexEntry> {
            val t = text?.trim()
            if (t.isNullOrEmpty()) return emptyList()
            val arr = runCatching { JSONArray(t) }.getOrNull() ?: return emptyList()
            val out = ArrayList<SessionIndexEntry>(arr.length())
            for (i in 0 until arr.length()) {
                parse(arr.optJSONObject(i))?.let { out.add(it) }
            }
            return out
        }

        fun toJsonArray(list: List<SessionIndexEntry>): String =
            JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()
    }
}

// ---------------------------------------------------------------- 小工具

/** 时间戳可能是数字、浮点、字符串或 ISO 时间，这里统一成毫秒。 */
internal fun optLong(o: JSONObject, key: String): Long {
    if (!o.has(key) || o.isNull(key)) return 0L
    val v = o.opt(key)
    return when (v) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull() ?: parseIsoTime(v.trim())
        else -> 0L
    }
}

/** 空字符串一律当成 null，避免写回时把 web 的"缺省"变成 ""。 */
internal fun JSONObject.optStringOrNull(key: String): String? {
    val s = optString(key, "")
    return if (s.isEmpty()) null else s
}

internal fun putIfNotNull(o: JSONObject, key: String, v: String?) {
    if (v != null) o.put(key, v)
}

/**
 * ISO-8601 → 毫秒。
 *
 * 不用 java.time.Instant：它是 API 26 才有的，minSdk 是 24，
 * 在 24/25 上直接 NoClassDefFoundError。SimpleDateFormat 全版本可用。
 */
private fun parseIsoTime(text: String): Long {
    if (text.isEmpty()) return 0L
    val patterns = arrayOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd HH:mm:ss",
    )
    for (p in patterns) {
        val r = runCatching {
            java.text.SimpleDateFormat(p, java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(text)?.time
        }.getOrNull()
        if (r != null && r > 0) return r
    }
    return 0L
}

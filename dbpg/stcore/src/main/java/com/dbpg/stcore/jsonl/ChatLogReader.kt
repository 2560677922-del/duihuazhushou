package com.dbpg.stcore.jsonl

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * SillyTavern 聊天记录（.jsonl）读取器。
 *
 * ST 把一个会话存成「一行一条消息」的 JSON Lines：
 *
 *   {"name":"Char","is_user":false,"is_system":false,"send_date":"2024-05-01T12:34:56.789Z","mes":"...","swipes":["..."],"swipe_id":0,"extra":{}}
 *   {"user_name":"You","character_name":"Char","chat_metadata":{...}}   <- 末行常是元数据，没有 mes
 *
 * 约束：
 *   - 只用 org.json（Android 自带，无需额外依赖），不碰 Android API，纯 JVM 可单测。
 *   - 任何一行解析失败都【跳过该行】而不是整份文件失败 —— 聊天记录是用户资产，
 *     宁可能读到多少读多少，也不能因为一行脏数据打不开。
 */
object SillyTavernChatLogReader {

    /** 一条聊天记录。字段对齐 ST 的 jsonl 语义。 */
    data class ChatLogEntry(
        /** 归一化角色：user / assistant / system。 */
        val role: String,
        /** 正文（mes；缺失时退回当前 swipe）。 */
        val content: String,
        /** 说话人名字（ST 里的 `name`）。 */
        val name: String?,
        val isUser: Boolean,
        val isSystem: Boolean,
        /** 原始时间戳字符串，原样保留（排查用）。 */
        val sendDate: String?,
        /** 尽量解析成「毫秒时间戳」，解析不出为 null（不阻塞渲染）。 */
        val timestampMs: Long?,
        /** 备选回复（swipes），含当前这条。 */
        val swipes: List<String>,
        /** 当前选中的 swipe 下标。 */
        val swipeId: Int,
        /** ST 的 extra 字段（图片、模型名等都在里面），浅层转成 Map。 */
        val extra: Map<String, Any?> = emptyMap(),
    )

    /**
     * 解析整份 jsonl 文本。空行忽略；解析失败的行跳过。
     * @return 按文件顺序（= 时间顺序）排列的消息列表。
     */
    fun parse(text: String): List<ChatLogEntry> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<ChatLogEntry>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.first() != '{') continue
            parseLine(line)?.let { out.add(it) }
        }
        return out
    }

    /**
     * 解析单行。返回 null 表示「不是一条可渲染的消息」（例如元数据行）。
     * 解析抛异常也返回 null（跳过脏数据）。
     */
    fun parseLine(line: String): ChatLogEntry? {
        return try {
            val o = JSONObject(line)
            // 没有 mes 也没有 swipes ⇒ 元数据行，跳过
            val hasMes = o.has("mes") && !o.isNull("mes")
            val swipes = readStringArray(o.optJSONArray("swipes"))
            if (!hasMes && swipes.isEmpty()) return null

            val isUser = o.optBoolean("is_user", false)
            val isSystem = o.optBoolean("is_system", false)
            val swipeId = o.optInt("swipe_id", 0)

            val content = when {
                hasMes -> o.optString("mes", "")
                swipes.isNotEmpty() -> swipes.getOrElse(swipeId) { swipes.first() }
                else -> ""
            }

            val role = when {
                isSystem -> "system"
                isUser -> "user"
                else -> "assistant"
            }

            ChatLogEntry(
                role = role,
                content = content,
                name = o.optString("name", "").ifEmpty { null },
                isUser = isUser,
                isSystem = isSystem,
                sendDate = o.optString("send_date", "").ifEmpty { null },
                timestampMs = parseTimestamp(o.opt("send_date")),
                swipes = swipes,
                swipeId = swipeId,
                extra = readExtra(o.optJSONObject("extra")),
            )
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun readStringArray(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    /** extra 浅层转 Map（值可能是 String / Number / Boolean / 嵌套对象）。 */
    private fun readExtra(o: JSONObject?): Map<String, Any?> {
        if (o == null) return emptyMap()
        val map = LinkedHashMap<String, Any?>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = o.opt(k)
        }
        return map
    }

    /**
     * 把 ST 的 send_date 解析成毫秒。兼容几种常见形态：
     *   - 数字：> 1e12 当作毫秒，否则当作秒
     *   - "2024-05-01@12:34"（老格式，@ 当 T）
     *   - "2024-05-01T12:34:56.789Z"（ISO）
     *   - "2024-05-01T12:34:56+08:00"
     *   - "2024-05-01 12:34:56"
     * 解析失败统一返回 null。
     */
    fun parseTimestamp(raw: Any?): Long? {
        if (raw == null) return null
        if (raw is Number) {
            val v = raw.toLong()
            return if (v > 1_000_000_000_000L) v else v * 1000L
        }
        var s = raw.toString().trim()
        if (s.isEmpty()) return null
        // 纯数字字符串
        s.toLongOrNull()?.let { v ->
            return if (v > 1_000_000_000_000L) v else v * 1000L
        }
        // 老格式 2024-05-01@12:34 -> 2024-05-01T12:34
        s = s.replace('@', 'T')

        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX" to TimeZone.getDefault(),
            "yyyy-MM-dd'T'HH:mm:ssXXX" to TimeZone.getDefault(),
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" to TimeZone.getTimeZone("UTC"),
            "yyyy-MM-dd'T'HH:mm:ss'Z'" to TimeZone.getTimeZone("UTC"),
            "yyyy-MM-dd'T'HH:mm:ss.SSS" to TimeZone.getDefault(),
            "yyyy-MM-dd'T'HH:mm:ss" to TimeZone.getDefault(),
            "yyyy-MM-dd'T'HH:mm" to TimeZone.getDefault(),
            "yyyy-MM-dd HH:mm:ss" to TimeZone.getDefault(),
            "yyyy-MM-dd HH:mm" to TimeZone.getDefault(),
            "yyyy-MM-dd" to TimeZone.getDefault(),
        )
        for ((pattern, tz) in patterns) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                fmt.timeZone = tz
                fmt.isLenient = false
                val d = fmt.parse(s) ?: continue
                return d.time
            } catch (_: Exception) {
                // 试下一个
            }
        }
        return null
    }
}

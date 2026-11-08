package com.dbpg.stcore.session

import org.json.JSONObject

/**
 * 一段自动摘要，覆盖 actions[fromIndex..toIndex]（含两端）。
 *
 * ★ 原文永远不删：摘要只是"发给模型时替换原文"，本地完整对话一直保留，
 *   用户随时能回看、能编辑摘要、能关掉摘要恢复原始上下文。
 *
 * ★ [toActionId] 是锚点：用户删了/改了中间的某条消息后，下标会错位。
 *   读取时先校验"这个下标上还是不是这条消息"，对不上就整段作废（回退原文），
 *   宁可多花 token，也不能把张三的剧情安到李四头上。
 */
data class SummaryBlock(
    val id: String = "",
    val text: String = "",
    val fromIndex: Int = 0,
    val toIndex: Int = 0,
    /** 覆盖区间最后一轮的 actionId（校验用）。 */
    val toActionId: String = "",
    val createdAt: Long = 0L,
    /** 生成用的模型名，排错用。 */
    val model: String = "",
    /** 原始 JSON，兜底保留未知字段。 */
    val raw: String? = null,
) {
    fun toJson(): JSONObject {
        val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        o.put("id", id)
        o.put("text", text)
        o.put("fromIndex", fromIndex)
        o.put("toIndex", toIndex)
        o.put("toActionId", toActionId)
        o.put("createdAt", createdAt)
        o.put("model", model)
        return o
    }

    companion object {
        fun parse(o: JSONObject?): SummaryBlock? {
            if (o == null) return null
            val text = o.optString("text", "")
            if (text.isBlank()) return null
            return SummaryBlock(
                id = o.optString("id", ""),
                text = text,
                fromIndex = o.optInt("fromIndex", 0),
                toIndex = o.optInt("toIndex", 0),
                toActionId = o.optString("toActionId", ""),
                createdAt = o.optLong("createdAt", 0L),
                model = o.optString("model", ""),
                raw = o.toString(),
            )
        }

        fun parseArray(arr: org.json.JSONArray?): List<SummaryBlock> {
            if (arr == null || arr.length() == 0) return emptyList()
            val out = ArrayList<SummaryBlock>(arr.length())
            for (i in 0 until arr.length()) {
                parse(arr.optJSONObject(i))?.let { out.add(it) }
            }
            return out
        }
    }
}

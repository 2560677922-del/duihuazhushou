package com.dbpg.duihuazhushou.session

/** 会话摘要：原生列表只读网页的会话，不持有消息体。 */
data class SessionSummary(
    val id: String,
    val title: String,
    val messageCount: Int,
    val updatedAt: Long,
    val current: Boolean,
) {
    val displayTitle: String
        get() = title.ifBlank { "未命名会话" }
}

package com.dbpg.duihuazhushou.session

/** 会话摘要：原生列表只读网页的会话，不持有消息体。 */
data class SessionSummary(
    val id: String,
    val title: String,
    val messageCount: Int,
    val updatedAt: Long,
    val current: Boolean,
    /** 覆盖副标题（群聊列表用它显示「3 人」而不是「N 条消息」）；null = 按会话算 */
    val detail: String? = null,
) {
    val displayTitle: String
        get() = title.ifBlank { "未命名会话" }
}

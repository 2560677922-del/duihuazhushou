package com.dbpg.duihuazhushou.chat

/**
 * 聊天列表里的一条（UI 层模型）。
 *
 * 刻意与「数据来源」解耦：
 *   - 来自网页快照（NativeChatActivity 轮询 getSnapshot）时，id = "snap:<序号>"
 *   - 来自 .jsonl 文件（ChatLogLoader）时，id = "jsonl:<序号>"
 * 图片一律用【本地文件路径】，绝不携带 base64。
 */
data class ChatItem(
    val id: String,
    val role: String,                       // user / assistant / system
    val content: String,
    val imagePaths: List<String> = emptyList(),
    val streaming: Boolean = false,
)

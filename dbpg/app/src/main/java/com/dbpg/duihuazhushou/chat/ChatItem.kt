package com.dbpg.duihuazhushou.chat

/**
 * 聊天列表里的一条（UI 层模型）。
 *
 * 刻意与「数据来源」解耦：
 *   - 会话消息：id = "a:<actionId>:<role>"
 *   - .jsonl 文件：id = "jsonl:<序号>"
 * 图片一律用【本地文件路径】，绝不携带 base64。
 *
 * 操作类字段（actionId / 版本）为空或 0 时，界面上对应按钮不出现 ——
 * 这样导入的聊天记录、系统提示这些"不可编辑"的条目也不用特殊分支。
 */
data class ChatItem(
    val id: String,
    val role: String,                       // user / assistant / system
    val content: String,
    val imagePaths: List<String> = emptyList(),
    val streaming: Boolean = false,
    /** 对应会话里的哪一轮；空串 = 不是会话消息（只读预览） */
    val actionId: String = "",
    /** 当前显示第几版（0 = 最新） */
    val variantIndex: Int = 0,
    /** 一共有几版；<= 1 时不出「‹ ›」 */
    val variantCount: Int = 0,
)

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
    /**
     * 要交给 WebView 真渲染的富 HTML（跑完正则、清洗过脚本）。
     *
     * 非空 = 这条消息含结构性标记（`<details>` / `<table>` / `<div>` / 内联
     * `<style>` …），原生 TextView 表达不了，必须走浏览器那套排版。
     */
    val html: String? = null,
    /**
     * 含 `<details>` 的富 HTML，给**降级路径**用：用户关掉富渲染、或 WebView
     * 起不来时，用原生折叠块至少还能展开/收起（只认 `<details>` 这一种）。
     *
     * 与 [html] 互斥：正常路径下 html 非空、foldHtml 为空。
     */
    val foldHtml: String? = null,
    /** 卡片位图文件路径（渲染完成后才有）。空 = 还在渲染 / 降级为纯文本。 */
    val cardPath: String? = null,
    /** 卡片渲染后的高度（px），用来在位图到位前占位，避免滚动跳动。 */
    val cardHeightPx: Int = 0,
)

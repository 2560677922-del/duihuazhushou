package com.dbpg.stcore.session

import com.dbpg.stcore.stripImageMarkers

/**
 * 把原生会话「导出成要送给生成引擎的消息列表」。
 *
 * 这是整个原生优先架构里最关键的一层边界，规则只有三条，但每条都不能破：
 *
 * 1. **只导出当前版本**。
 *    `SessionAction.user / ai` 存的永远是用户当前看到的那一版，
 *    `variants` 是本地历史（用来翻 ‹ ›），**一个字节都不外发**。
 *    所以"网页切过版本、原生还是旧版"这种不同步问题从根上不存在 ——
 *    生成时看的就是用户眼前这篇文字。
 *
 * 2. **图片不进上下文**。
 *    `[[IMG: ...]]` 是给渲染用的占位符，导出时用 [stripImageMarkers] 剥掉；
 *    图片本体存在 `ActionImage`（assetId / localPath），从来不拼进正文。
 *    这也意味着"重新生成"时模型看不到自己上次插了什么图，不会自我强化。
 *
 * 3. **空侧不占位**。
 *    用户只删了 AI 回复的那一轮，导出时只有一条 user；反之亦然。
 *    别发一条 `content: ""` 的假消息，那会污染模型的轮次判断。
 *
 * 不在这里做的事：宏替换、世界书触发、正则、token 裁剪。
 * 那些全都属于 ST 兼容层语义，留在 web 的 assembler 里，
 * 原生只负责"交出一份干净的、当前版本的对话"。
 */

/** 送给生成引擎的一条消息。字段名与 web 的 ChatMessage 对齐（role / content）。 */
data class PromptMessage(val role: String, val content: String)

object SessionHistory {

    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"

    /**
     * 导出整段历史（不含正在输入的那一句）。
     *
     * @param uptoActionId 只导出到这一轮**之前**（不含）。传 null = 全部。
     *                     重新生成第 i 轮时传第 i 轮的 id：它自己的 user 会作为
     *                     `currentUserInput` 单独发，不能重复出现在 history 里。
     */
    fun export(doc: SessionDoc, uptoActionId: String? = null): List<PromptMessage> {
        val stop = if (uptoActionId == null) -1 else doc.actions.indexOfFirst { it.id == uptoActionId }
        val end = if (stop < 0) doc.actions.size else stop

        val out = ArrayList<PromptMessage>(end * 2 + 1)
        // 场景描述是"故事的开场白"，始终以 assistant 身份排在最前
        // （与 web 的 history computed 一致；不加的话模型会丢掉开场地设定）
        val scene = clean(doc.sceneDescription)
        if (scene.isNotEmpty()) out.add(PromptMessage(ROLE_ASSISTANT, scene))

        for (i in 0 until end) {
            val a = doc.actions[i]
            val user = clean(a.user)
            if (user.isNotEmpty()) out.add(PromptMessage(ROLE_USER, user))
            val ai = clean(a.ai)
            if (ai.isNotEmpty()) out.add(PromptMessage(ROLE_ASSISTANT, ai))
        }
        return out
    }

    /** 某一轮的 user 文本，剥图后作为"当前输入"单独发送。 */
    fun userInputOf(doc: SessionDoc, actionId: String): String =
        clean(doc.actions.find { it.id == actionId }?.user.orEmpty())

    /** 最后一轮的 user 文本（继续对话时可能是"重发上一条"）。 */
    fun lastUserInput(doc: SessionDoc): String =
        doc.actions.lastOrNull()?.let { clean(it.user) }.orEmpty()

    /**
     * 当前这一轮 AI 的**完整**正文（含标记），用来做显示和自动配图。
     * 与 [export] 不同：这里保留 `[[IMG: ...]]`，因为界面要按标记落图。
     */
    fun aiTextOf(doc: SessionDoc, actionId: String): String =
        doc.actions.find { it.id == actionId }?.ai.orEmpty()

    /**
     * 粗估这段历史有多少字符。
     *
     * 不引 tokenizer：只用来判断"要不要提示用户上下文可能超了"，
     * 精确计数是 web assembler 的事（它有真 tokenizer）。
     */
    fun approximateLength(messages: List<PromptMessage>): Int =
        messages.sumOf { it.content.length + 4 }

    // ------------------------------------------------------------ 内部

    private fun clean(text: String): String {
        if (text.isEmpty()) return ""
        return stripImageMarkers(text).trim()
    }
}

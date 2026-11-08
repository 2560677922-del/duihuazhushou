package com.dbpg.duihuazhushou.summary

import android.content.Context

/**
 * 自动摘要配置（原生 SharedPreferences）。
 *
 * 为什么不放网页：摘要完全在原生侧生成、原生侧注入上下文，web 根本不知道
 * 有这回事。配置跟着实现走，放一张表里最不容易"两套配置各说各话"。
 */
data class SummaryConfig(
    val enabled: Boolean = false,
    /**
     * 触发方式：按轮数 / 按字数。
     *
     * 用户原话是"每十条或每多少字"，两者是**二选一**的触发条件，
     * 不是"且"。按字数更适合长回复（10 条可能就 2 万字）。
     */
    val mode: String = MODE_COUNT,
    /** 每多少轮触发（mode=count）。 */
    val everyCount: Int = 10,
    /** 每多少字触发（mode=chars）。 */
    val everyChars: Int = 4000,
    /** 单次摘要**取多少轮**（取最早的 N 轮，不是输出 N 条摘要）。 */
    val takeCount: Int = 8,
    /** 摘要正文的目标字数上限。 */
    val maxChars: Int = 400,
    /**
     * 摘要请求是否携带**当前会话的预设**。
     *
     * ★ 这里说的是预设（写作规则 / 格式要求 / 前置语），不是角色卡。
     *   角色卡与世界书**永远不带** —— 摘要是压缩任务，不是扮演。
     *   预设原样全带、不截断：砍一半的预设等于失效（用户明确要求）。
     */
    val withPreset: Boolean = true,
    /**
     * 用户自己补的摘要提示词（可空）。
     *
     * 追加在系统指令之后，用于纠正模型的摘要偏好，例如
     * "用第三人称、保留所有数字与承诺、按时间分条"。
     * 空串 = 只用内置指令。
     */
    val customPrompt: String = "",
    /** 摘要注入时的角色：system（默认）/ assistant。 */
    val role: String = "system",
) {
    companion object {
        const val MODE_COUNT = "count"
        const val MODE_CHARS = "chars"

        /** 上限保护：预设置信度不受影响，只防手滑填了 100 万。 */
        const val MAX_CUSTOM_PROMPT = 4000

        private const val PREF = "dhp_summary"

        fun load(ctx: Context): SummaryConfig {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            return SummaryConfig(
                enabled = p.getBoolean("enabled", false),
                mode = if (p.getString("mode", MODE_COUNT) == MODE_CHARS) MODE_CHARS else MODE_COUNT,
                everyCount = p.getInt("every_count", 10),
                everyChars = p.getInt("every_chars", 4000),
                takeCount = p.getInt("take_count", 8),
                maxChars = p.getInt("max_chars", 400),
                withPreset = p.getBoolean("with_preset", true),
                customPrompt = p.getString("custom_prompt", "").orEmpty(),
                role = if (p.getString("role", "system") == "assistant") "assistant" else "system",
            ).sanitized()
        }

        fun save(ctx: Context, cfg: SummaryConfig) {
            val c = cfg.sanitized()
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", c.enabled)
                .putString("mode", c.mode)
                .putInt("every_count", c.everyCount)
                .putInt("every_chars", c.everyChars)
                .putInt("take_count", c.takeCount)
                .putInt("max_chars", c.maxChars)
                .putBoolean("with_preset", c.withPreset)
                .putString("custom_prompt", c.customPrompt)
                .putString("role", c.role)
                .apply()
        }
    }

    /**
     * 参数自洽：单次取数不能超过触发阈值（否则永远攒不满就触发）。
     *
     * takeCount < everyCount 的差额天然保护了"最近几条"：每 10 条触发、
     * 取 8 条，剩下的 2 条永远是原文 —— 不需要再单设 keepRecent，
     * 多一个参数只会让用户困惑（"我设 10 条怎么 14 条才总结"）。
     */
    fun sanitized(): SummaryConfig = copy(
        everyCount = everyCount.coerceIn(3, 100),
        everyChars = everyChars.coerceIn(500, 100000),
        takeCount = takeCount.coerceIn(1, 50).coerceAtMost(maxOf(1, everyCount - 1)),
        maxChars = maxChars.coerceIn(80, 2000),
        customPrompt = customPrompt.take(MAX_CUSTOM_PROMPT),
    )
}

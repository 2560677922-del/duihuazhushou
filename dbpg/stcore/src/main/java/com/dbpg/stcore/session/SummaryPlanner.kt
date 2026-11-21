package com.dbpg.stcore.session

/**
 * 摘要窗口计算（纯函数，独立成文件是为了能跑 JVM 单测）。
 *
 * 用户要的语义："每 10 条（或每 N 字）触发一次，总结**前** 8 条"
 * —— 注意是"取最早的 8 条去摘要"，不是"输出 8 条摘要"。
 *
 * 窗口是滚动的：
 *
 *   actions:  [0][1]...[7] [8][9] [10][11][12]...
 *              └─ 已摘要 ──┘ └保留─┘ └─ 最新 ─┘
 *                              ↑ keepRecent
 *
 * 触发条件满足后，从"最早未摘要的那条"开始取 [takeCount] 条；
 * 结尾不能再往前进 —— 必须给最近 [keepRecent] 条留原文（刚发生的事
 * 压成一句话，模型会当场失忆）。
 */
object SummaryPlanner {

    enum class TriggerMode { COUNT, CHARS }

    /**
     * @param total 当前总轮数
     * @param summarizedUpTo 已经摘要到第几条（不含）；0 = 还没摘要过
     * @param charsOf 取第 i 轮的字数（用于 CHARS 模式）
     * @return 本次要摘要的区间；null = 还不够触发
     */
    fun next(
        total: Int,
        summarizedUpTo: Int,
        mode: TriggerMode,
        everyCount: Int,
        everyChars: Int,
        takeCount: Int,
        keepRecent: Int,
        charsOf: (Int) -> Int,
    ): IntRange? {
        if (total <= 0) return null
        val start = summarizedUpTo.coerceIn(0, total)

        // 最近 keepRecent 条永远保留原文：摘要只能动它之前的内容
        val limit = (total - keepRecent.coerceAtLeast(0)).coerceIn(start, total)
        val pending = limit - start
        if (pending <= 0) return null

        // 一次最多取 takeCount 条；但也不能超过"可摘要区间"
        val take = takeCount.coerceAtLeast(1).coerceAtMost(pending)

        val enough = when (mode) {
            TriggerMode.COUNT -> pending >= everyCount.coerceAtLeast(1)
            TriggerMode.CHARS -> {
                var chars = 0
                for (i in start until limit) chars += charsOf(i).coerceAtLeast(0)
                chars >= everyChars.coerceAtLeast(1)
            }
        }
        if (!enough) return null

        return start until (start + take)
    }

    /**
     * 摘要是否还能用在当前对话上。
     *
     * 校验两件事：下标在范围内、锚点消息没变。任何一条不满足就丢弃，
     * 让调用方回退到原文 —— 摘要错位的代价（剧情张冠李戴）远大于多花 token。
     */
    fun valid(block: SummaryBlock, actions: List<SessionAction>): Boolean {
        if (block.text.isBlank()) return false
        if (block.fromIndex < 0 || block.toIndex < block.fromIndex) return false
        if (block.toIndex >= actions.size) return false
        val anchor = actions[block.toIndex]
        return block.toActionId.isEmpty() || anchor.id == block.toActionId
    }

    /** 有效的摘要按时间顺序排列，并算出"摘要覆盖到第几条（不含）"。 */
    fun effective(
        summaries: List<SummaryBlock>,
        actions: List<SessionAction>,
    ): Pair<List<SummaryBlock>, Int> {
        val ok = summaries.filter { valid(it, actions) }.sortedBy { it.fromIndex }
        if (ok.isEmpty()) return emptyList<SummaryBlock>() to 0
        /*
         * 第一块允许从任意位置开始：用户在聊天界面里可以把"替换第几条"
         * 改成后面的区间（前面的原文照常发送，见 SessionHistory）。
         * 后续块必须紧接前一块 —— 中间断档就停在断档前，断档后的摘要不可信。
         */
        val kept = ArrayList<SummaryBlock>()
        var expect = ok.first().fromIndex
        for (b in ok) {
            if (b.fromIndex > expect) break
            kept.add(b)
            expect = b.toIndex + 1
        }
        if (kept.isEmpty()) return emptyList<SummaryBlock>() to 0
        return kept to kept.last().toIndex + 1
    }
}

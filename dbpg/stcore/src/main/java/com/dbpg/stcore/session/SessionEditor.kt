package com.dbpg.stcore.session

/**
 * 会话消息编辑（纯函数，无 IO、无 Android 依赖，可 JVM 单测）。
 *
 * 这里只做一件事：给定 [SessionDoc] 和一个操作，返回**新的** [SessionDoc]。
 * 不落盘、不弹窗、不碰 UI —— 调用方（原生 `ChatController`）负责存盘与刷新。
 *
 * 语义刻意与网页 `stores/adventure.ts` 逐条对齐：
 *   - 版本数组 `variants[0]` 永远是最新版，`variantIndex` 指向当前显示的那一版；
 *   - 空版本（既无 user 又无 ai）不入库、切换前先剔除；
 *   - 最多保留 [MAX_VARIANTS] 版，防止无限膨胀；
 *   - 删除是分侧的：删 AI 不动用户那句话，反之亦然。
 *
 * 为什么放在 stcore 而不是 app：
 *   这些规则是"数据语义"，不是界面逻辑。放在引擎层才能用纯 JVM 测试把
 *   边界情况（空版本、重复版本、越界下标）全部钉住，不依赖模拟器。
 */
object SessionEditor {

    /** 同一条消息最多保留几个历史版本（与 web 的 MAX_VARIANTS 一致）。 */
    const val MAX_VARIANTS = 20

    // ------------------------------------------------------------ 会话属性

    /**
     * 改会话标题。
     *
     * 只是 copy 一次，但仍放在这里：以后加「置顶 / 归档 / 分组」也是同一类
     * 纯数据变更，集中在一处才好为它们写 JVM 测试。空标题回落到「未命名冒险」，
     * 避免列表里出现一条点不到的空白行。
     */
    fun rename(doc: SessionDoc, title: String, now: Long = System.currentTimeMillis()): SessionDoc =
        doc.copy(title = title.trim().ifBlank { "未命名冒险" }, updatedAt = now)

    // ------------------------------------------------------------ 编辑 / 版本

    /**
     * 修改某一轮的 user / ai，并把旧内容压进历史版本。
     *
     * 与 web `updateAction` 一致：这是"编辑"，不是"重新生成"，
     * 所以不特殊处理"当前内容有没有 AI 正文"。
     */
    fun updateAction(
        doc: SessionDoc,
        actionId: String,
        user: String? = null,
        ai: String? = null,
        now: Long = System.currentTimeMillis(),
    ): SessionDoc {
        val act = doc.actions.find { it.id == actionId } ?: return doc
        val base = seedCurrent(act)
        val next = withVariant(
            base,
            MessageVariant(
                user = user ?: base.user,
                ai = ai ?: base.ai,
                timestamp = now,
            ),
        )
        return replace(doc, next, now)
    }

    /**
     * 用新生成的 AI 正文覆盖某一条，但保留旧版（重新生成走这里）。
     *
     * ★ 第一次重新生成时 `variants` 还是空的，必须**先把当前正文存成版本**，
     *   否则原来那条回复就永久丢了，「‹ ›」也翻不回去。
     *   当前正文为空（比如用户先删了回复）时不存 —— 存了就是一个切过去全空的幽灵版本。
     */
    fun replaceAi(
        doc: SessionDoc,
        actionId: String,
        ai: String,
        user: String? = null,
        now: Long = System.currentTimeMillis(),
    ): SessionDoc {
        val act = doc.actions.find { it.id == actionId } ?: return doc
        val base = seedCurrent(act)
        val next = withVariant(
            base,
            MessageVariant(user = user ?: base.user, ai = ai, timestamp = now),
        )
        return replace(doc, next, now)
    }

    /**
     * 切换版本。index 0 = 最新。
     *
     * 切换前先剔除空版本并夹紧下标：历史数据里可能残留"只有用户发言、
     * 没有 AI 正文"的旧版本，切进去整条消息会变空，而且按钮也会跟着消失。
     */
    fun switchVariant(doc: SessionDoc, actionId: String, index: Int, now: Long = System.currentTimeMillis()): SessionDoc {
        val act = doc.actions.find { it.id == actionId } ?: return doc
        if (act.variants.isEmpty()) return doc

        // 同上：用户发言非空的版本也要保留（与 web 一致），否则切换后计数器会消失
        val cleaned = act.variants.filter { it.ai.trim().isNotEmpty() || it.user.trim().isNotEmpty() }
        if (cleaned.isEmpty()) return doc

        val at = index.coerceIn(0, cleaned.size - 1)
        val v = cleaned[at]
        val next = act.copy(
            user = v.user,
            ai = v.ai,
            variants = cleaned,
            variantIndex = at,
        )
        return replace(doc, next, now)
    }

    // ------------------------------------------------------------ 删除（分侧）

    /**
     * 只删这一条的 AI 回复，保留用户发言。
     * 配图是为这段回复生成的，正文没了标记也就没了 —— 一并清掉。
     *
     * @return 新文档；被删的图片 id 通过 [removedImageIds] 单独取。
     */
    fun removeAi(doc: SessionDoc, actionId: String, now: Long = System.currentTimeMillis()): SessionDoc {
        val act = doc.actions.find { it.id == actionId } ?: return doc
        val next = act.copy(ai = "", images = emptyList(), variants = emptyList(), variantIndex = 0)
        // 用户侧也空 → 这条没意义了，整条删掉
        return if (next.user.trim().isEmpty()) remove(doc, actionId, now)
        else replace(doc, next, now)
    }

    /** 只删用户这一句，保留 AI 回复。 */
    fun removeUser(doc: SessionDoc, actionId: String, now: Long = System.currentTimeMillis()): SessionDoc {
        val act = doc.actions.find { it.id == actionId } ?: return doc
        val next = act.copy(user = "")
        return if (next.ai.trim().isEmpty() && next.images.isEmpty()) remove(doc, actionId, now)
        else replace(doc, next, now)
    }

    /** 整条删除（两侧一起）。 */
    fun remove(doc: SessionDoc, actionId: String, now: Long = System.currentTimeMillis()): SessionDoc {
        if (doc.actions.none { it.id == actionId }) return doc
        return doc.copy(
            actions = doc.actions.filterNot { it.id == actionId },
            updatedAt = now,
        )
    }

    /**
     * 砍掉某一条**之后**的所有消息（重新生成前的回溯）。
     *
     * 那一步之后的内容都是建立在旧回答上的，重新生成后已不成立。
     * 当前这一条保留 —— 它是被替换的对象，旧正文会进 variants。
     */
    fun truncateAfter(doc: SessionDoc, actionId: String, now: Long = System.currentTimeMillis()): SessionDoc {
        val idx = doc.actions.indexOfFirst { it.id == actionId }
        if (idx < 0 || idx == doc.actions.lastIndex) return doc
        return doc.copy(actions = doc.actions.take(idx + 1), updatedAt = now)
    }

    /** 删除前先取这一条携带的图片 id，供调用方清理本地文件/资产。 */
    fun imageIdsOf(doc: SessionDoc, actionId: String): List<String> =
        doc.actions.find { it.id == actionId }?.images?.mapNotNull { it.id.takeIf(String::isNotEmpty) }
            ?: emptyList()

    // ------------------------------------------------------------ 新增 / 草稿

    /** 追加一轮对话。 */
    fun appendAction(
        doc: SessionDoc,
        actionId: String,
        user: String,
        ai: String,
        now: Long = System.currentTimeMillis(),
        images: List<ActionImage> = emptyList(),
    ): SessionDoc = doc.copy(
        actions = doc.actions + SessionAction(
            id = actionId,
            user = user,
            ai = ai,
            timestamp = now,
            images = images,
        ),
        updatedAt = now,
        draft = null,
    )

    /**
     * 把某一条的配图替换成新的（按 id 覆盖，没有就按 markdownIndex 落位）。
     *
     * 自动配图是一张一张回来的，每张都要立刻可见，所以这里做成幂等 upsert。
     */
    fun upsertImage(doc: SessionDoc, actionId: String, image: ActionImage, now: Long = System.currentTimeMillis()): SessionDoc {
        val act = doc.actions.find { it.id == actionId } ?: return doc
        val list = act.images.toMutableList()
        val at = list.indexOfFirst { it.id == image.id }
        if (at >= 0) list[at] = image else list.add(image)
        return replace(doc, act.copy(images = list), now)
    }

    /** 写入/更新流式草稿（原生侧由节流器控制频率）。 */
    fun setDraft(doc: SessionDoc, user: String, ai: String, now: Long = System.currentTimeMillis()): SessionDoc =
        doc.copy(draft = SessionDraft(user, ai, now), updatedAt = now)

    fun clearDraft(doc: SessionDoc, now: Long = System.currentTimeMillis()): SessionDoc =
        doc.copy(draft = null, updatedAt = now)

    // ------------------------------------------------------------ 分支 / 副本

    /** 标题尾部的编号：「（2）」「(2)」都认，用来避免分支出「A（1）（1）」。 */
    private val BRANCH_NO = Regex("""^(.*?)\s*[（(](\d+)[)）]$""")

    /** 剥掉标题尾部的分支编号；没有就原样返回（trim 后）。 */
    private fun stripBranchNo(title: String): String {
        val t = title.trim()
        val hit = BRANCH_NO.find(t) ?: return t
        return hit.groupValues[1].trim().ifBlank { t }
    }

    /**
     * 算出下一个分支编号后缀。
     *
     * 规则：先把标题尾部的编号剥掉得到 base，再在 [existingTitles] 里找
     * `base（n）` 的最大 n，+1。所以从「雨夜（1）」再分支得到「雨夜（2）」，
     * 而不是「雨夜（1）（1）」；删掉中间的编号也不会撞号（取 max+1）。
     *
     * 为什么不直接数会话个数：分支可以删、可以并行分两条，
     * 数个数会撞号；扫标题才是幂等的。
     *
     * @param existingTitles 磁盘上已有会话的标题（含源会话本身，会被规则自动排除）。
     */
    fun branchSuffix(baseTitle: String, existingTitles: Collection<String>): String {
        val base = stripBranchNo(baseTitle).ifBlank { "未命名冒险" }
        var max = 0
        val same = Regex("""^""" + Regex.escape(base) + """\s*[（(](\d+)[)）]$""")
        for (t in existingTitles) {
            val n = same.find(t.trim())?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (n > max) max = n
        }
        return "（${max + 1}）"
    }

    /**
     * 从某一步分支：复制到这一步（含）为止，生成一个新会话。
     *
     * 原会话不动 —— 用户通常是想"从这个节点往下再走一条不同的路"。
     * @param newActionId 给第 i 条生成新 id；默认沿用原 id 会与源会话冲突。
     * @param titleSuffix 空串表示标题原样（调用方已经算好完整标题时用）。
     */
    fun branch(
        doc: SessionDoc,
        actionId: String,
        newSessionId: String,
        newActionId: (Int) -> String,
        titleSuffix: String = "（1）",
        now: Long = System.currentTimeMillis(),
    ): SessionDoc? {
        val idx = doc.actions.indexOfFirst { it.id == actionId }
        if (idx < 0) return null
        // raw 必须原样带走：它是"未知字段"的兜底（speakerId、自定义元数据等），
        // 置空等于分支一次就把 ST 扩展字段洗掉。id 在 toJson 里会被覆盖，不会串号。
        val copied = doc.actions.take(idx + 1).mapIndexed { i, a ->
            a.copy(id = newActionId(i))
        }
        return SessionDoc(
            id = newSessionId,
            title = stripBranchNo(doc.title).ifBlank { "未命名冒险" } + titleSuffix,
            sceneDescription = doc.sceneDescription,
            actions = copied,
            createdAt = now,
            updatedAt = now,
        )
    }

    // ------------------------------------------------------------ 内部

    /**
     * 把 [next] 记成"最新版本"，旧版本往后排。
     *
     * 与 web 的 pushVariant 一致：
     *   1. 只保留**有内容**的版本（AI 正文或用户发言任一非空）。
     *      注意：只按 AI 非空过滤是错的 —— web 迁移来的历史里存在
     *      「有用户发言、AI 正文为空」的版本，过滤掉会让 variantCount 骤减，
     *      「‹ › 2/3」直接消失（用户实测复现）。这里与 web 的 pushVariant 对齐。
     *   2. 去掉与新版完全重复的旧版（模型连出两次相同内容不堆垃圾）；
     *   3. 新版排最前，整体截断到 [MAX_VARIANTS]。
     */
    private fun withVariant(act: SessionAction, next: MessageVariant): SessionAction {
        val onlyReal = act.variants.filter { it.ai.trim().isNotEmpty() || it.user.trim().isNotEmpty() }
        val rest = onlyReal.filterNot { it.user == next.user && it.ai == next.ai }
        return act.copy(
            user = next.user,
            ai = next.ai,
            variants = (listOf(next) + rest).take(MAX_VARIANTS),
            variantIndex = 0,
        )
    }

    /**
     * 第一次改这条消息时，先把"用户现在看到的这一版"存进历史。
     *
     * 不做的话第一次编辑/重新生成会把原回复直接覆盖掉，「‹ ›」永远翻不回初版。
     * 当前正文为空时不存 —— 那会造出一个切过去全空的幽灵版本。
     */
    private fun seedCurrent(act: SessionAction): SessionAction {
        if (act.variants.isNotEmpty()) return act
        if (act.ai.trim().isEmpty()) return act
        return act.copy(
            variants = listOf(
                MessageVariant(user = act.user, ai = act.ai, timestamp = act.timestamp),
            ),
            variantIndex = 0,
        )
    }

    private fun replace(doc: SessionDoc, act: SessionAction, now: Long): SessionDoc =
        doc.copy(
            actions = doc.actions.map { if (it.id == act.id) act else it },
            updatedAt = now,
        )
}

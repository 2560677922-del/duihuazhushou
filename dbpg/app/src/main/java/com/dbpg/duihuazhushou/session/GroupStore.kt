package com.dbpg.duihuazhushou.session

import android.content.Context
import com.dbpg.stcore.session.GroupRef
import com.dbpg.stcore.session.SessionDoc
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 群聊容器。
 *
 * ## 为什么是「一个群 + N+1 个普通会话」
 * 群聊不是"一条时间线上多个 AI 轮流说话"，而是：
 *   - 公共区 1 个会话（所有成员可见，用 `speakerId` 区分谁说的）；
 *   - 每个成员 1 个私聊会话（完全复用单人聊天的分支/变体/摘要）。
 *
 * 这样私聊不需要任何新消息模型，切频道 = 换一个 sessionId 打开，
 * 每个频道的上下文天然隔离。公共区才需要多发言人标记。
 *
 * ## 这里存什么、不存什么
 *   - 存：群标题、公共区会话 id、成员（角色卡 id / 名字 / 频道会话 id / 顺序）、
 *         公共世界书、群预设；
 *   - 不存：成员私有世界书（在 [SessionBindingStore] 的频道绑定里）、消息（在会话文件里）。
 *
 * 群级改动（换公共书、换群预设）立即对全部频道生效，不需要回写每个会话。
 */
object GroupStore {

    private const val FILE_NAME = "groups.json"

    /**
     * 旁白的默认设定。
     *
     * 为什么旁白不做成角色卡：角色卡会进频道栏、参与「续写」轮转、
     * 还会被当成"某个人"注入人物描述。旁白要的是**视角**，不是**人设** ——
     * 所以它是一段只在本群生效的系统设定，不占成员位。
     */
    const val DEFAULT_NARRATOR =
        "你是这个群聊场景的旁白。你只负责环境与事件，不扮演任何具体角色。\n" +
            "· 描写场景、氛围、时间流逝、动作的结果，以及旁观者的反应；\n" +
            "· 第三人称、现在时、具体克制，一次 1～3 段，不加标题、不解释写法；\n" +
            "· 不替角色说台词，不替用户做决定，不复述刚刚发生的对话；\n" +
            "· 只在有新信息时推进，没有变化就用一两句带过气氛。"

    data class Member(
        /** 角色卡 uid */
        val characterId: String,
        /** 选人时的名字快照：角色卡改名后群里的显示名不跟着乱 */
        val name: String,
        /** 该成员的私聊频道会话 id */
        val sessionId: String,
    )

    data class Group(
        val id: String,
        val title: String,
        val publicSessionId: String,
        /** 公共世界书：全员强制生效，成员不能去掉 */
        val publicWorldBookIds: List<String> = emptyList(),
        /** 群预设；null = 跟随全局 */
        val presetId: String? = null,
        /** 群正则组；null = 跟随全局。成员绑定里没有显式指定时用这套 */
        val regexGroupIds: List<String>? = null,
        /**
         * 群默认 API；null = 跟随全局。
         *
         * 成员没单独选 API 时用它 —— 用户的原话是「公共那边选完，成员就默认公共的，
         * 只是可以单独在成员界面改」。
         */
        val apiConfigId: String? = null,
        /**
         * 旁白设定（本群一份）。
         *
         * 空 = 用 [DEFAULT_NARRATOR]。用户可以在群设置里改成本群专属的
         * 世界观口吻；改完下一句旁白就生效，不影响已经生成的内容。
         */
        val narratorPrompt: String = DEFAULT_NARRATOR,
        /** 成员顺序 = 公共区发言顺序（可拖动调整） */
        val members: List<Member> = emptyList(),
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L,
    )

    private fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE_NAME)

    // ------------------------------------------------------------ 读

    fun list(ctx: Context): List<Group> = read(ctx).sortedByDescending { it.updatedAt }

    fun get(ctx: Context, groupId: String): Group? =
        if (groupId.isBlank()) null else read(ctx).firstOrNull { it.id == groupId }

    // ------------------------------------------------------------ 写

    fun save(ctx: Context, group: Group) {
        val all = read(ctx).toMutableList()
        val at = all.indexOfFirst { it.id == group.id }
        val stamped = group.copy(updatedAt = System.currentTimeMillis())
        if (at >= 0) all[at] = stamped else all.add(stamped)
        write(ctx, all)
    }

    /**
     * 新建群：一次把 N+1 个会话建好，并把绑定写进去。
     *
     * ★ 必须在后台线程调用（要写 N+1 个会话文件）。
     *
     * @param members 角色卡 id → 显示名，顺序即发言顺序
     */
    fun create(
        ctx: Context,
        title: String,
        members: List<Pair<String, String>>,
        publicWorldBookIds: List<String> = emptyList(),
        presetId: String? = null,
    ): Group {
        val now = System.currentTimeMillis()
        val groupId = "g_" + now.toString(36) + java.util.UUID.randomUUID().toString().take(4)
        val cleanTitle = title.trim().ifBlank { "新群聊" }

        val publicSession = SessionDoc(
            id = SessionRepo.newId("s"),
            title = "$cleanTitle · 公共",
            createdAt = now,
            updatedAt = now,
            group = GroupRef(groupId, GroupRef.KIND_PUBLIC),
        )
        SessionRepo.saveSync(publicSession)
        SessionBindingStore.save(
            ctx,
            publicSession.id,
            SessionBindingStore.Binding(
                // 公共区明确列出公共书：即使资产页后来新启用别的书，这里也不会被悄悄带进来
                worldBookIds = publicWorldBookIds,
                presetId = presetId,
            ),
        )

        val saved = ArrayList<Member>(members.size)
        for ((characterId, name) in members) {
            val s = SessionDoc(
                id = SessionRepo.newId("s"),
                title = name.ifBlank { "成员" },
                createdAt = now,
                updatedAt = now,
                group = GroupRef(groupId, GroupRef.KIND_MEMBER, characterId),
            )
            SessionRepo.saveSync(s)
            SessionBindingStore.save(
                ctx,
                s.id,
                SessionBindingStore.Binding(
                    characterId = characterId,
                    // 成员的私有书先为空；公共书在生成时由群配置合并，不写死在这里
                    worldBookIds = emptyList(),
                    presetId = null,
                ),
            )
            saved.add(Member(characterId, name, s.id))
        }

        val group = Group(
            id = groupId,
            title = cleanTitle,
            publicSessionId = publicSession.id,
            publicWorldBookIds = publicWorldBookIds,
            presetId = presetId,
            members = saved,
            createdAt = now,
            updatedAt = now,
        )
        val all = read(ctx).toMutableList().apply { add(group) }
        write(ctx, all)
        return group
    }

    fun update(ctx: Context, group: Group): Group {
        val stamped = group.copy(updatedAt = System.currentTimeMillis())
        val all = read(ctx).toMutableList()
        val at = all.indexOfFirst { it.id == group.id }
        if (at >= 0) all[at] = stamped else all.add(stamped)
        write(ctx, all)
        return stamped
    }

    /**
     * 往已有群里加成员：一人建一个私聊频道会话。
     *
     * 必须在后台线程调用。已有的同角色成员会被跳过（不重复建频道）。
     */
    fun addMembers(
        ctx: Context,
        group: Group,
        members: List<Pair<String, String>>,
    ): Group {
        val now = System.currentTimeMillis()
        val existing = group.members.map { it.characterId }.toSet()
        val added = ArrayList<Member>()
        for ((characterId, name) in members) {
            if (characterId.isBlank() || characterId in existing) continue
            val s = SessionDoc(
                id = SessionRepo.newId("s"),
                title = name.ifBlank { "成员" },
                createdAt = now,
                updatedAt = now,
                group = GroupRef(group.id, GroupRef.KIND_MEMBER, characterId),
            )
            SessionRepo.saveSync(s)
            SessionBindingStore.save(
                ctx,
                s.id,
                SessionBindingStore.Binding(characterId = characterId, worldBookIds = emptyList()),
            )
            added.add(Member(characterId, name, s.id))
        }
        if (added.isEmpty()) return group
        return update(ctx, group.copy(members = group.members + added))
    }

    /** 删群：连同它的全部频道会话一起删，不留孤儿文件。 */
    fun delete(ctx: Context, groupId: String) {
        val g = get(ctx, groupId) ?: return
        SessionRepo.delete(g.publicSessionId)
        for (m in g.members) {
            SessionRepo.delete(m.sessionId)
            SessionBindingStore.clear(ctx, m.sessionId)
        }
        SessionBindingStore.clear(ctx, g.publicSessionId)
        write(ctx, read(ctx).filterNot { it.id == groupId })
    }

    /** 群聊里某个成员（按角色卡 uid）的频道。 */
    fun memberOf(group: Group, characterId: String): Member? =
        group.members.firstOrNull { it.characterId == characterId }

    // ------------------------------------------------------------ 序列化

    private fun read(ctx: Context): List<Group> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val arr = runCatching { JSONArray(f.readText()) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Group>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(parse(it)) }
        }
        return out
    }

    private fun write(ctx: Context, groups: List<Group>) {
        val arr = JSONArray()
        groups.forEach { arr.put(toJson(it)) }
        runCatching { file(ctx).writeText(arr.toString()) }
    }

    private fun toJson(g: Group): JSONObject = JSONObject()
        .put("id", g.id)
        .put("title", g.title)
        .put("publicSessionId", g.publicSessionId)
        .put("publicWorldBookIds", JSONArray(g.publicWorldBookIds))
        .put("members", JSONArray().also { a ->
            g.members.forEach { m ->
                a.put(
                    JSONObject()
                        .put("characterId", m.characterId)
                        .put("name", m.name)
                        .put("sessionId", m.sessionId),
                )
            }
        })
        .apply {
            g.presetId?.let { put("presetId", it) }
            g.regexGroupIds?.let { put("regexGroupIds", JSONArray(it)) }
            g.apiConfigId?.let { put("apiConfigId", it) }
            if (g.narratorPrompt != DEFAULT_NARRATOR) put("narratorPrompt", g.narratorPrompt)
        }
        .put("createdAt", g.createdAt)
        .put("updatedAt", g.updatedAt)

    private fun parse(o: JSONObject): Group {
        val arr = o.optJSONArray("members") ?: JSONArray()
        val members = ArrayList<Member>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val sid = m.optString("sessionId", "")
            if (sid.isEmpty()) continue
            members.add(
                Member(
                    characterId = m.optString("characterId", ""),
                    name = m.optString("name", ""),
                    sessionId = sid,
                ),
            )
        }
        val books = o.optJSONArray("publicWorldBookIds")
        return Group(
            id = o.optString("id", ""),
            title = o.optString("title", ""),
            publicSessionId = o.optString("publicSessionId", ""),
            publicWorldBookIds = if (books == null) emptyList() else
                List(books.length()) { books.optString(it) },
            presetId = if (o.has("presetId")) o.optString("presetId").ifEmpty { null } else null,
            regexGroupIds = o.optJSONArray("regexGroupIds")?.let { arr ->
                List(arr.length()) { arr.optString(it) }
            },
            apiConfigId = if (o.has("apiConfigId")) o.optString("apiConfigId").ifEmpty { null } else null,
            narratorPrompt = o.optString("narratorPrompt").ifBlank { DEFAULT_NARRATOR },
            members = members,
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}

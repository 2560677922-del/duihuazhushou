package com.dbpg.duihuazhushou.session

import android.content.Context
import com.dbpg.stcore.session.SessionAction
import com.dbpg.stcore.session.SessionDoc
import com.dbpg.stcore.session.SessionIndexEntry
import com.dbpg.stcore.session.SessionStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 原生会话仓库：聊天页与列表页的唯一数据入口。
 *
 * 职责边界（刻意压得很窄）：
 *   - 只管「读哪个文件、写哪个文件、id 怎么生成」；
 *   - 不解析消息、不做编辑、不碰 UI —— 那些分别在 stcore 的 SessionEditor 与
 *     app 的 ChatController 里。
 *
 * 线程模型：
 *   读可能几百 KB，主线程读会掉帧；写还要 fsync。所以统一丢到一个单线程池，
 *   回调切回主线程。单线程同时保证"同一会话的两次写不会交叉"。
 *
 * 目录：files/data/sessions（与迁移落盘位置一致，两套入口读同一份文件）。
 */
object SessionRepo {

    private const val DIR = "data/sessions"

    private var store: SessionStore? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dhp-session-io") }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun init(context: Context) {
        if (store != null) return
        synchronized(this) {
            if (store != null) return
            store = SessionStore(File(File(context.applicationContext.filesDir, "data"), "sessions"))
        }
    }

    private fun requireStore(): SessionStore =
        store ?: error("SessionRepo 未初始化（先调用 init(context)）")

    /** 目录，供迁移/备份使用。 */
    fun dir(context: Context): File = File(File(context.applicationContext.filesDir, "data"), "sessions")

    // ------------------------------------------------------------ 读

    fun list(onResult: (List<SessionIndexEntry>) -> Unit) {
        io.execute {
            val list = runCatching { requireStore().list() }.getOrDefault(emptyList())
            main.post { onResult(list) }
        }
    }

    fun load(id: String, onResult: (SessionDoc?) -> Unit) {
        io.execute {
            val doc = runCatching { requireStore().load(id) }.getOrNull()
            main.post { onResult(doc) }
        }
    }

    // ------------------------------------------------------------ 写

    fun save(doc: SessionDoc, onDone: ((Boolean) -> Unit)? = null) {
        io.execute {
            val ok = runCatching { requireStore().save(doc) }.getOrDefault(false)
            if (onDone != null) main.post { onDone(ok) }
        }
    }

    fun delete(id: String, onDone: ((Boolean) -> Unit)? = null) {
        io.execute {
            val ok = runCatching { requireStore().delete(id) }.getOrDefault(false)
            if (onDone != null) main.post { onDone(ok) }
        }
    }

    /** 后台线程直接保存（调用方已在自己的 IO 线程里时用，避免再排一次队）。 */
    fun saveSync(doc: SessionDoc): Boolean = runCatching { requireStore().save(doc) }.getOrDefault(false)

    fun loadSync(id: String): SessionDoc? = runCatching { requireStore().load(id) }.getOrNull()

    // ------------------------------------------------------------ 新建

    fun newId(prefix: String): String =
        "${prefix}_${System.currentTimeMillis().toString(36)}${UUID.randomUUID().toString().take(6)}"

    fun create(title: String, scene: String = ""): SessionDoc {
        val now = System.currentTimeMillis()
        return SessionDoc(
            id = newId("s"),
            title = title.ifBlank { "新冒险" },
            sceneDescription = scene,
            createdAt = now,
            updatedAt = now,
        )
    }

    // ------------------------------------------------------------ 从网页快照兜底导入

    /**
     * 只取快照里的 sessionId，不做其它解析。
     *
     * ★ 这个方法**必须在后台线程调用**：快照即使已经瘦身（去掉图片 base64），
     *   一个长会话的正文也能到几 MB，JSONObject 化在主线程会卡住第一帧。
     *
     * 抓不到就返回空串，调用方按"新会话"处理。
     */
    fun sessionIdOf(snapshotJson: String): String =
        runCatching { JSONObject(snapshotJson).optString("sessionId", "") }.getOrDefault("")

    /**
     * 本地没有这个会话时，用网页快照构造一份。
     *
     * 快照里只有"展开后的消息"，没有 variants —— 所以这是**兜底**，
     * 不是迁移主路径（主路径是设置页/列表页的完整迁移，连历史版本一起搬）。
     * 有它至少保证"点原生聊天不空白"。
     *
     * 快照结构（见 web `bridge/nativeBridge.ts`）：
     *   { sessionId, messages:[{role,content,user?,streaming?}], config:{title, sceneDescription} }
     */
    fun fromSnapshot(snapshotJson: String): SessionDoc? {
        val root = runCatching { JSONObject(snapshotJson) }.getOrNull() ?: return null
        val arr = root.optJSONArray("messages") ?: JSONArray()
        val cfg = root.optJSONObject("config") ?: JSONObject()

        val now = System.currentTimeMillis()
        val actions = ArrayList<SessionAction>()
        var pendingUser = ""
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val role = o.optString("role", "assistant")
            val content = o.optString("content", "")
            when (role) {
                "user" -> pendingUser = content
                "assistant" -> {
                    // 草稿（streaming）不落成正式消息：它属于"未完成的一轮"，
                    // 下次打开仍由网页/原生各自恢复。
                    if (o.optBoolean("streaming", false)) continue
                    val user = o.optString("user", pendingUser)
                    if (user.isBlank() && content.isBlank()) continue
                    actions.add(
                        SessionAction(
                            id = newId("a"),
                            user = user,
                            ai = content,
                            timestamp = now + i,
                        ),
                    )
                    pendingUser = ""
                }
            }
        }

        val id = root.optString("sessionId", "").ifBlank { newId("s") }
        return SessionDoc(
            id = id,
            title = cfg.optString("title", "").ifBlank { "新冒险" },
            sceneDescription = cfg.optString("sceneDescription", ""),
            actions = actions,
            createdAt = now,
            updatedAt = now,
        )
    }
}

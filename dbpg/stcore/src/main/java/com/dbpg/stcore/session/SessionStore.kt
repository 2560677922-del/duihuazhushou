package com.dbpg.stcore.session

import java.io.File
import java.io.FileOutputStream

/**
 * 会话文件存储（原生权威）。
 *
 * 布局：
 *   <root>/index.json          —— 列表页用的索引（id / 标题 / 条数 / 时间 / 预览）
 *   <root>/<id>.json           —— 单个会话的完整内容
 *
 * 几个刻意的决定：
 *   1. **一个会话一个文件**：列表页只读 index.json，不必把几千条消息全解析一遍；
 *      单个会话损坏也只影响它自己。
 *   2. **原子写**：先写 .tmp → fsync → rename。写到一半断电不会留下半个 JSON。
 *   3. **不碰 Android API**：只用 java.io，所以能在 JVM 单测里跑真实读写，
 *      不用 Robolectric。调用方传目录进来即可。
 *   4. 索引只是缓存：`rebuildIndex()` 能随时从文件重建，索引坏了不会丢数据。
 *
 * 线程约定：本类内部同步（synchronized），但仍是阻塞 IO，
 * 调用方应在后台线程使用（app 侧统一走 `SessionRepo` 的单线程执行器）。
 */
class SessionStore(private val root: File) {

    private val indexFile = File(root, "index.json")

    /** 索引缓存：第一次访问时从磁盘读。 */
    private var indexCache: MutableList<SessionIndexEntry>? = null

    /** 导入结果，给界面显示"成功几个 / 跳过几个"。 */
    data class ImportResult(val imported: Int, val skipped: Int, val failed: Int)

    // ------------------------------------------------------------ 读

    /** 列表（按更新时间倒序）。 */
    @Synchronized
    fun list(): List<SessionIndexEntry> {
        val idx = ensureIndex()
        return idx.sortedByDescending { it.updatedAt }
    }

    /** 读一个会话；文件不存在或损坏返回 null。 */
    @Synchronized
    fun load(id: String): SessionDoc? {
        val f = fileOf(id) ?: return null
        if (!f.exists()) return null
        return SessionDoc.parse(runCatching { f.readText() }.getOrNull())
    }

    @Synchronized
    fun exists(id: String): Boolean = fileOf(id)?.exists() == true

    // ------------------------------------------------------------ 写

    /**
     * 保存（新增或覆盖），并同步索引。
     *
     * @return 落盘成功与否。失败时磁盘上的旧文件保持原样（原子写保证）。
     */
    @Synchronized
    fun save(doc: SessionDoc): Boolean {
        val f = fileOf(doc.id) ?: return false
        if (!root.exists() && !root.mkdirs()) return false

        val ok = writeAtomic(f, doc.toJsonString())
        if (!ok) return false

        val idx = ensureIndex()
        val entry = doc.toIndexEntry()
        val at = idx.indexOfFirst { it.id == doc.id }
        if (at >= 0) idx[at] = entry else idx.add(entry)
        writeIndex(idx)
        return true
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val f = fileOf(id) ?: return false
        val ok = if (f.exists()) f.delete() else true
        if (!ok) return false
        val idx = ensureIndex()
        idx.removeAll { it.id == id }
        writeIndex(idx)
        return true
    }

    /**
     * 批量导入（迁移用）。
     *
     * @param overwrite false 时同 id 已存在就跳过 —— 迁移默认不覆盖，
     *   避免用户已经在新 App 里聊过之后，又被旧数据冲掉。
     */
    @Synchronized
    fun importFrom(docs: List<SessionDoc>, overwrite: Boolean = false): ImportResult {
        var imported = 0
        var skipped = 0
        var failed = 0
        for (doc in docs) {
            if (doc.id.isEmpty()) {
                failed++
                continue
            }
            val f = fileOf(doc.id)
            if (f == null) {
                failed++
                continue
            }
            if (f.exists() && !overwrite) {
                skipped++
                continue
            }
            if (save(doc)) imported++ else failed++
        }
        return ImportResult(imported, skipped, failed)
    }

    // ------------------------------------------------------------ 索引维护

    /**
     * 从磁盘重建索引（索引丢了/被改坏了都能救回来）。
     *
     * 过程：扫目录里的 *.json（排除 index.json）→ 逐个解析出最小信息。
     * 坏文件跳过并计数，不影响其它会话。
     */
    @Synchronized
    fun rebuildIndex(): List<SessionIndexEntry> {
        if (!root.exists()) {
            indexCache = mutableListOf()
            return emptyList()
        }
        val out = mutableListOf<SessionIndexEntry>()
        root.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != "index.json" }
            ?.forEach { f ->
                val doc = SessionDoc.parse(runCatching { f.readText() }.getOrNull())
                if (doc != null && doc.id.isNotEmpty()) {
                    out.add(doc.toIndexEntry())
                } else {
                    // 文件名兜底：至少让它出现在列表里，能点进去看/删掉
                    val id = f.nameWithoutExtension
                    out.add(SessionIndexEntry(id = id, title = "（读取失败）$id"))
                }
            }
        indexCache = out
        writeIndex(out)
        return out
    }
    // ------------------------------------------------------------ 内部

    private fun ensureIndex(): MutableList<SessionIndexEntry> {
        indexCache?.let { return it }
        // 索引文件不存在 → 从目录重建。
        // 以前这里直接返回空列表：索引一旦丢失（清缓存、迁移中断、手动拷贝会话文件），
        // 磁盘上明明有会话，列表却显示"还没有会话"，用户以为数据全丢了。
        if (!indexFile.exists()) {
            return rebuildIndex().toMutableList()
        }
        val loaded = SessionIndexEntry.parseArray(runCatching { indexFile.readText() }.getOrNull())
        val list = loaded.toMutableList()
        indexCache = list
        return list
    }

    /**
     * id → 文件。
     *
     * 只接受安全字符：会话 id 会进文件名，带 `../` 就能写到目录外。
     * 正常情况下 id 是 `s_xxx` 形状，非法 id 直接拒绝（返回 null）。
     */
    private fun fileOf(id: String): File? {
        if (id.isEmpty() || id.length > 128) return null
        if (!SAFE_ID.matches(id)) return null
        return File(root, "$id.json")
    }

    private fun writeIndex(list: List<SessionIndexEntry>): Boolean {
        if (!root.exists() && !root.mkdirs()) return false
        return writeAtomic(indexFile, SessionIndexEntry.toJsonArray(list))
    }

    /**
     * 原子写：tmp → fsync → rename。
     *
     * rename 在同一分区上是原子的，所以读的一方要么看到旧文件、要么看到新文件，
     * 不会看到"写了一半"的状态。
     */
    private fun writeAtomic(target: File, text: String): Boolean {
        val tmp = File(target.parentFile, target.name + ".tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (target.exists() && !target.delete()) {
                // 极少数机型上 delete 会失败；保底直接覆盖，也不要让保存整个失败
                return runCatching { target.writeText(text) }.isSuccess
            }
            tmp.renameTo(target)
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            false
        }
    }

    companion object {
        /** 文件名白名单：字母数字下划线连字符点。 */
        private val SAFE_ID = Regex("^[A-Za-z0-9_.\\-]+$")
    }
}

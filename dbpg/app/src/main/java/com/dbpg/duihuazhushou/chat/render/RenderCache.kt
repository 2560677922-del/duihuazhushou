package com.dbpg.duihuazhushou.chat.render

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.LruCache
import com.dbpg.stcore.render.RenderKey

/**
 * 渲染产物缓存（L1 内存 + L2 SQLite）。
 *
 * ★ 这里存的都是**派生物**，不是数据。删掉只会让下一次渲染变慢，绝不会丢消息。
 *   任何"从缓存读正文"的写法都是错的 —— 权威数据永远在 SessionDoc。
 *
 * 为什么要落盘而不是只放内存：
 *   用户关掉 App 再打开，200 条消息如果每次都要重跑一遍两万字的美化正则，
 *   冷启动就是几秒钟的白屏。落盘之后第二次打开几乎瞬时。
 *
 * 存储选择 SQLite 而不是"一会话一个 JSON"：单条产物是可变长 HTML（可达几十 KB），
 * 200 条就是一个几 MB 的文件，多会话还会产生上千个小文件；SQLite 单表 + WAL
 * 写入稳定、按 key 删改方便，而且不需要引入 Room（项目现在没有，也别为此加）。
 */
class RenderCache private constructor(context: Context) {

    /** 一条消息的渲染产物。 */
    data class Entry(
        val plain: String,
        val html: String?,
        /** 本地图片路径（绝对路径）。文件可能被用户删掉，用的时候仍要 File.exists 过滤。 */
        val paths: List<String> = emptyList(),
        /** 卡片位图渲染后的高度（px），0 = 还没量过。滚动时先用它占位，避免跳动。 */
        val heightPx: Int = 0,
    )

    private val helper = Helper(context.applicationContext)

    /**
     * L1：内存缓存。
     *
     * 用"条目数"而不是字节数做上限：一条消息的产物通常在几 KB 到几十 KB，
     * 128 条最多几 MB，已经能覆盖一整屏 + 前后各几十条。
     */
    private val memory = object : LruCache<String, Entry>(160) {}

    private var puts = 0

    @Synchronized
    fun get(sessionId: String, key: RenderKey): Entry? {
        val k = cacheKey(sessionId, key)
        memory.get(k)?.let { return it }
        val db = runCatching { helper.readableDatabase }.getOrNull() ?: return null
        return runCatching {
            db.query(
                TABLE,
                arrayOf("plain", "html", "paths", "height_px"),
                "session_id=? AND key_hash=?",
                arrayOf(sessionId, key.stable()),
                null, null, null, "1",
            ).use { c ->
                if (!c.moveToFirst()) return null
                val e = Entry(
                    plain = c.getString(0) ?: "",
                    html = if (c.isNull(1)) null else c.getString(1),
                    paths = splitPaths(if (c.isNull(2)) null else c.getString(2)),
                    heightPx = c.getInt(3),
                )
                memory.put(k, e)
                e
            }
        }.getOrNull()
    }

    @Synchronized
    fun put(sessionId: String, key: RenderKey, entry: Entry) {
        memory.put(cacheKey(sessionId, key), entry)
        val db = runCatching { helper.writableDatabase }.getOrNull() ?: return
        runCatching {
            val cv = ContentValues().apply {
                put("session_id", sessionId)
                put("key_hash", key.stable())
                put("plain", entry.plain)
                if (entry.html == null) putNull("html") else put("html", entry.html)
                put("paths", joinPaths(entry.paths))
                put("height_px", entry.heightPx)
                put("updated_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            if (++puts % 200 == 0) trim(db)
        }
    }

    /** 只更新高度（卡片量完高度后回写，不重存正文）。 */
    @Synchronized
    fun putHeight(sessionId: String, key: RenderKey, heightPx: Int) {
        val k = cacheKey(sessionId, key)
        memory.get(k)?.let { if (it.heightPx != heightPx) memory.put(k, it.copy(heightPx = heightPx)) }
        val db = runCatching { helper.writableDatabase }.getOrNull() ?: return
        runCatching {
            val cv = ContentValues().apply { put("height_px", heightPx) }
            db.update(TABLE, cv, "session_id=? AND key_hash=?", arrayOf(sessionId, key.stable()))
        }
    }

    /** 删会话时清掉它的全部产物（分支/删除/导入覆盖都要调）。 */
    @Synchronized
    fun invalidateSession(sessionId: String) {
        memory.evictAll()
        val db = runCatching { helper.writableDatabase }.getOrNull() ?: return
        runCatching { db.delete(TABLE, "session_id=?", arrayOf(sessionId)) }
    }

    /**
     * 配置变了（换正则/预设/变量/字体）。
     *
     * 直接全清：产物是按 key_hash 存的，旧的虽然不会命中，但会白占空间；
     * 全清最省心，代价只是重算一次。
     */
    @Synchronized
    fun clearAll() {
        memory.evictAll()
        val db = runCatching { helper.writableDatabase }.getOrNull() ?: return
        runCatching { db.delete(TABLE, null, null) }
    }

    /**
     * 只清"外观"维度的产物是做不到的（key 是整体哈希），所以这里退化成清全部，
     * 但保留说明：外观变化（字号/字体）本来就会让所有缓存失效。
     */
    @Synchronized
    fun clearForLookChange() = clearAll()

    /** 当前占用（调试/设置页显示用）。 */
    @Synchronized
    fun stats(): Pair<Int, Long> {
        val db = runCatching { helper.readableDatabase }.getOrNull() ?: return 0 to 0L
        return runCatching {
            db.rawQuery("SELECT COUNT(*), COALESCE(SUM(LENGTH(plain)+LENGTH(COALESCE(html,''))),0) FROM $TABLE", null)
                .use { c -> if (c.moveToFirst()) c.getInt(0) to c.getLong(1) else 0 to 0L }
        }.getOrDefault(0 to 0L)
    }

    private fun cacheKey(sessionId: String, key: RenderKey): String = "$sessionId|${key.stable()}"

    /** 路径列表序列化：用不可见分隔符，路径里不会出现它。 */
    private fun joinPaths(paths: List<String>): String = paths.joinToString("\u0001")

    private fun splitPaths(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split('\u0001').filter { it.isNotEmpty() }
    }

    /** 全局上限 4000 条，按更新时间淘汰最旧的。 */
    private fun trim(db: SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM $TABLE WHERE rowid NOT IN " +
                "(SELECT rowid FROM $TABLE ORDER BY updated_at DESC LIMIT 4000)",
        )
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, "render_cache.db", null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                  session_id TEXT NOT NULL,
                  key_hash TEXT NOT NULL,
                  plain TEXT NOT NULL,
                  html TEXT,
                  paths TEXT,
                  height_px INTEGER NOT NULL DEFAULT 0,
                  updated_at INTEGER NOT NULL,
                  PRIMARY KEY (session_id, key_hash)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_rc_updated ON $TABLE(updated_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 缓存表结构变了直接重建：反正都是可重算的派生物
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    companion object {
        private const val TABLE = "render_cache"

        @Volatile
        private var instance: RenderCache? = null

        fun get(context: Context): RenderCache =
            instance ?: synchronized(this) {
                instance ?: RenderCache(context).also { instance = it }
            }
    }
}

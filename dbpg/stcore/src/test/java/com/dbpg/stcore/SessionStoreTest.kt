package com.dbpg.stcore

import com.dbpg.stcore.session.SessionAction
import com.dbpg.stcore.session.SessionDoc
import com.dbpg.stcore.session.SessionStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 会话存储测试：真实磁盘读写（临时目录），不依赖 Android。
 *
 * 重点不是"能存能取"，而是几种容易出事的场景：
 *   覆盖写、目录被删、索引损坏、坏文件不拖垮列表、id 越界。
 */
class SessionStoreTest {

    private lateinit var dir: File
    private lateinit var store: SessionStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("dbpg-sessions").toFile()
        store = SessionStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun doc(id: String, title: String = "会话$id", n: Int = 1) = SessionDoc(
        id = id,
        title = title,
        createdAt = 1000L,
        updatedAt = 1000L + n,
        actions = List(n) { SessionAction(id = "a$it", user = "u$it", ai = "ai$it", timestamp = 1000L + it) },
    )

    @Test
    fun `保存后能读回`() {
        assertTrue(store.save(doc("s1", "雨夜书店", 2)))
        val back = store.load("s1")
        assertNotNull(back)
        assertEquals("雨夜书店", back!!.title)
        assertEquals(2, back.actions.size)
        assertEquals("ai1", back.actions[1].ai)
    }

    @Test
    fun `列表按更新时间倒序`() {
        store.save(doc("s1", "旧", 1))
        store.save(doc("s2", "新", 5))
        val list = store.list()
        assertEquals(2, list.size)
        assertEquals("s2", list[0].id)
        assertEquals(1, list[1].count)
    }

    @Test
    fun `覆盖写不会留下临时文件`() {
        store.save(doc("s1", "第一版", 1))
        store.save(doc("s1", "第二版", 3))
        assertEquals("第二版", store.load("s1")!!.title)
        val leftovers = dir.listFiles { f -> f.name.endsWith(".tmp") }?.size ?: 0
        assertEquals(0, leftovers)
    }

    @Test
    fun `删除同时清索引`() {
        store.save(doc("s1"))
        store.save(doc("s2"))
        assertTrue(store.delete("s1"))
        assertEquals(1, store.list().size)
        assertNull(store.load("s1"))
    }

    @Test
    fun `目录被整个删掉后还能重建`() {
        store.save(doc("s1"))
        dir.deleteRecursively()
        // 索引在内存里还有，但文件没了：重新保存应自动建目录
        assertTrue(store.save(doc("s2")))
        assertTrue(File(dir, "s2.json").exists())
    }

    @Test
    fun `索引损坏时能从文件重建`() {
        store.save(doc("s1", "甲", 2))
        store.save(doc("s2", "乙", 3))
        File(dir, "index.json").writeText("{ 这不是数组")

        val fresh = SessionStore(dir)      // 新实例：不带内存索引
        val list = fresh.rebuildIndex()
        assertEquals(2, list.size)
        assertEquals(2, fresh.list().size)
    }

    @Test
    fun `坏文件不影响其他会话`() {
        store.save(doc("s1", "好", 1))
        File(dir, "bad.json").writeText("{ 坏掉了")
        val fresh = SessionStore(dir)
        val list = fresh.rebuildIndex()
        // 坏的也进列表（能点进去删），但标题标成读取失败
        assertEquals(2, list.size)
        val bad = list.first { it.id == "bad" }
        assertTrue(bad.title.contains("读取失败"))
        // 好的仍然正常
        assertEquals("好", fresh.load("s1")!!.title)
    }

    @Test
    fun `非法 id 拒绝写入`() {
        assertFalse(store.save(doc("../escape")))
        assertFalse(store.save(doc("a/b")))
        assertNull(store.load("../escape"))
        // 确认没有写到上级目录
        assertFalse(File(dir.parentFile, "escape.json").exists())
    }

    @Test
    fun `导入默认不覆盖已有会话`() {
        store.save(doc("s1", "本地版", 5))
        val r = store.importFrom(listOf(doc("s1", "旧备份", 1), doc("s2", "新会话", 2)))
        assertEquals(1, r.imported)
        assertEquals(1, r.skipped)
        assertEquals("本地版", store.load("s1")!!.title)
    }

    @Test
    fun `导入可以强制覆盖`() {
        store.save(doc("s1", "本地版", 5))
        val r = store.importFrom(listOf(doc("s1", "旧备份", 1)), overwrite = true)
        assertEquals(1, r.imported)
        assertEquals("旧备份", store.load("s1")!!.title)
    }

    @Test
    fun `空白会话也能存能列`() {
        store.save(SessionDoc(id = "empty", title = ""))
        assertEquals(1, store.list().size)
        assertEquals(0, store.list()[0].count)
    }

    @Test
    fun `列表预览取最后一条`() {
        store.save(doc("s1", "t", 2))
        assertEquals("ai1", store.list()[0].preview)
        assertTrue(store.list()[0].updatedAt > store.list()[0].createdAt)
    }
}

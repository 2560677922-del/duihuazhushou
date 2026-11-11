package com.dbpg.duihuazhushou.character

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 用户的角色卡（persona）。
 *
 * ## 为什么单独一份，不混进角色卡库
 * 角色卡是"要扮演的人"，用户卡是"你在这个故事里是谁"。两者的字段、
 * 使用位置、切换频率都不一样：
 *   - 角色卡：每个会话绑一个（私聊频道）/ 公共区每次发言换一个；
 *   - 用户卡：全局选一个，写进 `{{user}}` 和用户人设块，所有会话共用。
 *
 * 放在同一个列表里会让人以为"用户卡也能开聊"，所以资产页单开一栏。
 *
 * ## 存法
 *   `filesDir/personas/personas.json` —— 名字、描述、当前使用项
 *   `filesDir/personas/avatar_<id>.jpg` —— 头像（统一压成 512 长边 JPEG）
 *
 * 头像单独落文件而不是塞 JSON：base64 会让几十张卡的文件膨胀十几倍，
 * 而且聊天页每次读都要解码整份 JSON。
 */
object PersonaStore {

    private const val DIR = "personas"
    private const val INDEX = "personas.json"
    /** 头像长边上限：聊天气泡最大也就 40dp，512 足够且省内存 */
    private const val AVATAR_MAX = 512

    data class Persona(
        val id: String,
        val name: String,
        val description: String = "",
        /** 头像文件名（不含目录）；空 = 用默认占位 */
        val avatar: String = "",
    )

    private fun dir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun indexFile(ctx: Context): File = File(dir(ctx), INDEX)

    fun list(ctx: Context): List<Persona> = read(ctx).second

    /** 当前使用的用户角色；没有就返回 null。 */
    fun active(ctx: Context): Persona? {
        val (activeId, items) = read(ctx)
        return items.firstOrNull { it.id == activeId } ?: items.firstOrNull()
    }

    fun get(ctx: Context, id: String): Persona? =
        if (id.isBlank()) null else read(ctx).second.firstOrNull { it.id == id }

    fun setActive(ctx: Context, id: String) {
        val (_, items) = read(ctx)
        if (items.none { it.id == id }) return
        write(ctx, id, items)
    }

    /** 新建或更新；返回落盘后的条目。 */
    fun upsert(ctx: Context, p: Persona): Persona {
        val (activeId, items) = read(ctx)
        val id = p.id.ifBlank { "p_" + UUID.randomUUID().toString().take(8) }
        val saved = p.copy(id = id, name = p.name.trim().ifBlank { "我" })
        val next = items.toMutableList()
        val at = next.indexOfFirst { it.id == id }
        if (at >= 0) next[at] = saved else next.add(saved)
        // 第一个用户角色自动设为当前，省一步点击
        write(ctx, activeId.ifBlank { id }, next)
        return saved
    }

    fun delete(ctx: Context, id: String) {
        val (activeId, items) = read(ctx)
        val target = items.firstOrNull { it.id == id } ?: return
        if (target.avatar.isNotBlank()) runCatching { File(dir(ctx), target.avatar).delete() }
        val next = items.filterNot { it.id == id }
        write(ctx, if (activeId == id) next.firstOrNull()?.id.orEmpty() else activeId, next)
    }

    /**
     * 把选中的图片压成头像落盘，返回文件名。
     *
     * 失败返回空串（调用方保持原头像）—— 选图失败不该让整个编辑作废。
     */
    fun saveAvatar(ctx: Context, personaId: String, uri: Uri): String {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ""
        val scale = AVATAR_MAX.toFloat() / maxOf(src.width, src.height)
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            src
        }
        val name = "avatar_$personaId.jpg"
        val ok = runCatching {
            File(dir(ctx), name).outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
        }.isSuccess
        if (bmp !== src) bmp.recycle()
        src.recycle()
        return if (ok) name else ""
    }

    fun avatarFile(ctx: Context, p: Persona?): File? {
        val name = p?.avatar.orEmpty()
        if (name.isBlank()) return null
        val f = File(dir(ctx), name)
        return if (f.exists()) f else null
    }

    // ------------------------------------------------------------ 序列化

    private fun read(ctx: Context): Pair<String, List<Persona>> {
        val f = indexFile(ctx)
        if (!f.exists()) return "" to emptyList()
        val root = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return "" to emptyList()
        val arr = root.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<Persona>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) continue
            out.add(
                Persona(
                    id = id,
                    name = o.optString("name", "我"),
                    description = o.optString("description", ""),
                    avatar = o.optString("avatar", ""),
                ),
            )
        }
        return root.optString("activeId", "") to out
    }

    private fun write(ctx: Context, activeId: String, items: List<Persona>) {
        val root = JSONObject()
            .put("activeId", activeId)
            .put("items", JSONArray().also { a ->
                items.forEach { p ->
                    a.put(
                        JSONObject()
                            .put("id", p.id)
                            .put("name", p.name)
                            .put("description", p.description)
                            .put("avatar", p.avatar),
                    )
                }
            })
        runCatching { indexFile(ctx).writeText(root.toString()) }
    }
}

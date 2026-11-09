package com.dbpg.duihuazhushou.character

import com.dbpg.duihuazhushou.Insets

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.MainActivity
import com.dbpg.duihuazhushou.R
import com.dbpg.stcore.character.CharacterCard
import com.dbpg.stcore.character.CharacterCardParser
import org.json.JSONObject
import java.io.File

/**
 * 角色卡详情（全屏）。
 *
 * 取代原来的 AlertDialog：长描述要能滚动、能长按复制、能翻备用开场白，
 * 弹窗做这些很别扭。
 *
 * 读取路径只有一条：按 path 读原始字节 → CharacterCardParser（和列表页同一个解析器），
 * 不缓存、不复制数据，避免详情和列表看到两份不一致的内容。
 */
class CharacterDetailActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"
    }

    private lateinit var card: CharacterCard
    private lateinit var file: File
    private var greetingIndex = 0

    private lateinit var greetingView: TextView
    private lateinit var previewLabel: TextView
    private lateinit var prevBtn: TextView
    private lateinit var nextBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_character_detail)
        Insets.applyTop(this)

        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        file = File(path)
        val parsed = runCatching {
            CharacterCardParser.parse(file.readBytes(), file.nameWithoutExtension)
        }.getOrNull()
        if (parsed == null) {
            Toast.makeText(this, "这张卡解析失败，无法查看", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        card = parsed

        findViewById<TextView>(R.id.detail_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.detail_chat).setOnClickListener { startChat() }
        findViewById<TextView>(R.id.detail_edit).setOnClickListener { startEdit() }
        findViewById<TextView>(R.id.detail_delete).setOnClickListener { confirmDelete() }

        greetingView = findViewById(R.id.detail_greeting)
        previewLabel = findViewById(R.id.detail_greeting_index)
        prevBtn = findViewById(R.id.detail_greeting_prev)
        nextBtn = findViewById(R.id.detail_greeting_next)

        bindHeader()
        bindSections()
        bindGreetings()
    }

    private fun bindHeader() {
        findViewById<TextView>(R.id.detail_title).text = card.name
        findViewById<TextView>(R.id.detail_name).text = card.name

        val tags = card.tags.joinToString("  ") { "#$it" }
        findViewById<TextView>(R.id.detail_tags).apply {
            text = tags
            visibility = if (tags.isBlank()) View.GONE else View.VISIBLE
        }

        val avatar = findViewById<ImageView>(R.id.detail_avatar)
        val bytes = card.avatarBytes
        if (bytes != null) {
            val bmp = runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= 200) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }.getOrNull()
            if (bmp != null) avatar.setImageBitmap(bmp)
        }
    }

    private fun bindSections() {
        section(R.id.detail_desc_title, R.id.detail_desc, "角色描述", card.description)
        section(R.id.detail_persona_title, R.id.detail_persona, "人格设定", card.personality)
        section(R.id.detail_scene_title, R.id.detail_scene, "场景", card.scenario)
    }

    /** 空字段整段隐藏，别在详情页堆一片"（空）"。 */
    private fun section(titleId: Int, bodyId: Int, title: String, body: String) {
        val t = findViewById<TextView>(titleId)
        val b = findViewById<TextView>(bodyId)
        if (body.isBlank()) {
            t.visibility = View.GONE
            b.visibility = View.GONE
            return
        }
        t.text = title
        b.text = body
        // 长按复制：详情页里的设定经常要拿去改，能选中很实用
        // 只开选中；不要覆盖 movementMethod，否则长按选区的行为会变
        b.setTextIsSelectable(true)
    }

    private fun bindGreetings() {
        val list = card.greetings
        if (list.isEmpty()) {
            greetingView.text = "（这张卡没有开场白）"
            greetingView.setTextColor(0xFF66707F.toInt())
            return
        }
        greetingView.setTextIsSelectable(true)
        renderGreeting()

        if (list.size <= 1) return
        prevBtn.visibility = View.VISIBLE
        nextBtn.visibility = View.VISIBLE
        previewLabel.visibility = View.VISIBLE
        prevBtn.setOnClickListener { moveGreeting(-1) }
        nextBtn.setOnClickListener { moveGreeting(1) }
    }

    private fun moveGreeting(delta: Int) {
        val list = card.greetings
        greetingIndex = (greetingIndex + delta + list.size) % list.size
        renderGreeting()
    }

    private fun renderGreeting() {
        val list = card.greetings
        if (list.isEmpty()) return
        greetingView.text = list[greetingIndex]
        previewLabel.text = "${greetingIndex + 1}/${list.size}"
    }

    // ------------------------------------------------------------ 操作

    private fun startChat() {
        val web = MainActivity.getWebView()
        if (web == null) {
            Toast.makeText(this, "网页还在启动，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        val json = JSONObject.quote(CharacterJson.toV2(card))
        web.evaluateJavascript("window.__dhpApp && window.__dhpApp.useCharacter($json);", null)
        finish()
    }

    /** 带路径打开编辑器；编辑完回到详情页需要重新读文件，所以这里 finish 后由列表/历史兜底。 */
    private fun startEdit() {
        startActivity(
            Intent(this, CharacterEditActivity::class.java)
                .putExtra(CharacterEditActivity.EXTRA_PATH, file.absolutePath),
        )
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage("删除后本地文件就没了，确定删除《${card.name}》？")
            .setPositiveButton("删除") { _, _ ->
                val ok = CharacterRepository(this).delete(file)
                Toast.makeText(this, if (ok) "已删除" else "删除失败", Toast.LENGTH_SHORT).show()
                if (ok) finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

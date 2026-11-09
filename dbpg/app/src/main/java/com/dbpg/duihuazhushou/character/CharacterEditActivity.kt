package com.dbpg.duihuazhushou.character

import com.dbpg.duihuazhushou.Insets

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.R
import com.dbpg.stcore.character.CharacterCard
import com.dbpg.stcore.character.CharacterCardParser
import java.io.File

/**
 * 原生角色卡编辑器（傻酒馆 / SillyTavern V2 格式）。
 *
 * 新建和编辑共用这一个页面：
 *   - 不带 EXTRA_PATH → 空白新建；
 *   - 带 EXTRA_PATH → 把那张卡解析出来，把字段回填进表单。
 *
 * 保存时按"有没有头像"决定落成 .png（V2 JSON 嵌进 tEXt 块）还是 .json（纯 V2 JSON），
 * 和列表页 / 详情页的解析器完全对得上，导回网页版或其它 ST 客户端都能读。
 */
class CharacterEditActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val REQ_PICK_AVATAR = 3001
        /** 占位：头像没动过，沿用原卡。用 identity 比较，不能用 null/空区分。 */
        private val AVATAR_UNCHANGED = ByteArray(0)
        /** 占位：用户主动清除了头像。 */
        private val AVATAR_CLEARED = ByteArray(0)
    }

    private lateinit var repo: CharacterRepository
    /** 编辑模式下被改写的文件；新建时为 null。 */
    private var existingFile: File? = null
    /** 原始头像字节（编辑时用于"没换头像就沿用"）。 */
    private var originalAvatar: ByteArray? = null
    /** 用户本次新选的头像；null = 沿用 originalAvatar，AVATAR_CLEARED = 主动清除。 */
    private var pickedAvatar: ByteArray? = AVATAR_UNCHANGED

    private lateinit var avatarView: ImageView
    private lateinit var clearBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_character_edit)
        Insets.applyTop(this)
        repo = CharacterRepository(this)

        avatarView = findViewById(R.id.edit_avatar)
        clearBtn = findViewById(R.id.btn_clear_avatar)

        val path = intent.getStringExtra(EXTRA_PATH)
        val editing = !path.isNullOrEmpty()
        findViewById<TextView>(R.id.edit_title).text = if (editing) "编辑角色" else "新建角色"

        if (editing) {
            existingFile = File(path)
            val card = runCatching {
                CharacterCardParser.parse(existingFile!!.readBytes(), existingFile!!.nameWithoutExtension)
            }.getOrNull()
            if (card == null) {
                Toast.makeText(this, "这张卡解析失败，无法编辑", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            originalAvatar = card.avatarBytes
            prefill(card)
            if (originalAvatar != null) showAvatar(originalAvatar)
        }

        findViewById<TextView>(R.id.edit_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.edit_save).setOnClickListener { save() }
        findViewById<TextView>(R.id.btn_pick_avatar).setOnClickListener { pickAvatar() }
        clearBtn.setOnClickListener {
            pickedAvatar = AVATAR_CLEARED
            avatarView.setImageResource(R.drawable.ic_avatar_placeholder)
            clearBtn.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------ 回填

    private fun prefill(card: CharacterCard) {
        setText(R.id.edit_name, card.name)
        setText(R.id.edit_description, card.description)
        setText(R.id.edit_personality, card.personality)
        setText(R.id.edit_scenario, card.scenario)
        setText(R.id.edit_first_mes, card.firstMessage)
        setText(R.id.edit_mes_example, card.messageExample)
        setText(R.id.edit_alternate, card.alternateGreetings.joinToString("\n"))
        setText(R.id.edit_tags, card.tags.joinToString(", "))
        setText(R.id.edit_system_prompt, card.systemPrompt)
        setText(R.id.edit_post_history, card.postHistoryInstructions)
        setText(R.id.edit_creator_notes, card.creatorNotes)
        setText(R.id.edit_creator, card.creator)
        setText(R.id.edit_version, card.characterVersion)
    }

    private fun setText(id: Int, value: String) {
        findViewById<EditText>(id).setText(value)
    }

    // ------------------------------------------------------------ 保存

    private fun save() {
        val name = findViewById<EditText>(R.id.edit_name).text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "角色名称不能为空", Toast.LENGTH_SHORT).show()
            return
        }

        val card = CharacterCard(
            spec = "chara_card_v2",
            name = name,
            description = fieldText(R.id.edit_description),
            personality = fieldText(R.id.edit_personality),
            scenario = fieldText(R.id.edit_scenario),
            firstMessage = fieldText(R.id.edit_first_mes),
            messageExample = fieldText(R.id.edit_mes_example),
            creatorNotes = fieldText(R.id.edit_creator_notes),
            systemPrompt = fieldText(R.id.edit_system_prompt),
            postHistoryInstructions = fieldText(R.id.edit_post_history),
            alternateGreetings = splitLines(fieldText(R.id.edit_alternate)),
            tags = splitTags(fieldText(R.id.edit_tags)),
            creator = fieldText(R.id.edit_creator),
            characterVersion = fieldText(R.id.edit_version),
        )

        // 头像优先级：新选的 > 主动清除(null) > 沿用原卡
        val avatar = when {
            pickedAvatar === AVATAR_CLEARED -> null
            pickedAvatar !== AVATAR_UNCHANGED -> pickedAvatar
            else -> originalAvatar
        }

        val result = runCatching { repo.save(card, avatar, existingFile) }
        result.onSuccess {
            Toast.makeText(this, "已保存：${it.displayName}", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }.onFailure {
            Toast.makeText(this, it.message ?: "保存失败", Toast.LENGTH_LONG).show()
        }
    }

    private fun fieldText(id: Int): String =
        findViewById<EditText>(id).text.toString().replace("\r\n", "\n").trimEnd()

    private fun splitLines(s: String): List<String> =
        s.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    private fun splitTags(s: String): List<String> =
        s.split(Regex("[,，、\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }.toList()

    // ------------------------------------------------------------ 头像

    private fun pickAvatar() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        runCatching { startActivityForResult(intent, REQ_PICK_AVATAR) }
            .onFailure { Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show() }
    }

    private fun showAvatar(bytes: ByteArray?) {
        if (bytes == null) {
            avatarView.setImageResource(R.drawable.ic_avatar_placeholder)
            clearBtn.visibility = View.GONE
            return
        }
        val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        if (bmp != null) {
            avatarView.setImageBitmap(bmp)
            clearBtn.visibility = View.VISIBLE
        }
    }

    @Deprecated("系统文件选择器只有这一条回调路径")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_AVATAR || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        Thread {
            val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                .getOrNull()?.takeIf { it.isNotEmpty() }
            runOnUiThread {
                if (bytes == null) {
                    Toast.makeText(this, "无法读取所选图片", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                pickedAvatar = bytes
                showAvatar(bytes)
            }
        }.start()
    }
}

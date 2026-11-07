package com.dbpg.duihuazhushou.appearance

import com.dbpg.duihuazhushou.Insets

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.chat.ChatBackground

/**
 * 原生外观设置页。
 *
 * 交互原则：**改一下存一下**，没有"保存"按钮 —— 用户从设置页退回来就该看到效果，
 * 少一步确认就少一次"我明明改了怎么没变"的困惑。
 */
class SettingsActivity : Activity() {

    companion object {
        private const val REQ_BG_GLOBAL = 2101
    }

    private lateinit var cfg: AppearanceConfig
    private lateinit var preview: TextView
    private lateinit var sizeValue: TextView
    private lateinit var lineValue: TextView
    private lateinit var widthValue: TextView
    private lateinit var sizeBar: SeekBar
    private lateinit var lineBar: SeekBar
    private lateinit var widthBar: SeekBar
    private lateinit var hideSwitch: Switch
    private lateinit var htmlSwitch: Switch
    private lateinit var scriptSwitch: Switch
    private lateinit var bubbleBtn: TextView
    private lateinit var bgValue: TextView
    private lateinit var dimBar: SeekBar
    private lateinit var dimValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Insets.applyTop(this)
        cfg = Appearance.load(this)

        preview = findViewById(R.id.set_preview)
        sizeValue = findViewById(R.id.set_size_value)
        lineValue = findViewById(R.id.set_line_value)
        widthValue = findViewById(R.id.set_width_value)
        sizeBar = findViewById(R.id.set_size)
        lineBar = findViewById(R.id.set_line)
        widthBar = findViewById(R.id.set_width)
        hideSwitch = findViewById(R.id.set_hide_reasoning)
        htmlSwitch = findViewById(R.id.set_html_render)
        scriptSwitch = findViewById(R.id.set_allow_script)
        bubbleBtn = findViewById(R.id.set_bubble_style)
        bgValue = findViewById(R.id.set_bg_value)
        dimBar = findViewById(R.id.set_bg_dim)
        dimValue = findViewById(R.id.set_bg_dim_value)

        bubbleBtn.setOnClickListener { pickBubbleStyle() }
        bgValue.setOnClickListener { pickGlobalBackground() }
        dimBar.max = 90
        dimBar.progress = cfg.bgDim

        findViewById<TextView>(R.id.set_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.set_reset).setOnClickListener { reset() }
        // 数据迁移：独立页面，这里只做入口（迁移逻辑不塞进设置页）
        findViewById<TextView>(R.id.set_migrate).setOnClickListener {
            startActivity(android.content.Intent(this, com.dbpg.duihuazhushou.data.MigrationActivity::class.java))
        }

        // 字号 12.0 ~ 24.0 sp，按 0.1 步进
        sizeBar.max = 120
        sizeBar.progress = ((cfg.textSizeSp - 12f) * 10).toInt().coerceIn(0, 120)
        // 行距 1.0 ~ 2.0
        lineBar.max = 100
        lineBar.progress = ((cfg.lineSpacing - 1f) * 100).toInt().coerceIn(0, 100)
        // 气泡宽度 60% ~ 94%
        widthBar.max = 34
        widthBar.progress = (cfg.bubbleWidthPercent - 60).coerceIn(0, 34)
        hideSwitch.isChecked = cfg.hideReasoning
        htmlSwitch.isChecked = cfg.htmlRender
        scriptSwitch.isChecked = cfg.allowScript

        bind()
        render()
    }

    private fun bind() {
        sizeBar.setOnSeekBarChangeListener(onChange { p ->
            cfg = cfg.copy(textSizeSp = 12f + p / 10f)
        })
        lineBar.setOnSeekBarChangeListener(onChange { p ->
            cfg = cfg.copy(lineSpacing = 1f + p / 100f)
        })
        widthBar.setOnSeekBarChangeListener(onChange { p ->
            cfg = cfg.copy(bubbleWidthPercent = 60 + p)
        })
        hideSwitch.setOnCheckedChangeListener { _, checked ->
            cfg = cfg.copy(hideReasoning = checked)
            Appearance.save(this, cfg)
        }
        htmlSwitch.setOnCheckedChangeListener { _, checked ->
            cfg = cfg.copy(htmlRender = checked)
            Appearance.save(this, cfg)
        }
        dimBar.setOnSeekBarChangeListener(onChange { p ->
            cfg = cfg.copy(bgDim = p)
        })
        scriptSwitch.setOnCheckedChangeListener { _, checked ->
            cfg = cfg.copy(allowScript = checked)
            Appearance.save(this, cfg)
        }
    }

    private fun onChange(apply: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
            apply(progress)
            Appearance.save(this@SettingsActivity, cfg)
            render()
        }

        override fun onStartTrackingTouch(bar: SeekBar?) = Unit
        override fun onStopTrackingTouch(bar: SeekBar?) = Unit
    }

    private fun render() {
        sizeValue.text = String.format("%.1f sp", cfg.textSizeSp)
        lineValue.text = String.format("%.2f 倍", cfg.lineSpacing)
        widthValue.text = "${cfg.bubbleWidthPercent}%"

        bubbleBtn.text = BubbleStyle.byId(cfg.bubbleStyle).label
        bgValue.text = if (ChatBackground.hasGlobal(this)) "已设置" else "选择图片"
        dimValue.text = "${cfg.bgDim}%"

        preview.textSize = cfg.textSizeSp
        preview.setLineSpacing(0f, cfg.lineSpacing)
        // 预览块跟着宽度设置收缩，所见即所得
        val screen = resources.displayMetrics.widthPixels
        val margin = (32 * resources.displayMetrics.density).toInt()
        preview.maxWidth = ((screen - margin) * cfg.bubbleWidthPercent / 100).coerceAtLeast(screen / 3)
    }

    /** 气泡款式：单选对话框。改完立即保存，回聊天页就生效。 */
    private fun pickBubbleStyle() {
        val all = BubbleStyle.ALL
        val labels = all.map { "${it.label}（圆角 ${it.cornerDp.toInt()}）" }.toTypedArray()
        val current = all.indexOfFirst { it.id == cfg.bubbleStyle }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("气泡款式")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                cfg = cfg.copy(bubbleStyle = all[which].id)
                Appearance.save(this, cfg)
                render()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 全局聊天背景：选图 / 清除。
     *
     * 只做「全局」这一层；单会话背景在聊天页 ⋯ 里换 —— 两层各管一处，
     * 用户不用在一堆设置里找"这条会话的背景到底存在哪"。
     */
    private fun pickGlobalBackground() {
        val has = ChatBackground.hasGlobal(this)
        val items = if (has) arrayOf("选择图片", "清除（改为纯色）") else arrayOf("选择图片")
        AlertDialog.Builder(this)
            .setTitle("聊天背景（全局）")
            .setItems(items) { _, which ->
                if (which == 0) {
                    try {
                        startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "image/*"
                            },
                            REQ_BG_GLOBAL,
                        )
                    } catch (t: Throwable) {
                        android.widget.Toast.makeText(this, "打不开图片选择器", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    ChatBackground.clear(this, null)
                    render()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_BG_GLOBAL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            if (ChatBackground.save(this, null, bmp)) render()
        }
    }

    private fun reset() {
        cfg = AppearanceConfig()
        Appearance.save(this, cfg)
        sizeBar.progress = ((cfg.textSizeSp - 12f) * 10).toInt()
        lineBar.progress = ((cfg.lineSpacing - 1f) * 100).toInt()
        widthBar.progress = cfg.bubbleWidthPercent - 60
        hideSwitch.isChecked = cfg.hideReasoning
        htmlSwitch.isChecked = cfg.htmlRender
        scriptSwitch.isChecked = cfg.allowScript
        dimBar.progress = cfg.bgDim
        render()
    }
}

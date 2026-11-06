package com.dbpg.duihuazhushou.appearance

import android.app.Activity
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.dbpg.duihuazhushou.R

/**
 * 原生外观设置页。
 *
 * 交互原则：**改一下存一下**，没有"保存"按钮 —— 用户从设置页退回来就该看到效果，
 * 少一步确认就少一次"我明明改了怎么没变"的困惑。
 */
class SettingsActivity : Activity() {

    private lateinit var cfg: AppearanceConfig
    private lateinit var preview: TextView
    private lateinit var sizeValue: TextView
    private lateinit var lineValue: TextView
    private lateinit var widthValue: TextView
    private lateinit var sizeBar: SeekBar
    private lateinit var lineBar: SeekBar
    private lateinit var widthBar: SeekBar
    private lateinit var hideSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        cfg = Appearance.load(this)

        preview = findViewById(R.id.set_preview)
        sizeValue = findViewById(R.id.set_size_value)
        lineValue = findViewById(R.id.set_line_value)
        widthValue = findViewById(R.id.set_width_value)
        sizeBar = findViewById(R.id.set_size)
        lineBar = findViewById(R.id.set_line)
        widthBar = findViewById(R.id.set_width)
        hideSwitch = findViewById(R.id.set_hide_reasoning)

        findViewById<TextView>(R.id.set_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.set_reset).setOnClickListener { reset() }

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

        preview.textSize = cfg.textSizeSp
        preview.setLineSpacing(0f, cfg.lineSpacing)
        // 预览块跟着宽度设置收缩，所见即所得
        val screen = resources.displayMetrics.widthPixels
        val margin = (32 * resources.displayMetrics.density).toInt()
        preview.maxWidth = ((screen - margin) * cfg.bubbleWidthPercent / 100).coerceAtLeast(screen / 3)
    }

    private fun reset() {
        cfg = AppearanceConfig()
        Appearance.save(this, cfg)
        sizeBar.progress = ((cfg.textSizeSp - 12f) * 10).toInt()
        lineBar.progress = ((cfg.lineSpacing - 1f) * 100).toInt()
        widthBar.progress = cfg.bubbleWidthPercent - 60
        hideSwitch.isChecked = cfg.hideReasoning
        render()
    }
}

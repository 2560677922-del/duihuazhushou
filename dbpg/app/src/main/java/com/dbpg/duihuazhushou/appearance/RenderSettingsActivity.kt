package com.dbpg.duihuazhushou.appearance

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.dbpg.duihuazhushou.Insets
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.summary.SummaryConfig

/**
 * 「渲染与上下文」设置页：性能开关 + 自动摘要。
 *
 * ★ 为什么单开一页
 *   渲染（怎么画）和摘要（发什么）都不是"外观"，混在 UI 风格页里
 *   用户找不到，也不知道关掉会发生什么。这一页每行都带一句人话解释。
 *
 * ★ 交互仍是"改一下存一下"：没有保存按钮。
 */
class RenderSettingsActivity : Activity() {

    private lateinit var look: AppearanceConfig
    private lateinit var sum: SummaryConfig

    private lateinit var htmlSwitch: Switch
    private lateinit var scriptSwitch: Switch

    private lateinit var sumSwitch: Switch
    private lateinit var sumPresetSwitch: Switch
    private lateinit var sumModeValue: TextView
    private lateinit var sumEveryValue: TextView
    private lateinit var sumTakeValue: TextView
    private lateinit var sumCharsValue: TextView
    private lateinit var sumPromptValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_render_settings)
        Insets.applyTop(this)

        look = Appearance.load(this)
        sum = SummaryConfig.load(this)

        findViewById<android.view.View>(R.id.rnd_back).setOnClickListener { finish() }

        htmlSwitch = findViewById(R.id.rnd_html)
        scriptSwitch = findViewById(R.id.rnd_script)
        sumSwitch = findViewById(R.id.sum_enabled)
        sumPresetSwitch = findViewById(R.id.sum_preset)
        sumModeValue = findViewById(R.id.sum_mode)
        sumEveryValue = findViewById(R.id.sum_every)
        sumTakeValue = findViewById(R.id.sum_take)
        sumCharsValue = findViewById(R.id.sum_chars)
        sumPromptValue = findViewById(R.id.sum_prompt_value)

        htmlSwitch.setOnCheckedChangeListener { _, on ->
            saveLook(look.copy(htmlRender = on))
        }
        scriptSwitch.setOnCheckedChangeListener { _, on ->
            saveLook(look.copy(allowScript = on))
        }

        sumSwitch.setOnCheckedChangeListener { _, on ->
            saveSum(sum.copy(enabled = on))
        }
        sumPresetSwitch.setOnCheckedChangeListener { _, on ->
            saveSum(sum.copy(withPreset = on))
        }

        sumModeValue.setOnClickListener { pickMode() }
        sumEveryValue.setOnClickListener { editEvery() }
        sumTakeValue.setOnClickListener { editTake() }
        sumCharsValue.setOnClickListener { editChars() }
        findViewById<android.view.View>(R.id.sum_prompt_row).setOnClickListener { editPrompt() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 可能被别处改过（例如重置外观），重新读一次最稳
        look = Appearance.load(this)
        sum = SummaryConfig.load(this)
        refresh()
    }

    // ------------------------------------------------------------ 保存

    private fun saveLook(next: AppearanceConfig) {
        look = next
        Appearance.save(this, next)
        // 脚本开关依赖美化渲染：关掉美化时脚本自然无效，这里只做视觉提示
        refresh()
    }

    private fun saveSum(next: SummaryConfig) {
        sum = next.sanitized()
        SummaryConfig.save(this, sum)
        refresh()
    }

    // ------------------------------------------------------------ 刷新

    private fun refresh() {
        htmlSwitch.isChecked = look.htmlRender
        scriptSwitch.isChecked = look.allowScript
        scriptSwitch.isEnabled = look.htmlRender
        scriptSwitch.alpha = if (look.htmlRender) 1f else 0.45f

        sumSwitch.isChecked = sum.enabled
        sumPresetSwitch.isChecked = sum.withPreset
        sumModeValue.text = if (sum.mode == SummaryConfig.MODE_CHARS) "按字数" else "按条数"
        sumEveryValue.text = if (sum.mode == SummaryConfig.MODE_CHARS) {
            "${sum.everyChars} 字"
        } else {
            "${sum.everyCount} 条"
        }
        // 摘要关闭时，下面这些参数变灰但仍可调（用户可以先设好再开）
        sumTakeValue.text = "${sum.takeCount} 条"
        sumCharsValue.text = "${sum.maxChars} 字"
        sumPromptValue.text = if (sum.customPrompt.isBlank()) "未设置" else "已设置"
        val dim = if (sum.enabled) 1f else 0.5f
        listOf(sumModeValue, sumEveryValue, sumTakeValue, sumCharsValue, sumPromptValue)
            .forEach { it.alpha = dim }
    }

    // ------------------------------------------------------------ 弹窗

    private fun pickMode() {
        val items = arrayOf("按条数（每 N 条触发）", "按字数（每 N 字触发）")
        val cur = if (sum.mode == SummaryConfig.MODE_CHARS) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("触发方式")
            .setSingleChoiceItems(items, cur) { d, which ->
                saveSum(sum.copy(mode = if (which == 1) SummaryConfig.MODE_CHARS else SummaryConfig.MODE_COUNT))
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editEvery() {
        if (sum.mode == SummaryConfig.MODE_CHARS) {
            numberDialog("每多少字触发", sum.everyChars, 500, 100000) {
                saveSum(sum.copy(everyChars = it))
            }
        } else {
            numberDialog("每多少条触发", sum.everyCount, 3, 100) {
                saveSum(sum.copy(everyCount = it))
            }
        }
    }

    private fun editTake() {
        numberDialog("每次浓缩几条", sum.takeCount, 1, 50) {
            saveSum(sum.copy(takeCount = it))
        }
    }

    private fun editChars() {
        numberDialog("摘要字数上限", sum.maxChars, 80, 2000) {
            saveSum(sum.copy(maxChars = it))
        }
    }

    /**
     * 自定义摘要提示词。
     *
     * 之所以做成"独立的一段文本"而不是替换内置指令：内置的那几条
     * （保留人物/承诺/冲突、删掉环境描写）是摘要不跑偏的底线，
     * 用户加的偏好应该**叠加**在它后面，而不是把它冲掉。
     */
    private fun editPrompt() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val tip = TextView(this).apply {
            text = "会追加在内置摘要指令后面。留空则只用内置规则。"
            setTextSize(12f)
            setTextColor(0xFF66707F.toInt())
        }
        val input = EditText(this).apply {
            setText(sum.customPrompt)
            hint = "例如：按时间分条；保留所有数字、承诺与未解决的矛盾；用第三人称"
            setTextSize(14f)
            setTextColor(0xFF1A2230.toInt())
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            gravity = Gravity.TOP
            minLines = 5
            maxLines = 10
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        box.addView(tip)
        box.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        AlertDialog.Builder(this)
            .setTitle("自定义摘要提示词")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                saveSum(sum.copy(customPrompt = input.text?.toString().orEmpty().trim()))
            }
            .setNeutralButton("清空") { _, _ ->
                saveSum(sum.copy(customPrompt = ""))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun numberDialog(title: String, cur: Int, min: Int, max: Int, onOk: (Int) -> Unit) {
        val input = EditText(this).apply {
            setText(cur.toString())
            setTextSize(15f)
            setTextColor(0xFF1A2230.toInt())
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = InputType.TYPE_CLASS_NUMBER
            setSelection(text?.length ?: 0)
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("范围 $min ~ $max")
            .setView(wrap)
            .setPositiveButton("确定") { _, _ ->
                val v = input.text?.toString()?.trim()?.toIntOrNull() ?: return@setPositiveButton
                onOk(v.coerceIn(min, max))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

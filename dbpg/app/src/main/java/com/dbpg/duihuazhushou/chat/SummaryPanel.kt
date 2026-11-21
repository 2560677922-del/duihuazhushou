package com.dbpg.duihuazhushou.chat

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.settings.ApiConfigActivity
import com.dbpg.duihuazhushou.settings.EngineBridge
import org.json.JSONArray

/**
 * 「前情提要」面板：私聊与群聊共用。
 *
 * 面板管三件事，全部落在这个对话自己的绑定上：
 *   1. 摘要用哪个模型（默认跟随对话，选了就固定）；
 *   2. 摘要替换第几条到第几条（可直接改）；
 *   3. 摘要正文（可直接改）。
 *
 * ★ 原文永远不删：这里改的只是"发给模型时用哪段提要替代哪几条原文"，
 *   本地完整对话一直保留，用户随时能回看。
 *
 * ★ 不做二次确认之外的花活：字段越界在这里夹正，真正的落盘与锚点
 *   重算交给 [ChatController.updateSummary]，面板只负责取值。
 */
object SummaryPanel {

    /**
     * @param runConfirm 非空时点「立即浓缩」先弹确认（群聊成员频道用：
     *        明确只压当前频道，不动公共区）。
     */
    fun show(activity: Activity, c: ChatController, runConfirm: String? = null) {
        val total = c.doc?.actions?.size ?: 0
        val blocks = c.summaries()
        val block = blocks.firstOrNull()

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 4), dp(activity, 20), 0)
        }

        val tip = TextView(activity).apply {
            setText(
                when {
                    block != null ->
                        "这段提要替代第 ${block.fromIndex + 1} 到第 ${block.toIndex + 1} 条原文" +
                            "（共 $total 条）。可以直接改正文和范围。"
                    total == 0 -> "这个对话还没有消息可压缩。"
                    else ->
                        "把早期对话压成一段提要，发消息时替代原文，长会话才跑得动。" +
                            "点「立即浓缩」压最早的一批；也可以在设置里开自动摘要。"
                },
            )
            setTextSize(12f)
            setTextColor(0xFF66707F.toInt())
            setLineSpacing(dp(activity, 2).toFloat(), 1f)
        }

        // ---- 摘要模型 ----------------------------------------------------

        val modelValue = TextView(activity).apply {
            setTextSize(13f)
            setTextColor(0xFF2F66D8.toInt())
            gravity = Gravity.END
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 模型名可能很长（带日期后缀），别把左边的「摘要模型」挤掉
            maxWidth = (activity.resources.displayMetrics.widthPixels * 0.62f).toInt()
        }
        fun refreshModel() {
            val uid = c.summaryModelId()
            modelValue.text = if (uid.isEmpty()) {
                "跟随对话（${c.chatModelName().ifBlank { "未配置" }}） ›"
            } else {
                "${c.summaryModelLabel().ifBlank { "已选模型" }} ›"
            }
        }
        refreshModel()

        val modelRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 12), 0, dp(activity, 4))
            addView(
                TextView(activity).apply {
                    setText("摘要模型")
                    setTextSize(13f)
                    setTextColor(0xFF66707F.toInt())
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(modelValue)
            setOnClickListener { pickModel(activity, c) { refreshModel() } }
        }

        // ---- 替换范围 ----------------------------------------------------

        val fromInput = rangeInput(activity, (block?.fromIndex ?: 0) + 1)
        val toInput = rangeInput(activity, (block?.toIndex ?: -1) + 1)
        fromInput.isEnabled = block != null
        toInput.isEnabled = block != null

        val rangeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 6), 0, 0)
            addView(
                TextView(activity).apply {
                    setText("替换范围")
                    setTextSize(13f)
                    setTextColor(0xFF66707F.toInt())
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(TextView(activity).apply {
                setText("第")
                setTextSize(13f)
                setTextColor(0xFF66707F.toInt())
            })
            addView(fromInput)
            addView(TextView(activity).apply {
                setText("条 到 第")
                setTextSize(13f)
                setTextColor(0xFF66707F.toInt())
            })
            addView(toInput)
            addView(TextView(activity).apply {
                setText("条")
                setTextSize(13f)
                setTextColor(0xFF66707F.toInt())
            })
        }

        val rangeHint = TextView(activity).apply {
            setText(if (block != null) "第 1 条是最早的一轮，共 $total 条" else "还没有摘要，先浓缩一次再调范围")
            setTextSize(11f)
            setTextColor(0xFF9AA3B0.toInt())
            setPadding(0, dp(activity, 2), 0, 0)
        }

        // ---- 正文 --------------------------------------------------------

        val input = EditText(activity).apply {
            setText(blocks.joinToString("\n") { it.text.trim() })
            hint = "还没有摘要，点下面「立即浓缩」生成"
            setTextSize(14f)
            setTextColor(0xFF1A2230.toInt())
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            gravity = Gravity.TOP
            minLines = 6
            maxLines = 12
        }

        val runBtn = TextView(activity).apply {
            setText("立即浓缩")
            setTextSize(13f)
            setTextColor(0xFF2F66D8.toInt())
            background = activity.getDrawable(R.drawable.bg_btn_soft)
            setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8))
        }
        val clearBtn = TextView(activity).apply {
            setText("清除摘要")
            setTextSize(13f)
            setTextColor(0xFFA33A3A.toInt())
            background = activity.getDrawable(R.drawable.bg_btn_soft)
            setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8))
        }
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(activity, 10), 0, 0)
            addView(runBtn)
            addView(
                clearBtn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = dp(activity, 8) },
            )
        }

        box.addView(tip)
        box.addView(modelRow)
        box.addView(rangeRow)
        box.addView(rangeHint)
        box.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(activity, 170),
            ).apply { topMargin = dp(activity, 8) },
        )
        box.addView(btnRow)

        val dlg = AlertDialog.Builder(activity)
            .setTitle("前情提要")
            .setView(box)
            .setPositiveButton("保存", null)
            .setNegativeButton("关闭", null)
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (block == null) {
                    toast(activity, "还没有摘要，先点「立即浓缩」")
                    return@setOnClickListener
                }
                val text = input.text?.toString().orEmpty().trim()
                if (text.isEmpty()) {
                    toast(activity, "摘要正文不能为空；要删除请点「清除摘要」")
                    return@setOnClickListener
                }
                val a = fromInput.text?.toString()?.toIntOrNull()
                val b = toInput.text?.toString()?.toIntOrNull()
                if (a == null || b == null) {
                    toast(activity, "替换范围填完整")
                    return@setOnClickListener
                }
                val from = minOf(a, b).coerceIn(1, total)
                val to = maxOf(a, b).coerceIn(1, total)
                c.updateSummary(text, from - 1, to - 1)
                toast(activity, "已更新：替换第 $from–$to 条")
                dlg.dismiss()
            }
        }

        runBtn.setOnClickListener {
            dlg.dismiss()
            if (runConfirm != null) {
                AlertDialog.Builder(activity)
                    .setTitle("压缩这页的历史")
                    .setMessage(runConfirm)
                    .setPositiveButton("开始") { _, _ -> c.summarizeNow() }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                c.summarizeNow()
            }
        }
        clearBtn.setOnClickListener {
            if (blocks.isEmpty()) {
                toast(activity, "还没有摘要")
                return@setOnClickListener
            }
            AlertDialog.Builder(activity)
                .setTitle("清除摘要")
                .setMessage("清除后会把全文发给模型（原文一直没删，随时可以再压一次）。")
                .setPositiveButton("清除") { _, _ ->
                    c.clearSummaries()
                    dlg.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        dlg.show()
    }

    // ------------------------------------------------------------ 模型选择

    /**
     * 摘要模型列表：第一项固定「跟随对话」，其余按运营商 × 模型平铺。
     *
     * 不做两级弹窗：模型通常十几项，一屏能扫完；两级反而要多点一次。
     */
    private fun pickModel(activity: Activity, c: ChatController, onPicked: () -> Unit) {
        EngineBridge.fetch(activity, "getConfig") { obj ->
            if (activity.isFinishing || activity.isDestroyed) return@fetch
            if (obj == null) {
                toast(activity, "配置还没就绪，稍后再试")
                return@fetch
            }
            val providers = obj.optJSONArray("providers") ?: JSONArray()
            val labels = ArrayList<String>()
            val uids = ArrayList<String>()
            val names = ArrayList<String>()
            val bound = c.summaryModelId()
            val follow = "跟随对话（当前 ${c.chatModelName().ifBlank { "未配置" }}）"
            labels.add(if (bound.isEmpty()) "✓ $follow" else follow)

            for (i in 0 until providers.length()) {
                val p = providers.optJSONObject(i) ?: continue
                val providerName = p.optString("note")
                    .ifBlank { p.optString("baseUrl") }
                    .ifBlank { "运营商 ${i + 1}" }
                val models = p.optJSONArray("models") ?: continue
                for (j in 0 until models.length()) {
                    val m = models.optJSONObject(j) ?: continue
                    val uid = m.optString("uid")
                    if (uid.isBlank()) continue
                    val modelName = m.optString("model")
                        .ifBlank { m.optString("name") }
                        .ifBlank { "未命名" }
                    val label = "$providerName · $modelName"
                    labels.add(if (uid == bound) "✓ $label" else label)
                    uids.add(uid)
                    names.add(label)
                }
            }

            if (uids.isEmpty()) {
                AlertDialog.Builder(activity)
                    .setMessage("还没有可用的对话模型。先到「API 配置」添加运营商和模型。")
                    .setPositiveButton("去配置") { _, _ ->
                        activity.startActivity(Intent(activity, ApiConfigActivity::class.java))
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@fetch
            }

            AlertDialog.Builder(activity)
                .setTitle("摘要用哪个模型")
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which == 0) {
                        c.setSummaryModel("", "")
                    } else {
                        val k = which - 1
                        c.setSummaryModel(uids[k], names[k])
                    }
                    toast(activity, if (which == 0) "摘要跟随对话模型" else "摘要模型已固定")
                    onPicked()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ------------------------------------------------------------ 小工具

    private fun rangeInput(activity: Activity, value: Int): EditText =
        EditText(activity).apply {
            setText(value.coerceAtLeast(1).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextSize(13f)
            setTextColor(0xFF1A2230.toInt())
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4))
            layoutParams = LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 34)).apply {
                leftMargin = dp(activity, 4)
                rightMargin = dp(activity, 4)
            }
        }

    private fun toast(activity: Activity, msg: String) =
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    private fun dp(activity: Activity, v: Int): Int =
        Math.round(v * activity.resources.displayMetrics.density)
}

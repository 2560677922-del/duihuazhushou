package com.dbpg.duihuazhushou.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.dbpg.duihuazhushou.Insets
import com.dbpg.duihuazhushou.R
import com.dbpg.duihuazhushou.appearance.AppearanceConfig
import com.dbpg.duihuazhushou.chat.render.HtmlMessageView
import com.dbpg.duihuazhushou.chat.render.plugin.PLUGIN_API_VERSION
import com.dbpg.duihuazhushou.chat.render.plugin.PluginCatalog
import com.dbpg.duihuazhushou.chat.render.plugin.PluginDescriptor
import com.dbpg.duihuazhushou.chat.render.plugin.PluginRegistry
import com.dbpg.duihuazhushou.chat.render.plugin.PluginStore

/**
 * 渲染插件设置页。
 *
 * ★ 语义（用户明确要求）：插件**随 APK 内置在插件库**里，这里只提供
 *   「安装 / 卸载」运行时挂载 + 「启用 / 禁用」。**没有删除按钮** ——
 *   卸载只是把插件从运行时移出，它仍留在库里，随时能再装回来。
 *   因为目前没有云端插件库、也没有发布页，先内置。
 *
 * ★ 目的：把「预设怎么写前端界面」这件事外置。以后预设换了写法、
 *   或换了支持的社区插件，卸载旧的、内置个新的顶上即可，主 App 不用改。
 *
 * 整页用代码构建，不新增 layout XML，也不动现有设置页的结构。
 */
class PluginSettingsActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private lateinit var arbText: TextView
    private lateinit var diagText: TextView
    private lateinit var previewHost: LinearLayout
    private var previewWeb: HtmlMessageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F4F6FA"))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(28))
        }
        scroll.addView(
            root,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        setContentView(scroll)
        Insets.applyTop(this)

        // 顶栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        bar.addView(TextView(this).apply {
            text = "‹ 返回"
            textSize = 16f
            setTextColor(Color.parseColor("#2F66D8"))
            setPadding(dp(4), dp(6), dp(16), dp(6))
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "渲染插件"
            textSize = 18f
            setTextColor(Color.parseColor("#1A2230"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(bar, matchWidth())

        root.addView(
            infoCard(
                "插件随 App 内置在「插件库」里，可以**随时安装 / 卸载**，" +
                    "但**不能删除**——卸载只是从运行时移出，插件仍在库里，随时能装回来。\n\n" +
                    "它们负责把预设的前端界面（折叠窗 / 状态栏 / 选项面板）渲染出来：" +
                    "既把预设吐出的 HTML 卡片整理成可渲染的隔离文档，" +
                    "也能在模型把面板内容写成散文时，按预设模板补出折叠窗。" +
                    "不改请求体、不改正则引擎、不动主渲染管线。",
            ),
        )

        arbText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#5A6B85"))
            setPadding(dp(20), dp(6), dp(20), dp(6))
        }
        root.addView(arbText, matchWidth())

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox, matchWidth())

        // 渲染自检：把主 App 送进来的 rich 形态、插件认领结果直接摊开给用户看，
        // 再用内置模板走一遍**同一条气泡通道**，一眼分清「插件没生效」还是「气泡没渲染」。
        root.addView(diagnosticCard(), cardParams())

        root.addView(
            infoCard(
                "以后预设换了写法怎么办：卸载旧插件、在插件库里内置一条新约定即可，主渲染管线一行都不用改。\n\n" +
                    "插件只在遇到自己负责的写法时介入；标准 ` ```html ` 整页文档内置本来就处理得好，插件会放行（看起来没变化是正确的）。\n\n" +
                    "同时装多个时只有优先级最高的生效，不会出现社区那种「两个渲染都开了 → 重复 / 闪烁」。\n\n" +
                    "小提示：预设里若启用了「行动选项2」这类会删除 <options> 的显示正则，" +
                    "选项可能先被吃掉；「面板数据适配器」会在渲染层把散文选项补回来，" +
                    "也可以在预设里关掉那条正则。",
            ),
        )

        refresh()
    }

    override fun onResume() {
        super.onResume()
        PluginStore.invalidate()
        PluginRegistry.onPluginsChanged(this)
        if (::listBox.isInitialized) refresh()
    }

    override fun onDestroy() {
        // 设置页自己造的 WebView 自己销毁，不带累聊天页那一批
        previewWeb?.destroy()
        previewWeb = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 刷新

    private fun refresh() {
        listBox.removeAllViews()
        val installed = PluginStore.installed(this)
        val enabled = PluginStore.enabled(this)
        for (plugin in PluginCatalog.ALL) {
            listBox.addView(
                pluginCard(plugin.descriptor, plugin.descriptor.id in installed, plugin.descriptor.id in enabled),
                cardParams(),
            )
        }
        val candidates = PluginRegistry.activeCandidates(this, "")
        arbText.text = if (candidates.isEmpty()) {
            "当前没有生效的插件 → 内置渲染按原样工作（零改动）"
        } else {
            "仲裁顺序：" + candidates.joinToString(" → ") { it.name } + "（同时认领时只有第一个生效）"
        }
        refreshDiagnostic()
    }

    /** 主 App 送进来的 rich 是「原样 \n」还是「<br> HTML」、插件认没认领，全摊开。 */
    private fun refreshDiagnostic() {
        if (!::diagText.isInitialized) return
        val d = PluginRegistry.lastDiagnostic
        diagText.text = if (d == null) {
            "还没有渲染记录。回聊天生成 / 重新生成一条消息，再回这里点「刷新诊断」即可。"
        } else {
            buildString {
                append("输入形态：")
                    .append(if (d.htmlified) "HTML 化（含 <br>，已按行归一化）" else "原样换行文本")
                    .append(" · rich 长度 ").append(d.richLength).append(" 字符\n")
                append("插件认领：").append(
                    if (d.claims.isEmpty()) "（无可用插件）"
                    else d.claims.joinToString("，") { "${it.first}=${if (it.second) "是" else "否"}" },
                ).append('\n')
                append("生效插件：").append(d.winner ?: "无（全部放行）")
                    .append(" · 产出 ").append(d.outputLength?.toString() ?: "0").append(" 字符\n")
                append("结果：").append(d.note).append('\n')
                if (d.probe.isNotBlank()) append("结构探针：").append(d.probe).append('\n')
                append("rich 开头：").append(d.richHead)
            }
        }
    }

    /** 把整条 rich 复制到剪贴板：诊断里截断的 160 字符之外，一眼能看全。 */
    private fun copyRich() {
        val rich = PluginRegistry.lastRich
        if (rich == null) {
            diagText.text = "还没有渲染记录，先回聊天生成一条再回来。"
            return
        }
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("rich", rich))
        Toast.makeText(this, "已复制 ${rich.length} 字符，可直接粘贴给我", Toast.LENGTH_SHORT).show()
    }

    /**
     * 自检渲染：直接用 APK 内置的预设模板 + 固定标签，走和聊天气泡完全相同的
     * HtmlMessageView / render.html / iframe srcdoc 通道。能看到 01/02/03 三个
     * 选项就说明气泡层没问题，问题在插件输入或认领；看不到就是气泡层坏了。
     */
    private fun togglePreview() {
        if (previewWeb != null) {
            previewHost.removeAllViews()
            previewWeb?.destroy()
            previewWeb = null
            previewHost.visibility = View.GONE
            return
        }
        val tmpl = runCatching {
            assets.open("plugins/meowssiah_panel.html").bufferedReader().use { it.readText() }
        }.getOrNull()
        if (tmpl == null || !tmpl.contains("__DHP_PANEL_DATA__")) {
            diagText.text = "自检失败：读不到内置模板 assets/plugins/meowssiah_panel.html"
            return
        }
        val tags =
            "<summary>自检：这张卡片由 APK 内置的预设模板渲染，和聊天气泡走同一条 WebView / iframe 通道。</summary>\n" +
                "<comment>能看到下面 01 / 02 / 03 三个选项，就说明气泡层没问题；看不到请把上面的诊断截图发我。</comment>\n" +
                "<options>\n" +
                "<option>选项一：插件没认领，看诊断里的「插件认领」</option>\n" +
                "<option>选项二：输入形态不对，看诊断里的「输入形态」</option>\n" +
                "<option>选项三：气泡层坏了，卸载插件回退</option>\n" +
                "</options>"
        val doc = tmpl.replace("__DHP_PANEL_DATA__", tags)
        val html = "```html\n" + doc + (if (doc.endsWith("\n")) "" else "\n") + "```"
        val web = HtmlMessageView(this)
        previewWeb = web
        previewHost.addView(
            web,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(520)),
        )
        web.heightListener = HtmlMessageView.HeightListener { px ->
            val lp = web.layoutParams ?: return@HeightListener
            if (lp.height != px) {
                lp.height = px
                web.layoutParams = lp
            }
        }
        web.bind(html, HtmlMessageView.cssOf(AppearanceConfig()))
        previewHost.visibility = View.VISIBLE
    }

    private fun diagnosticCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(TextView(this).apply {
            text = "渲染自检 · 最近一条消息"
            textSize = 15f
            setTextColor(Color.parseColor("#1A2230"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        diagText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#5A6B85"))
            setLineSpacing(dp(2).toFloat(), 1f)
            val lp = matchWidth()
            lp.topMargin = dp(8)
            layoutParams = lp
        }
        card.addView(diagText)
        val acts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        acts.addView(actionButton("用内置模板自检渲染") { togglePreview() })
        acts.addView(actionButton("复制完整 rich") { copyRich() })
        acts.addView(actionButton("刷新诊断") { refreshDiagnostic() })
        card.addView(acts, matchWidth())
        previewHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(previewHost, matchWidth())
        return card
    }

    private fun changed(block: () -> Unit) {
        block()
        PluginRegistry.onPluginsChanged(this)
        refresh()
    }

    // ------------------------------------------------------------ 卡片

    private fun pluginCard(d: PluginDescriptor, installed: Boolean, enabled: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = d.name
            textSize = 15f
            setTextColor(Color.parseColor("#1A2230"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(this).apply {
            text = when {
                !installed -> "未安装"
                enabled -> "已启用"
                else -> "已停用"
            }
            textSize = 11f
            setTextColor(
                Color.parseColor(
                    when {
                        !installed -> "#8A94A6"
                        enabled -> "#0F8A5F"
                        else -> "#B8860B"
                    },
                ),
            )
            setBackgroundResource(R.drawable.bg_btn_soft)
            setPadding(dp(9), dp(3), dp(9), dp(3))
        })
        card.addView(top, matchWidth())

        // 这几个 TextView 自带带 margin 的 LayoutParams，addView 不能再传一份覆盖掉
        card.addView(textView(d.convention, 12.5f, "#3A4353", dp(6)))
        card.addView(textView(d.description, 12f, "#66707F", dp(6)))
        card.addView(
            textView(
                "版本 ${d.version} · 契约 v${d.apiVersion}（当前 v$PLUGIN_API_VERSION）· " +
                    "优先级 ${d.priority} · 范围 ${d.scope.label} · 内置不可删除",
                11f, "#8A94A6", dp(8),
            ),
        )

        // 按钮行：只有 安装/卸载 + 启用/禁用，**没有删除**
        val acts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }
        if (!installed) {
            acts.addView(actionButton("安装") {
                changed { PluginStore.install(this, d.id) }
            })
        } else {
            acts.addView(actionButton(if (enabled) "禁用" else "启用") {
                changed { PluginStore.setEnabled(this, d.id, !enabled) }
            })
            acts.addView(actionButton("卸载", danger = true) {
                changed { PluginStore.uninstall(this, d.id) }
            })
        }
        card.addView(acts, matchWidth())
        return card
    }

    private fun actionButton(label: String, danger: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.parseColor(if (danger) "#C0392B" else "#2F66D8"))
            setBackgroundResource(R.drawable.bg_btn_soft)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginStart = dp(8)
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    // ------------------------------------------------------------ 小工具

    private fun infoCard(text: String): TextView = TextView(this).apply {
        this.text = text.replace("**", "")
        textSize = 12f
        setTextColor(Color.parseColor("#66707F"))
        setLineSpacing(dp(3).toFloat(), 1f)
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = cardParams()
    }

    private fun textView(text: String, size: Float, color: String, topMargin: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
            setLineSpacing(dp(2).toFloat(), 1f)
            val lp = matchWidth()
            lp.topMargin = topMargin
            layoutParams = lp
        }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun cardParams(): LinearLayout.LayoutParams {
        val lp = matchWidth()
        lp.setMargins(dp(12), dp(8), dp(12), 0)
        return lp
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

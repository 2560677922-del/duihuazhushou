package com.dbpg.duihuazhushou.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.dbpg.duihuazhushou.chat.render.plugin.PluginKind
import com.dbpg.duihuazhushou.chat.render.plugin.PluginRegistry
import com.dbpg.duihuazhushou.chat.render.plugin.PluginStore

/**
 * 渲染插件设置页。
 *
 * ★ 信息层级（本次重做）：首屏只回答三个问题 ——
 *     ① 现在生效了吗（状态概览 + 卡片状态 chip）
 *     ② 它是干嘛的（一句话 summary）
 *     ③ 我该按哪个按钮（启用 / 停用 / 安装 的主按钮）
 *   兼容说明、认领写法、契约细节、渲染自检全部收进默认折叠的区块。
 *   之前整页 600+ 字铺开，重点被淹没，改完首屏一屏能看完。
 *
 * ★ 双清单语义：插件本体在 [PluginCatalog.BUILTIN_IDS] 出厂清单里（随 APK
 *   编译、恒在、不可删除）；[PluginStore] 只存「已挂载 / 已启用」两份状态。
 *   「卸载」= 移出运行时，随时能单个装回或「恢复出厂」一键装回 —— 测试期
 *   没有云端库 / 发布页，这条兜底保证怎么点都下得回来，也回应了「卸载了
 *   就真没了」的顾虑。
 *
 * ★ 插件边界：模仿社区「小白X / 酒馆助手」那层前端渲染约定（很多预设依赖
 *   它们），但只把预设吐出的 HTML 卡片补成可渲染的隔离文档，做成插件是为了
 *   **不碰主线路**：不改请求体、不改正则引擎、不动主渲染管线，装上才生效、
 *   卸载立即回退。
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

        root.addView(topBar(), matchWidth())

        // 一行提示：把「内置 / 卸载 / 恢复」的语义说清，不占大卡片
        root.addView(
            hintLine("内置插件随 App 出厂 · 卸载只是移出，随时可装回；「恢复出厂」一键装回全部"),
        )

        arbText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#1A2230"))
            typeface = Typeface.DEFAULT_BOLD
            val lp = matchWidth()
            lp.setMargins(dp(16), dp(10), dp(16), dp(2))
            layoutParams = lp
        }
        root.addView(arbText)

        // 重点区：插件卡（状态 + 一句话作用 + 主操作）
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox, matchWidth())

        // 折叠 ①：兼容说明 / 契约细节（默认收起）
        root.addView(
            sectionCard("插件详情 · 兼容说明") { body ->
                body.addView(detailsBody(), matchWidth())
            },
            cardParams(),
        )

        // 折叠 ②：渲染自检 / 排错（默认收起）
        root.addView(
            sectionCard("渲染自检 · 最近一条消息") { body ->
                body.addView(diagnosticBody(), matchWidth())
            },
            cardParams(),
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

    // ------------------------------------------------------------ 顶栏 / 提示

    private fun topBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(TextView(this@PluginSettingsActivity).apply {
            text = "‹ 返回"
            textSize = 16f
            setTextColor(Color.parseColor("#2F66D8"))
            setPadding(dp(4), dp(6), dp(16), dp(6))
            setOnClickListener { finish() }
        })
        addView(TextView(this@PluginSettingsActivity).apply {
            text = "插件"
            textSize = 18f
            setTextColor(Color.parseColor("#1A2230"))
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@PluginSettingsActivity).apply {
            text = "恢复出厂"
            textSize = 13f
            setTextColor(Color.parseColor("#2F66D8"))
            setBackgroundResource(R.drawable.bg_btn_soft)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener {
                changed { PluginStore.restoreBuiltins(this@PluginSettingsActivity) }
                Toast.makeText(this@PluginSettingsActivity, "内置插件已恢复并启用", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun hintLine(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(Color.parseColor("#8A94A6"))
        val lp = matchWidth()
        lp.setMargins(dp(16), dp(2), dp(16), 0)
        layoutParams = lp
    }

    // ------------------------------------------------------------ 刷新

    private fun refresh() {
        listBox.removeAllViews()
        val installed = PluginStore.installed(this)
        val enabled = PluginStore.enabled(this)

        // 两类插件共用同一份「已挂载 / 已启用」状态，但作用点完全不同：
        // 渲染插件管消息怎么显示，生图插件管出图带什么参数。分组展示，
        // 免得用户以为「卸载生图预设」也会影响卡片渲染。
        listBox.addView(groupTitle("渲染 · 聊天消息"), cardParams())
        for (plugin in PluginCatalog.ALL) {
            listBox.addView(
                pluginCard(plugin.descriptor, plugin.descriptor.id in installed, plugin.descriptor.id in enabled),
                cardParams(),
            )
        }

        /*
         * 「生图 · 提示词预设」整组已下架（[PromptPresetPlugin.SEALED]）。
         * 出厂清单为空时这里连标题都不出现 —— 空标题比"点了没反应"更让人困惑。
         */
        val imagePlugins = com.dbpg.duihuazhushou.imagegen.PromptPresetCatalog.ALL
        if (imagePlugins.isNotEmpty()) {
            listBox.addView(groupTitle("生图 · 提示词预设"), cardParams())
            for (d in imagePlugins) {
                listBox.addView(pluginCard(d, d.id in installed, d.id in enabled), cardParams())
            }
        }

        // 仲裁信息只对渲染插件有意义（一条消息最多一个渲染器生效）
        val candidates = PluginRegistry.activeCandidates(this, "")
        arbText.text = if (candidates.isEmpty()) {
            "当前无生效插件 → 内置渲染按原样工作"
        } else {
            "已生效：" + candidates.joinToString(" → ") { it.name }
        }
        refreshDiagnostic()
    }

    /** 分组小标题：两类插件之间只隔这一行，不占卡片。 */
    private fun groupTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(Color.parseColor("#8A94A6"))
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(18), dp(14), dp(18), dp(0))
    }

    /** 折叠区里的说明：为什么存在、认领什么、契约细节。默认收起，不抢首屏。 */
    private fun detailsBody(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val d = PluginCatalog.ALL.firstOrNull()?.descriptor
        if (d != null) {
            box.addView(sectionLabel("它解决什么"))
            box.addView(bodyText(
                "很多预设的前端界面是按社区插件「小白X / 酒馆助手」的写法做的。" +
                    "这里补上那层兼容：把预设吐出的 HTML 卡片（整页 / 片段 / 非标准围栏）" +
                    "整理成能渲染的隔离文档，交给 iframe 沙箱。\n\n" +
                    "做成插件而不是改主线路，就是为了互不影响：不改请求体、不改正则引擎、" +
                    "不动正文；装上才生效，卸载立即回退，主渲染管线一行都不用改。",
            ))
            box.addView(sectionLabel("认领写法"))
            box.addView(bodyText(d.convention))
            box.addView(sectionLabel("详细说明"))
            box.addView(bodyText(d.description))
            box.addView(sectionLabel("契约信息"))
            box.addView(bodyText(
                "版本 ${d.version} · 契约 v${d.apiVersion}（当前 v$PLUGIN_API_VERSION）· " +
                    "优先级 ${d.priority} · 范围 ${d.scope.label} · ${d.source.label}不可删除",
            ))
        }
        box.addView(sectionLabel("小提示"))
        box.addView(bodyText(
            "标准 ```html 整页文档内置本来就处理得好，插件会放行（看起来没变化是正确的）。" +
                "预设里若启用了「行动选项2」这类会删除 <options> 的显示正则，选项可能先被吃掉，" +
                "可以在预设里关掉那条正则。",
        ))
        return box
    }

    /** 默认折叠的区块：点标题展开 / 收起，箭头跟着变。 */
    private fun sectionCard(title: String, build: (LinearLayout) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
        }
        val header = TextView(this).apply {
            text = "▸  $title"
            textSize = 14f
            setTextColor(Color.parseColor("#1A2230"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(16), 0, dp(16), dp(14))
        }
        build(body)
        header.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            header.text = (if (open) "▾  " else "▸  ") + title
        }
        box.addView(header, matchWidth())
        box.addView(body, matchWidth())
        return box
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#1A2230"))
        typeface = Typeface.DEFAULT_BOLD
        val lp = matchWidth()
        lp.topMargin = dp(12)
        layoutParams = lp
    }

    private fun bodyText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#66707F"))
        setLineSpacing(dp(2).toFloat(), 1f)
        val lp = matchWidth()
        lp.topMargin = dp(4)
        layoutParams = lp
    }

    /** 主 App 送进来的 rich 是「原样 \n」还是「<br> HTML」、插件认没认领，全摊开。 */
    private fun refreshDiagnostic() {
        if (!::diagText.isInitialized) return
        val d = PluginRegistry.lastDiagnostic
        diagText.text = if (d == null) {
            "还没有渲染记录。回聊天生成 / 重新生成一条消息，再点「刷新诊断」即可。"
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
     * 自检渲染：用一段**自带脚本的内联卡片**，走和聊天气泡完全相同的
     * HtmlMessageView / render.html / iframe srcdoc 通道。
     *
     * ★ 不依赖任何预设模板 —— 插件只负责渲染卡片，长什么样是预设的事。
     *   能看到卡片、按钮点了会变绿、高度自适应，就说明气泡层整条链路正常。
     */
    private fun togglePreview() {
        if (previewWeb != null) {
            previewHost.removeAllViews()
            previewWeb?.destroy()
            previewWeb = null
            previewHost.visibility = View.GONE
            return
        }
        val doc = """
<!DOCTYPE html><html><head><meta charset="utf-8">
<style>
  body{margin:0;padding:8px;font-family:sans-serif;background:transparent}
  .card{border-radius:18px;padding:16px;background:linear-gradient(145deg,#fff6f9,#ffe7ee);
        border:1px solid rgba(255,255,255,.8);box-shadow:0 6px 18px rgba(211,137,156,.22)}
  h1{margin:0 0 4px;font-size:19px;color:#b25a76}
  p{margin:0 0 10px;font-size:12px;color:#a07383;line-height:1.6}
  button{display:block;width:100%;margin:0 0 8px;padding:11px 12px;border-radius:12px;
         border:1px solid rgba(190,122,147,.22);background:rgba(255,255,255,.75);
         color:#8a5567;font-size:13px;text-align:left}
  button:last-child{margin-bottom:0}
  .ok{color:#2f7d5b;font-weight:700;border-color:rgba(85,160,125,.5)}
</style></head><body>
<div class="card">
  <h1>自检卡片</h1>
  <p id="hint">能看到这张卡片和下面三个按钮，就说明气泡层（WebView / iframe / 脚本 / 高度）全都正常；看不到就是气泡层坏了。</p>
  <button type="button">01 点我一下：能变绿说明卡片里的脚本在跑</button>
  <button type="button">02 这一条用来确认样式没被裁切</button>
  <button type="button">03 这一条用来确认高度回传正常</button>
</div>
<script>
  document.querySelectorAll('button').forEach(function (b) {
    b.addEventListener('click', function () {
      b.classList.add('ok');
      b.textContent = '✓ 脚本正常运行';
      document.getElementById('hint').textContent = '脚本、样式、高度都正常 —— 气泡层没问题。';
    });
  });
</script>
</body></html>
""".trimIndent()
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

    private fun diagnosticBody(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        diagText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#5A6B85"))
            setLineSpacing(dp(2).toFloat(), 1f)
            val lp = matchWidth()
            lp.topMargin = dp(4)
            layoutParams = lp
        }
        box.addView(diagText)
        val acts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        acts.addView(actionButton("渲染一张自检卡片") { togglePreview() })
        acts.addView(actionButton("复制完整 rich") { copyRich() })
        acts.addView(actionButton("刷新诊断") { refreshDiagnostic() })
        box.addView(acts, matchWidth())
        previewHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        box.addView(previewHost, matchWidth())
        return box
    }

    private fun changed(block: () -> Unit) {
        block()
        PluginRegistry.onPluginsChanged(this)
        refresh()
    }

    // ------------------------------------------------------------ 插件卡（重点）

    private fun pluginCard(d: PluginDescriptor, installed: Boolean, enabled: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        // 第一行：名称 + 状态 chip
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = d.name
            textSize = 16f
            setTextColor(Color.parseColor("#1A2230"))
            typeface = Typeface.DEFAULT_BOLD
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

        // 第二行：一句话作用（首屏的重点）
        card.addView(textView(d.summary.ifBlank { d.convention }, 13f, "#3A4353", dp(10)))

        // 第三行：来源 + 内置标记（小字，一眼知道它为什么存在）
        // 两类插件的来源说明不一样：渲染插件是兼容社区写法，生图插件是本机支线能力。
        card.addView(
            textView(
                if (d.kind == PluginKind.IMAGE_GEN) {
                    "生图支线 · ${d.source.label}不可删除 · 契约 v${d.apiVersion}"
                } else {
                    "兼容 小白X / 酒馆助手 写法 · ${d.source.label}不可删除 · 契约 v${d.apiVersion}"
                },
                11f, "#8A94A6", dp(6),
            ),
        )

        // 第四行：主操作（醒目）+ 次操作
        val acts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        if (!installed) {
            acts.addView(primaryButton("安装并启用", accent = true) {
                changed { PluginStore.install(this, d.id) }
            })
        } else {
            acts.addView(primaryButton(if (enabled) "停用" else "启用", accent = !enabled) {
                changed { PluginStore.setEnabled(this, d.id, !enabled) }
            })
            acts.addView(actionButton("卸载", danger = true) {
                changed { PluginStore.uninstall(this, d.id) }
            })
        }
        card.addView(acts, matchWidth())
        return card
    }

    /** 主按钮：填充色块 + 白字，视觉上压过次要操作。 */
    private fun primaryButton(label: String, accent: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor(if (accent) "#2F66D8" else "#8A94A6"))
            }
            setPadding(dp(18), dp(9), dp(18), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { onClick() }
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

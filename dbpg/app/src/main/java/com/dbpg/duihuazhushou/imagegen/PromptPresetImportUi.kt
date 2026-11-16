package com.dbpg.duihuazhushou.imagegen

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream

/**
 * 导入提示词预设的交互：选文件 / 粘贴 → 解析 → **先落库** → 再谈界面。
 *
 * ## 为什么"先落库"
 *
 * v1.7.0 的写法是：选完文件 → 回调回到页面 → 弹"勾选段落" → 用户点导入才写盘。
 * 真机上这一步经常走不完 —— `ACTION_OPEN_DOCUMENT` 拉起系统选择器时，中低端机
 * 会把调用方 Activity 回收；回来时要么重建、要么直接回到上一页，内存里那个
 * 回调自然就没了。表现就是"选完文件弹回设置页、预设没进来"。
 *
 * 现在改成：`onActivityResult` 里读文件 → 解析 → **立刻按默认勾选写盘** →
 * 用 Toast 报结果。写盘只依赖 ContentResolver，跟页面还活着没有关系。
 * "调整勾选"降级成事后可选的对话框：点它才重算，不点就用默认那套。
 *
 * 顺带修了两处：
 *
 * - 不再用静态字段存"选完文件后做什么"（那会持有已经销毁的 Activity）；
 * - 勾选列表的 ScrollView 给了确定高度 —— 原来用 `weight=1` 挂在
 *   `wrap_content` 的自定义 view 里，高度算出来是 0，列表整个看不见。
 */
object PromptPresetImportUi {

    private const val REQ_PICK_FILE = 0x9A71
    private const val MAX_FILE = 8 * 1024 * 1024

    /**
     * 选完文件后要通知谁。
     *
     * 只在这里暂存一次、用完立刻清空 —— 它捕获的是调用方（设置弹窗）的刷新动作，
     * 页面没了调用也只是白跑一趟（[refreshSafely] 里整段兜异常）。不用静态字段长期
     * 持有，就是为了避免 v1.7.0 那个"闭包指向已销毁 Activity"的坑。
     */
    @Volatile
    private var pending: (() -> Unit)? = null

    fun start(activity: Activity, onImported: () -> Unit = {}) {
        pending = onImported
        val clip = clipboard(activity)
        val labels = ArrayList<String>(3)
        val actions = ArrayList<() -> Unit>(3)

        labels.add("📄 从文件选择（.json / .txt）")
        actions.add { pickFile(activity) }

        /*
         * 剪贴板路径放在最前面（有内容时）：它**不离开 App**。
         * 文件选择器在部分机型上会把调用方回收，回来时页面没了、导入也就断了；
         * 这条路径没有这个风险，点一下立刻落库。
         */
        if (clip.isNotBlank()) {
            val head = clip.take(24).replace(Regex("[ \t\r\n]+"), " ")
            labels.add(0, "📋 用剪贴板导入（$head…）")
            actions.add(0) { importNow(activity, clip, onImported) }
        }

        labels.add("✍️ 手动粘贴 / 编辑文本")
        actions.add { paste(activity, onImported) }

        AlertDialog.Builder(activity)
            .setTitle("导入预设")
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 宿主在 `onActivityResult` 里转发；返回 true 表示这次结果归我处理。
     *
     * 这里会**同步完成落库**，所以即使调用方页面马上被销毁，预设也已经进去了。
     */
    fun onActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ): Boolean {
        if (requestCode != REQ_PICK_FILE) return false

        /*
         * 整段兜异常：这里跑在宿主的 onActivityResult 里，抛出去就是一次崩溃
         * （表现正好是"选完文件闪回上一页，预设没进来"）。宁可只吐一句 toast。
         */
        runCatching {
            /*
             * 不按 resultCode 提前返回：个别文件管理器（小米/OPPO 自带的那几个）
             * 选中后仍然回 RESULT_CANCELED，但 data 里带着 uri。只看 data 更实际。
             */
            val uri = data?.data
            if (uri == null) {
                if (resultCode == Activity.RESULT_OK) toast(activity, "没拿到文件，换「粘贴」试试")
                return@runCatching
            }
            val text = readText(activity, uri)
            if (text.isNullOrBlank()) {
                toast(activity, "文件是空的（或超过 8M）")
                return@runCatching
            }
            val cb = pending ?: {}
            pending = null
            importNow(activity, text) { runCatching { cb() } }
        }.onFailure {
            toast(activity, "导入出错：${it.message ?: it.javaClass.simpleName}")
        }
        return true
    }

    // ------------------------------------------------------------ 取内容

    /**
     * 起系统选择器。
     *
     * 用 `ACTION_GET_CONTENT` 而不是 `ACTION_OPEN_DOCUMENT`，并且**不限定 MIME**：
     * 前者几乎每个 ROM 都实现得规规矩矩；后者要持久授权，部分机型上会
     * "选中就退回、uri 为空"。`.json` 在不同文件管理器里的 MIME 也不统一
     * （`application/json` / `text/plain` / `application/octet-stream` 都有），
     * 一旦对不上，用户看到的就是"选完没反应"。
     */
    private fun pickFile(activity: Activity) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { activity.startActivityForResult(intent, REQ_PICK_FILE) }
            .onFailure { toast(activity, "打不开文件选择器：${it.message}") }
    }

    private fun readText(c: Context, uri: Uri): String? {
        val resolver = c.contentResolver
        val declared = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        if (declared != null && declared > MAX_FILE) return null

        return resolver.openInputStream(uri)?.use { ins ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_FILE) return null
                out.write(buf, 0, n)
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun paste(activity: Activity, onImported: () -> Unit) {
        val input = EditText(activity).apply {
            hint = "把预设 JSON 或一段破甲文本粘进来"
            textSize = 12.5f
            gravity = Gravity.TOP
            minLines = 9
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(clipboard(activity).take(400_000))
            setSelection(text.length)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), 0)
        }
        box.addView(input)
        box.addView(
            noteView(
                activity,
                "支持酒馆预设（有 prompts 数组）、{name, prompt} 小配置、或直接一段文本。" +
                    "也可以在「资产 → 导出当前」之后把导出的 JSON 粘这里。",
            ),
        )
        AlertDialog.Builder(activity)
            .setTitle("粘贴预设")
            .setView(box)
            .setPositiveButton("导入") { _, _ -> importNow(activity, input.text.toString(), onImported) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 解析 → 落库

    /**
     * 解析并**立即写入**预设库（默认勾选那套），然后提示结果。
     *
     * @return true = 已经落库
     */
    private fun importNow(activity: Activity, raw: String, onImported: () -> Unit): Boolean {
        if (raw.isBlank()) {
            toast(activity, "内容是空的")
            return false
        }
        val result = runCatching { PromptPresetImport.parse(raw) }.getOrElse {
            showError(activity, "解析出错：${it.message ?: it.javaClass.simpleName}")
            return false
        }
        val draft = when (result) {
            is PromptPresetImport.Result.Err -> {
                showError(activity, result.message)
                return false
            }

            is PromptPresetImport.Result.Ok -> result.draft
        }

        val preset = draft.build(draft.defaultPicked())
        if (preset == null) {
            showError(activity, "这份预设里没有可用段落")
            return false
        }

        // ★ 落库放在任何弹窗之前：页面随时可能被回收，数据必须先落地
        val uid = runCatching { PromptPresetStore.upsert(activity, preset) }
            .getOrElse {
                showError(activity, "保存失败：${it.message ?: "写盘出错"}")
                return false
            }

        onImported()
        toast(activity, "已导入「${preset.name}」：${preset.countAll} 段 / ${preset.all.length} 字")

        runCatching {
            AlertDialog.Builder(activity)
                .setTitle("已导入「${preset.name}」")
                .setMessage(
                    buildString {
                        append(preset.source.ifBlank { draft.format.label })
                        append("\n")
                        append("已勾 ${preset.countAll} 段 / ${preset.all.length} 字")
                        if (draft.rules.isNotEmpty()) {
                            append("；${draft.rules.count { it.on }} 条正则")
                        }
                        append("\n\n已设为当前预设，可以直接去出图了。")
                        append("段和正则都能在预设设置里逐条开关 —— 它只作用于「剧情 → 标签」那一步，")
                        append("画风、负面、采样、尺寸仍由「风格」决定。")
                    },
                )
                .setPositiveButton("知道了") { _, _ -> }
                .show()
        }
        return true
    }

    // ------------------------------------------------------------ 小工具

    private fun showError(activity: Activity, message: String) {
        runCatching {
            AlertDialog.Builder(activity)
                .setTitle("没导入")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show()
        }.onFailure { toast(activity, message) }
    }

    private fun noteView(activity: Activity, text: String) = TextView(activity).apply {
        this.text = text
        textSize = 10.5f
        setTextColor(0xFF8A94A6.toInt())
        setPadding(0, dp(activity, 3), 0, dp(activity, 6))
        setLineSpacing(dp(activity, 1).toFloat(), 1f)
    }

    private fun clipboard(c: Context): String = runCatching {
        val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(c)?.toString()
            .orEmpty()
    }.getOrDefault("")

    private fun dp(c: Context, v: Int): Int = Math.round(v * c.resources.displayMetrics.density)

    private fun toast(c: Context, msg: String) = Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()
}

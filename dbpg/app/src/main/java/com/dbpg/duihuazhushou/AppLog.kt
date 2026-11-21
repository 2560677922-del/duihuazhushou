package com.dbpg.duihuazhushou

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内运行日志。
 *
 * 为什么要有：真机出错时用户只能截到一闪而过的 toast，而异常堆栈、HTTP 状态码、
 * 模型原样回复全在 logcat 里 —— 用户拿不到，于是排查只能靠猜。设置页的
 * 「运行日志」把这些攒成一段文字，复制出来就能定位。
 *
 * 只做三件事：内存环形缓冲（界面看）、追加写文件（重启后还在）、每行带版本号。
 * 不接管 logcat，不装崩溃处理器（那是 [CrashLogger] 的事），也不碰 web 层。
 *
 * 每行都带版本号，是因为"你装的是不是最新包"这个问题反复出现：
 * 只复制其中一行也能看出包版本，不用再问。
 */
object AppLog {

    private const val FILE_NAME = "app-log.txt"

    /** 文件上限。超了就丢掉一半，日志是排查材料，不是资产，不需要无限留。 */
    private const val MAX_BYTES = 256 * 1024

    /** 内存里保留的行数（界面能翻到的最早位置）。 */
    private const val MAX_LINES = 400

    /** 堆栈只留前 N 行：真机栈一般 20~40 行，再长也是框架噪音。 */
    private const val STACK_LINES = 60

    private val mem = ArrayDeque<String>()

    /** null = 还没 install；写日志在 install 之前会被静默丢弃。 */
    private var dir: File? = null

    /** 每行前缀用短版本（`1.8.10`），避免每行都被 versionCode 挤占。 */
    private var ver: String = "?"

    /** 头部用完整版本（`1.8.10 (26)`），判断新旧直接看括号。 */
    private var verFull: String = "?"

    /** 机型/系统那一截，启动和清空时复用。 */
    private var deviceLine: String = ""

    private fun file(): File? = dir?.let { File(it, FILE_NAME) }

    /** 安装日志文件并写一行启动横幅。重复调用无副作用。 */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(mem) {
            if (dir != null) return
            dir = app.filesDir
            verFull = versionName(app)
            ver = verFull.substringBefore(" (")
            deviceLine =
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}" +
                    " (SDK ${Build.VERSION.SDK_INT})"
            appendLocked(banner())
        }
    }

    fun e(tag: String, msg: String) = write("E", tag, msg)

    /** 带异常的错误：消息 + 类名 + 堆栈前若干行。 */
    fun e(tag: String, msg: String, t: Throwable) {
        val head = "${t.javaClass.name}: ${t.message}"
        val stack = t.stackTraceToString().lineSequence().take(STACK_LINES).joinToString("\n")
        write("E", tag, "$msg\n$head\n$stack")
    }

    /**
     * 全部日志（不含版本头，头由页面拼）。
     *
     * 以**文件**为准而不是内存：用户往往是"崩了 → 重开 → 来看日志"，
     * 这时内存是空的，只有文件里还留着上次的记录。内存那份仅作兜底。
     */
    fun dump(): String = synchronized(mem) {
        val txt = runCatching { file()?.takeIf { it.exists() }?.readText() }
            .getOrNull()?.trimEnd()
        when {
            txt.isNullOrBlank() && mem.isEmpty() -> "（暂无日志）"
            txt.isNullOrBlank() -> mem.joinToString("\n")
            else -> {
                val lines = txt.lineSequence().toList()
                if (lines.size <= MAX_LINES) txt
                else "（更早的日志已省略，下面是最近 $MAX_LINES 行）\n" +
                    lines.takeLast(MAX_LINES).joinToString("\n")
            }
        }
    }

    /** 版本 + 机型，供页面头部与复制内容使用。 */
    fun header(ctx: Context): String {
        val v = if (verFull == "?") versionName(ctx.applicationContext) else verFull
        val d = deviceLine.takeIf { it.isNotEmpty() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        return "v$v · $d"
    }

    fun clear() {
        synchronized(mem) {
            mem.clear()
            runCatching { file()?.delete() }
            appendLocked(banner())
        }
    }

    /** 返回 `1.8.11 (26)`：括号里是 versionCode，判断新旧一看就知道。 */
    @Suppress("DEPRECATION")
    fun versionName(ctx: Context): String = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val name = info.versionName ?: "?"
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "$name ($code)"
    }.getOrDefault("?")

    // ------------------------------------------------------------ 内部

    private fun banner(): String =
        "===== ${time()} 启动 · v$verFull · $deviceLine ====="

    private fun time(): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun write(level: String, tag: String, msg: String) {
        val line = "${time()} $ver $level $tag $msg"
        synchronized(mem) {
            appendLocked(line)
        }
    }

    /**
     * 落内存 + 落文件。
     *
     * 文件写入是同步的：崩溃前最后一条日志必须已经在磁盘上，异步队列会丢。
     * 量很小（只有错误和几次桥失败），不值得为它引入线程池。
     */
    private fun appendLocked(line: String) {
        mem.addLast(line)
        while (mem.size > MAX_LINES) mem.removeFirst()
        runCatching {
            val f = file() ?: return
            if (f.length() > MAX_BYTES) {
                val tail = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText("（更早的日志已滚动清除）\n$tail")
            }
            f.appendText(line + "\n")
        }
    }
}

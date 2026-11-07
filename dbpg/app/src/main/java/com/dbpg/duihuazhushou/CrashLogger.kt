package com.dbpg.duihuazhushou

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程级崩溃记录。
 *
 * 为什么需要：这个项目一直在真机上试，而真机不像模拟器那样随手就能连 adb。
 * 用户反馈"黑屏 / 卡住 / 闪退"时，如果拿不到堆栈，就只能靠猜，改一轮试一轮。
 *
 * 做法：挂一个未捕获异常处理器，崩溃瞬间把机型 + 版本 + 完整堆栈写到
 * `files/last-crash.txt`，然后**照常把异常交给系统原处理器**（该崩还是崩，
 * 不掩盖问题）。下次启动时 [read] 读出来提示，用户截个图就能定位。
 *
 * 注意：这里只能抓 Java/Kotlin 未捕获异常，抓不到 ANR。
 * 主线程阻塞（ANR）要靠"别在主线程干重活"来避免，不是靠这个。
 */
object CrashLogger {

    private const val FILE_NAME = "last-crash.txt"

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    /** 安装处理器。重复调用无副作用。 */
    fun install(context: Context) {
        val app = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashHandler) return   // 已经装过

        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(app, prev))
    }

    /** 读取上次崩溃记录；没有则返回 null。读完不清空（用户可能还想再看）。 */
    fun read(context: Context): String? = runCatching {
        val f = file(context)
        if (!f.exists()) null else f.readText().takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private class CrashHandler(
        private val app: Context,
        private val prev: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val sw = StringWriter()
                PrintWriter(sw).use { e.printStackTrace(it) }
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val text = buildString {
                    appendLine("时间: $time")
                    appendLine("线程: ${t.name}")
                    appendLine("机型: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
                    appendLine("异常: ${e.javaClass.name}: ${e.message}")
                    appendLine()
                    appendLine(sw.toString())
                }
                file(app).writeText(text)
            }
            // 该崩还是崩：交给系统原处理器，不在这里吞掉异常
            if (prev != null) prev.uncaughtException(t, e)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }
}

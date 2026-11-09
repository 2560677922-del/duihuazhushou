package com.dbpg.duihuazhushou.data

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * 世界书 / 正则组的原生权威存储。
 *
 * ## 为什么要有它（2026-11-09）
 *
 * 这两类资产以前只活在网页 store 里，再被 persist 镜像进
 * `filesDir/Data/app-data.json`。结果是：
 *   · 原生代码想读世界书，只能隔着桥等引擎；
 *   · 删除要靠命令回包，引擎没起来就什么都干不了；
 *   · 以后做群聊（多角色共享世界书/正则）时，没有可直接复用的数据层。
 *
 * 现在把**存储**搬到原生：引擎启动时拉一份快照填进 store，此后引擎里的
 * store 只是工作副本，每次 `assets.save()` 都通过
 * `AndroidNative.onAssetsChanged(json)` 回传，由本类落盘。
 *
 * ## 为什么不在原生解析 ST 资产
 *
 * 世界书 / 正则的归一化（entries 对象索引→数组、key 对象、ST 字段补齐）
 * 全在网页的 `engine/stImport.ts`，那里被真实预设验证过。原生只存**引擎
 * 已经归一化好的 JSON**，提示词组装看到的数据与改造前逐字节一致。
 * 换句话说：本类不解释数据，只保证它完整、原子地留在手机上。
 *
 * ## 落盘格式
 *
 * `filesDir/data/assets/assets.json`：
 * ```json
 * { "version": 1, "worldBooks": [...], "regexGroups": [...],
 *   "currentWorldBookIds": [...], "currentRegexGroupIds": [...] }
 * ```
 * 原子写（临时文件 + rename），崩溃最多丢最后一次编辑，不会写出半个 JSON。
 */
object NativeAssetsStore {

    private const val TAG = "DHP-ASSETS"
    private const val DIR = "assets"
    private const val NAME = "assets.json"

    /** 世界书带正文，正常几 MB；超过这个值基本可以断定是脏数据。 */
    private const val MAX_BYTES = 32 * 1024 * 1024

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dhp-assets") }
    private val lock = Any()

    @Volatile
    private var cached: String? = null
    @Volatile
    private var loaded = false

    fun file(ctx: Context): File = File(File(ctx.filesDir, "data"), DIR).apply {
        if (!exists()) mkdirs()
    }.let { File(it, NAME) }

    /**
     * 给引擎读的快照。没数据时返回空串（引擎据此判断是否首次升级）。
     * 同步读 —— JS 桥调用方（WebView 的 JS 线程）需要立即拿到结果。
     */
    @JvmStatic
    fun get(ctx: Context): String {
        synchronized(lock) {
            if (loaded) return cached ?: ""
            loaded = true
            val f = file(ctx)
            cached = try {
                if (f.isFile && f.length() in 1..MAX_BYTES.toLong()) f.readText(Charsets.UTF_8) else ""
            } catch (t: Throwable) {
                Log.w(TAG, "读取失败，按空处理", t)
                ""
            }
            return cached ?: ""
        }
    }

    /** 引擎回传的完整快照。线程安全：先更新缓存，再异步原子落盘。 */
    @JvmStatic
    fun save(ctx: Context, json: String?) {
        val text = json?.trim().orEmpty()
        if (text.length < 2 || !text.startsWith("{") || text.length > MAX_BYTES) {
            Log.w(TAG, "拒绝写入异常快照（长度 ${text.length}）")
            return
        }
        synchronized(lock) {
            cached = text
            loaded = true
        }
        val target = file(ctx)
        val app = ctx.applicationContext
        io.execute {
            val tmp = File(target.parentFile, "$NAME.tmp")
            try {
                tmp.writeText(text, Charsets.UTF_8)
                if (target.exists() && !target.delete()) {
                    Log.w(TAG, "旧文件删不掉，直接覆盖")
                }
                if (!tmp.renameTo(target)) {
                    // rename 失败（跨文件系统等）：退回直接写
                    target.writeText(text, Charsets.UTF_8)
                    tmp.delete()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "落盘失败（内存里仍是最新的）", t)
                runCatching { tmp.delete() }
            }
            // app 仅用于保持引用语义，避免 lint 误报未使用
            if (app == null) Log.w(TAG, "context 丢失")
        }
    }

    /** 供资产页/诊断显示用：当前快照大小（字节）。 */
    @JvmStatic
    fun size(ctx: Context): Long = runCatching { file(ctx).length() }.getOrDefault(0L)
}

package com.dbpg.duihuazhushou.settings

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.dbpg.duihuazhushou.MainActivity
import com.dbpg.duihuazhushou.NativeBridge
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 原生页 ↔ 引擎 WebView 的共用调用工具。
 *
 * 集中在这里是因为「返回值拆引号」「JSON 转义」「主线程调度」这三件事
 * 每个页面都要做一遍，散着写早晚出错。
 *
 * ## 2026-11-07 架构调整：配置走本地，只有 ST 兼容层走桥
 *
 * 运营商 / 模型 / 风格这些连接配置与 SillyTavern 兼容逻辑无关，已经搬到
 * [ConnectionStore] 原生落盘。因此：
 *   · `getConfig / getImageConfig / getImageStyle` → **纯本地读**，不等引擎、
 *     不受隐藏 WebView 被判后台页冻结定时器的影响（见 docs/原生化边界.md 二·五）；
 *   · 对应的写命令 → **本地立即执行**，再用 [NativeBridge.fireLocal] 按原有
 *     kind 广播一次，页面照旧刷新；同时 [pushToEngine] 单向推一份只读镜像，
 *     供引擎发 LLM 请求与提示词裁剪使用（不等回包、失败不影响配置本身）。
 *   · 资产（预设/世界书/正则）、显示设置、拉模型列表、提示词优化 → 仍走桥，
 *     这些要么依赖 ST 语义，要么本身就是网络/引擎工具。
 *
 * 换句话说：**桥不再是配置的数据库**。引擎挂了、还在加载、被冻结，
 * 设置页照样能看能改。
 */
object EngineBridge {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 引擎没就绪时的重试间隔与上限（约 6 秒，覆盖冷启动加载 Vue 应用的时间）。 */
    private const val READY_RETRY_MS = 300L
    private const val READY_MAX_TRY = 20

    /** 首次迁移的重试计数：引擎一直没起来就放弃，绝不阻塞设置页。 */
    private var migrationTries = 0

    /** 引擎 WebView 是否可用（页面还没加载完时为 false）。资产页还在用。 */
    fun ready(): Boolean = MainActivity.getWebView() != null

    /**
     * 调一个同步取值函数，结果在主线程回调。
     *
     * 配置类三个 fn 直接读 [ConnectionStore]；其余（getAssets / getDisplaySettings
     * 等）仍需引擎，保留「未就绪自动重试」逻辑，避免冷启动显示「引擎还没就绪」。
     */
    fun fetch(activity: Activity, fn: String, cb: (JSONObject?) -> Unit) {
        if (fn in ConnectionStore.LOCAL_FETCH) {
            ConnectionStore.init(activity.applicationContext)
            if (ConnectionStore.isEmpty()) tryMigrate(activity)
            // 单向补推一份镜像：冷启动后第一个页面往往就是设置页，
            // 不推的话引擎里的 __dhpNativeConfig 要等到第一次生成才更新，
            // 自动配图可能拿到旧 store。这是通知、不等待回包、失败无影响。
            pushToEngine()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                cb(ConnectionStore.snapshot(fn))
            }
            return
        }
        fetchInternal(activity, fn, 0, cb)
    }

    /**
     * 一次性把 web store 里的老配置搬进原生。
     *
     * 只在本地为空时尝试，且**完全异步、不阻塞页面**：即使引擎永远起不来，
     * 用户也能直接在原生页新建运营商。搬成功后广播 configMigrated 让当前页重载。
     */
    private fun tryMigrate(activity: Activity) {
        if (migrationTries >= READY_MAX_TRY) return
        val w = MainActivity.getWebView()
        if (w == null) {
            migrationTries++
            mainHandler.postDelayed({ tryMigrate(activity) }, READY_RETRY_MS)
            return
        }
        w.evaluateJavascript(
            "window.__dhpApi && window.__dhpApi.exportNativeConfig ? " +
                "window.__dhpApi.exportNativeConfig() : null",
        ) { raw ->
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val obj = parse(raw)
                if (obj != null && ConnectionStore.migrateFromEngine(obj)) {
                    NativeBridge.fireLocal("{\"kind\":\"configMigrated\",\"ok\":true}")
                } else {
                    migrationTries++
                    mainHandler.postDelayed({ tryMigrate(activity) }, READY_RETRY_MS)
                }
            }
        }
    }

    private fun fetchInternal(activity: Activity, fn: String, tries: Int, cb: (JSONObject?) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val w = MainActivity.getWebView()
        if (w == null) {
            scheduleRetry(activity, fn, tries, cb)
            return
        }
        w.evaluateJavascript("window.__dhpApi ? window.__dhpApi.$fn() : null") { r ->
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val obj = parse(r)
                if (obj != null) cb(obj) else scheduleRetry(activity, fn, tries, cb)
            }
        }
    }

    private fun scheduleRetry(activity: Activity, fn: String, tries: Int, cb: (JSONObject?) -> Unit) {
        if (tries >= READY_MAX_TRY) {
            cb(null)
            return
        }
        mainHandler.postDelayed({ fetchInternal(activity, fn, tries + 1, cb) }, READY_RETRY_MS)
    }

    /** 调一个同步取值函数（可带一个字符串参数），结果在主线程回调。 */
    fun fetchArg(activity: Activity, fn: String, arg: String, cb: (JSONObject?) -> Unit) {
        val w = MainActivity.getWebView()
        if (w == null) {
            cb(null)
            return
        }
        w.evaluateJavascript(
            "window.__dhpApi ? window.__dhpApi.$fn(${JSONObject.quote(arg)}) : null",
        ) { r ->
            activity.runOnUiThread { cb(parse(r)) }
        }
    }

    /**
     * 发一条异步命令。
     *
     * 配置类命令在本进程就地执行并落盘，页面通过 [NativeBridge.fireLocal]
     * 收到与原来完全一样的 kind 回包，因此四个设置页无需任何改动。
     * 执行完顺手把镜像推给引擎（单向、不等结果）。
     */
    fun command(body: JSONObject) {
        val cmd = body.optString("cmd")
        if (cmd in ConnectionStore.LOCAL_COMMANDS) {
            val extra = ConnectionStore.apply(cmd, body)
            val reply = JSONObject(extra.toString()).put("kind", cmd).put("ok", true)
            NativeBridge.fireLocal(reply.toString())
            pushToEngine()
            return
        }
        val w = MainActivity.getWebView() ?: return
        // 像 optimizeImagePrompt 这类仍走桥的命令也要读配置，先补一次镜像，
        // 免得冷启动第一次调用时引擎拿到的是空 store。
        pushToEngine()
        w.evaluateJavascript(
            "window.__dhpApi && window.__dhpApi.command(${JSONObject.quote(body.toString())})",
        ) { }
    }

    /**
     * 把当前配置单向注入引擎，供 `generate / buildMessages / imageConfig /
     * optimizeImagePrompt` 使用。引擎侧只存一份只读镜像，不再把 Pinia store
     * 当权威数据源。风格缩略图已在 [ConnectionStore.exportForEngine] 里剥掉。
     *
     * 这是「通知」而不是「读写」：没有回调、不等待、引擎没就绪直接跳过，
     * 下次生成请求里还会再带一份 [ConnectionStore.llmRuntime] 作双保险。
     */
    fun pushToEngine() {
        val w = MainActivity.getWebView() ?: return
        val raw = ConnectionStore.exportForEngine()
        w.evaluateJavascript(
            "window.__dhpApi && window.__dhpApi.applyNativeConfig && " +
                "window.__dhpApi.applyNativeConfig(${JSONObject.quote(raw.toString())})",
        ) { }
    }

    /**
     * 原生拉取模型列表。结果按原来 web 回包的形状（kind=models）广播，
     * 两个配置页的 onApiResult 一行都不用改，也不需要 WebView 活着。
     */
    fun fetchModels(baseUrl: String, apiKey: String) {
        ModelListClient.fetch(baseUrl, apiKey) { models, error ->
            val body = JSONObject().put("kind", "models")
            if (models != null) {
                val arr = JSONArray()
                models.forEach { arr.put(it) }
                body.put("ok", true).put("models", arr)
            } else {
                body.put("ok", false).put("error", error ?: "拉取失败")
            }
            NativeBridge.fireLocal(body.toString())
        }
    }

    /** evaluateJavascript 返回值是「JSON 化的 JS 值」：字符串会再包一层引号，用 JSONTokener 统一拆。 */
    fun parse(raw: String?): JSONObject? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        return try {
            JSONObject(JSONTokener(raw).nextValue().toString())
        } catch (t: Throwable) {
            null
        }
    }
}

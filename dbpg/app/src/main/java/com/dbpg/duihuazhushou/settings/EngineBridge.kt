package com.dbpg.duihuazhushou.settings

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.dbpg.duihuazhushou.MainActivity
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 原生页 ↔ 引擎 WebView 的共用调用工具。
 *
 * 所有原生设置/资产页都通过 window.__dhpApi 读写引擎数据：
 *   · get 同步函数：evaluateJavascript 接返回值（JSON 字符串再包一层引号）
 *   · command(json)：异步命令，结果经 AndroidNative.onApiResult 回传
 *
 * 集中在这里是因为「返回值拆引号」「JSON 转义」「主线程调度」这三件事
 * 每个页面都要做一遍，散着写早晚出错。
 */
object EngineBridge {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 引擎没就绪时的重试间隔与上限（约 6 秒，覆盖冷启动加载 Vue 应用的时间）。 */
    private const val READY_RETRY_MS = 300L
    private const val READY_MAX_TRY = 20

    /** 引擎 WebView 是否可用（页面还没加载完时为 false）。 */
    fun ready(): Boolean = MainActivity.getWebView() != null

    /**
     * 调一个同步取值函数（如 `getConfig` / `getAssets`），结果在主线程回调。
     * 回调参数已拆掉 evaluateJavascript 的外层引号。
     *
     * ★ 引擎没就绪时**自动重试**：冷启动或清后台后，WebView 里的 Vue 应用要
     * 几秒才把 `window.__dhpApi` 挂上，这期间同步取值只会拿到 null。以前页面
     * 直接显示「引擎还没就绪，稍等几秒点刷新」，用户得自己手动点一下，
     * 而且误以为功能坏了。现在统一在这里等到就绪再回调，页面代码零改动。
     *
     * 只在「引擎未就绪」时重试；函数真正返回空对象不会重试。重试耗尽仍会
     * 回调 null，让页面保留原来的兜底提示，不会无限转圈。
     */
    fun fetch(activity: Activity, fn: String, cb: (JSONObject?) -> Unit) {
        fetchInternal(activity, fn, 0, cb)
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

    /** 发一条异步命令（cmd + 附加字段），结果走 onApiResult 监听。 */
    fun command(body: JSONObject) {
        val w = MainActivity.getWebView() ?: return
        w.evaluateJavascript(
            "window.__dhpApi && window.__dhpApi.command(${JSONObject.quote(body.toString())})",
        ) { }
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

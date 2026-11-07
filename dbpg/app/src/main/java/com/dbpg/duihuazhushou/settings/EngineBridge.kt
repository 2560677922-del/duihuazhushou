package com.dbpg.duihuazhushou.settings

import android.app.Activity
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

    /** 引擎 WebView 是否可用（页面还没加载完时为 false）。 */
    fun ready(): Boolean = MainActivity.getWebView() != null

    /**
     * 调一个同步取值函数（如 `getConfig` / `getAssets`），结果在主线程回调。
     * 回调参数已拆掉 evaluateJavascript 的外层引号；页面没就绪时回调 null。
     */
    fun fetch(activity: Activity, fn: String, cb: (JSONObject?) -> Unit) {
        val w = MainActivity.getWebView()
        if (w == null) {
            cb(null)
            return
        }
        w.evaluateJavascript("window.__dhpApi ? window.__dhpApi.$fn() : null") { r ->
            activity.runOnUiThread { cb(parse(r)) }
        }
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

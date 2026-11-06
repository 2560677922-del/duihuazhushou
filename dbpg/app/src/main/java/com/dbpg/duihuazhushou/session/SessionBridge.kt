package com.dbpg.duihuazhushou.session

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.dbpg.duihuazhushou.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 会话列表桥：从网页 store 取"会话摘要"，不在原生留副本。
 *
 * 难点只有一个 —— **时机**。WebView 里的 Vue 应用可能还没挂载完，
 * `window.__dhpNative` 还不存在；所以这里做固定间隔重试（10 秒上限），
 * 取到就回调，取不到就回空列表，绝不阻塞 UI。
 *
 * 所有回调都在主线程，Activity 直接更新 UI 即可。
 */
class SessionBridge {

    private companion object {
        const val MAX_TRIES = 25
        const val RETRY_MS = 400L
        const val JS_GET = "(window.__dhpNative && window.__dhpNative.getSessions) ? window.__dhpNative.getSessions() : ''"
    }

    private val handler = Handler(Looper.getMainLooper())

    fun load(onResult: (List<SessionSummary>) -> Unit) = attempt(0, onResult)

    fun open(id: String) {
        val web: WebView = MainActivity.getWebView() ?: return
        // id 来自网页，仍然做一次 JSON 转义，避免引号把 JS 语句截断
        web.evaluateJavascript(
            "window.__dhpNative && window.__dhpNative.openSession(${JSONObject.quote(id)});",
            null,
        )
    }

    private fun attempt(tries: Int, onResult: (List<SessionSummary>) -> Unit) {
        val web = MainActivity.getWebView()
        if (web == null || tries >= MAX_TRIES) {
            onResult(emptyList())
            return
        }
        web.evaluateJavascript(JS_GET) { raw ->
            val json = unwrap(raw)
            if (json.isBlank()) {
                handler.postDelayed({ attempt(tries + 1, onResult) }, RETRY_MS)
            } else {
                onResult(parse(json))
            }
        }
    }

    /**
     * evaluateJavascript 的回调是「返回值再 JSON 编码一次」的形式：
     * JS 返回字符串 `"[{...}]"`，回调拿到的是 `"\"[{...}]\""`。
     * 先用 JSONTokener 解一层，失败就原样用。
     */
    private fun unwrap(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching { JSONTokener(raw).nextValue() as? String ?: raw }.getOrDefault(raw)
    }

    private fun parse(json: String): List<SessionSummary> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<SessionSummary>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isBlank()) continue
            out.add(
                SessionSummary(
                    id = id,
                    title = o.optString("title", ""),
                    messageCount = o.optInt("count", 0),
                    updatedAt = o.optLong("updatedAt", 0L),
                    current = o.optBoolean("current", false),
                ),
            )
        }
        // 网页是按创建顺序存的，这里按更新时间倒序，最近聊的排最上
        return out.sortedByDescending { it.updatedAt }
    }
}

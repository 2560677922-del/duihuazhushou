package com.dbpg.duihuazhushou.settings

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 原生拉取「运营商下有哪些模型」。
 *
 * 原来这一步是 `__dhpApi.fetchModels` → web 的 `fetchModels()` → `httpRequest`
 * （端上再转原生 HTTP）。绕一大圈只为了拿一个字符串数组，却让配置页必须
 * 等 WebView 起来。现在整条链路就是一次 OkHttp GET，配置页彻底不碰引擎。
 *
 * 候选地址与响应解析规则复刻自 `src/engine/novelai.ts` 的
 * `candidateModelsEndpoints` 和 `src/engine/imageGen.ts` 的 `fetchModels`：
 * 中转站的路径五花八门（`/v1`、`/api/v1`、自定义前缀），多试几个端点比
 * 让用户手填地址靠谱。改这里时请同步对照那两处。
 */
object ModelListClient {

    private val main = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 异步拉取；结果一定在主线程回调。
     *
     * @param cb (模型名列表, 错误信息) —— 二者恰好一个非空
     */
    fun fetch(baseUrl: String, apiKey: String, cb: (List<String>?, String?) -> Unit) {
        val urls = candidates(baseUrl)
        if (urls.isEmpty()) {
            cb(null, "无法解析请求地址：$baseUrl")
            return
        }
        Thread {
            val problems = ArrayList<String>()
            var result: List<String>? = null
            for (url in urls) {
                try {
                    val builder = Request.Builder().url(url).header("Accept", "application/json")
                    if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
                    client.newCall(builder.get().build()).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            problems.add("$url → HTTP ${resp.code} ${body.take(120)}")
                            return@use
                        }
                        val ids = parseIds(body)
                        if (ids.isEmpty()) {
                            problems.add("$url → HTTP 200 但不是模型接口：${body.take(100)}")
                        } else {
                            result = ids
                        }
                    }
                } catch (t: Throwable) {
                    problems.add("$url → 连不上（${t.message}）")
                }
                if (result != null) break
            }
            val models = result
            val error = if (models == null) {
                "拉取模型列表失败，已尝试 ${urls.size} 个地址：\n  " +
                    problems.joinToString("\n  ") +
                    "\n\n部分服务不提供 /models 端点，可以手动填写模型名。"
            } else null
            main.post { cb(models, error) }
        }.start()
    }

    /** 复刻 candidateModelsEndpoints：从完整地址推出 origin / base，再列候选。 */
    private fun candidates(raw0: String): List<String> {
        val raw = raw0.trim()
        if (raw.isEmpty()) return emptyList()
        if (Regex("/models$", RegexOption.IGNORE_CASE).containsMatchIn(raw)) {
            return listOf(raw.trimEnd('/'))
        }
        val uri = runCatching { URI(raw) }.getOrNull() ?: return emptyList()
        if (uri.scheme == null || uri.host == null) return emptyList()
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val origin = "${uri.scheme}://${uri.host}$port"
        val path = raw.trimEnd('/')
        val stripped = path.replace(Regex("/(v\\d+|api)(/.*)?$", RegexOption.IGNORE_CASE), "")
        val base = stripped.ifEmpty { origin }
        return linkedSetOf(
            "$origin/v1/models",
            "$origin/models",
            "$base/v1/models",
            "$path/v1/models",
            "$path/models",
        ).toList()
    }

    /** 兼容常见响应形状：数组 / data / models / result / list，元素可为字符串或对象。 */
    private fun parseIds(body: String): List<String> {
        val json = runCatching { JSONTokener(body).nextValue() }.getOrNull() ?: return emptyList()
        if (json is JSONObject && json.has("error") && !json.has("data") && !json.has("models")) {
            return emptyList()
        }
        val raw: Any? = when (json) {
            is JSONArray -> json
            is JSONObject -> json.optJSONArray("data")
                ?: json.optJSONArray("models")
                ?: json.optJSONArray("result")
                ?: json.optJSONArray("list")
            else -> null
        }
        val arr = raw as? JSONArray ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            val id = when (v) {
                is String -> v
                is JSONObject -> v.optString("id").ifBlank { v.optString("name") }
                    .ifBlank { v.optString("model") }
                else -> ""
            }
            if (id.isNotEmpty()) out.add(id)
        }
        return out.sorted()
    }
}

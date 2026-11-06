package com.dbpg.duihuazhushou.imagegen

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 原生生图网络层（OkHttp）。
 *
 * 为什么用原生而不是网页 fetch：
 *   1. 网页端会受 CORS 限制，很多中转站不放行跨域；原生没有这层限制。
 *   2. 生图动辄几十秒到几分钟，网页端容易超时；这里给足长超时。
 *   3. 需要连「本地电脑上的模型」（http://192.168.x.x:port），原生可直接访问局域网。
 *
 * 本类只负责「发请求 / 拿原始字节」，不做任何解析（解析在 stcore 的 ImagePayloadExtractor）。
 * 调用方必须在后台线程调用（[ImageGenService] 已用单线程池保证）。
 */
class HttpImageClient {

    /** 一次原始响应：状态码 + Content-Type + 字节。 */
    data class RawResponse(val code: Int, val contentType: String?, val body: ByteArray)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)     // 生图很慢，读超时给到 5 分钟
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(360, TimeUnit.SECONDS)     // 整个调用上限 6 分钟
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** POST JSON（生图请求）。 */
    fun postJson(url: String, headers: Map<String, String>, json: String): RawResponse {
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return execute(builder.build())
    }

    /** GET 字节（下载 JSON 里返回的图片 url）。 */
    fun getBytes(url: String): RawResponse {
        val builder = Request.Builder().url(url).get()
        return execute(builder.build())
    }

    private fun execute(request: Request): RawResponse {
        client.newCall(request).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            return RawResponse(resp.code, resp.header("Content-Type"), bytes)
        }
    }

    /** 释放连接池（服务端用完即弃时调用）。 */
    fun shutdown() {
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }
}

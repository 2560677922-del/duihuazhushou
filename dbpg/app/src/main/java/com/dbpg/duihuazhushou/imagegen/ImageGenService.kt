package com.dbpg.duihuazhushou.imagegen

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dbpg.stcore.imagegen.ImagePayloadExtractor
import com.dbpg.stcore.imagegen.ImageRequestBuilder
import java.util.concurrent.Executors

/**
 * 原生生图服务：把「请求 → 解析（含 ZIP 解压）→ 落盘」串起来，对 UI 只暴露回调。
 *
 * 刻意保持扁平：网络交给 [HttpImageClient]，解析交给 stcore 的 [ImagePayloadExtractor]，
 * 存储交给 [ImageStore]，本类只做编排，不写具体 IO 细节。
 */
class ImageGenService(context: Context) {

    /** 一次生图请求参数（与具体中转站无关，字段按需填）。 */
    data class Request(
        val url: String,
        val apiKey: String,
        val model: String,
        val prompt: String,
        val size: String? = null,
        val n: Int = 1,
        val responseFormat: String = "b64_json",
        val headers: Map<String, String> = emptyMap(),
        val extraBody: Map<String, Any?> = emptyMap(),
    )

    interface Callback {
        fun onSuccess(images: List<ImageStore.StoredImage>)
        fun onError(message: String)
    }

    private val client = HttpImageClient()
    private val store = ImageStore(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** 后台执行；结果切回主线程。 */
    fun generate(req: Request, cb: Callback) {
        executor.execute {
            val outcome = runCatching { doGenerate(req) }
            main.post {
                outcome.fold(
                    onSuccess = { imgs ->
                        if (imgs.isEmpty()) cb.onError("没有解析到图片（检查中转站返回格式）")
                        else cb.onSuccess(imgs)
                    },
                    onFailure = { cb.onError(it.message ?: "生图失败") },
                )
            }
        }
    }

    /**
     * 同步版：直接返回结果，供已经在后台线程的调用方用（自动配图批量出图）。
     *
     * 不走 [generate] 的回调是因为自动配图要"一张接一张、失败就换端点重试"，
     * 用回调得套 CountDownLatch，反而更容易写错；调用方本来就在 IO 线程。
     */
    fun generateSync(req: Request): List<ImageStore.StoredImage> = doGenerate(req)

    private fun doGenerate(req: Request): List<ImageStore.StoredImage> {
        val body = ImageRequestBuilder.buildOpenAiJson(
            model = req.model,
            prompt = req.prompt,
            size = req.size,
            n = req.n,
            responseFormat = req.responseFormat,
            extra = req.extraBody,
        )
        val headers = ImageRequestBuilder.buildHeaders(req.apiKey, req.headers)
        val raw = client.postJson(req.url, headers, body)

        // 解析（ZIP / 图片 / JSON / dataUrl / base64 全部在 extract 内部处理）
        val parsed = ImagePayloadExtractor.extract(raw.contentType, raw.body)
        if (parsed.isEmpty) {
            val reason = parsed.rawText?.take(300) ?: "HTTP ${raw.code}，未返回图片"
            throw IllegalStateException(reason)
        }

        val stored = ArrayList<ImageStore.StoredImage>()
        for (src in parsed.images) {
            when (src) {
                is ImagePayloadExtractor.Source.Inline ->
                    store.save(src.bytes, src.mime)?.let { stored.add(it) }
                is ImagePayloadExtractor.Source.Remote ->
                    downloadAndStore(src.url, stored)
            }
        }
        return stored
    }

    /** JSON 只给了 url 时，再下载一次拿真实字节。 */
    private fun downloadAndStore(url: String, out: MutableList<ImageStore.StoredImage>) {
        val resp = client.getBytes(url)
        val parsed = ImagePayloadExtractor.extract(resp.contentType, resp.body)
        for (src in parsed.images) {
            if (src is ImagePayloadExtractor.Source.Inline) {
                store.save(src.bytes, src.mime)?.let { out.add(it) }
            }
        }
    }

    fun shutdown() {
        runCatching { executor.shutdown() }
        client.shutdown()
    }
}

package com.dbpg.duihuazhushou.imagegen

import android.content.Context
import com.dbpg.duihuazhushou.MainActivity
import org.json.JSONObject

/**
 * 生图的原生桥：网页调用 {@code AndroidNative.generateImage(json, callbackId)} 进来，
 * 原生跑完把结果通过 {@code window.__dhpOnImageResult(callbackId, payload)} 回传给网页。
 *
 * 参数 JSON 形如：
 * {
 *   "url": "https://relay.example.com/v1/images/generations",
 *   "apiKey": "sk-...",
 *   "model": "flux",
 *   "prompt": "一只猫",
 *   "size": "1024x1024",
 *   "n": 1,
 *   "responseFormat": "b64_json",
 *   "headers": {"X-Custom": "1"},
 *   "extraBody": {"watermark": false}
 * }
 *
 * 回调 payload：
 * { "ok": true,  "images": [{"path": "...", "uri": "file://...", "mime": "image/png"}] }
 * { "ok": false, "error": "错误原因" }
 */
object ImageGenBridge {

    fun handle(context: Context, reqJson: String, callbackId: String) {
        val req = try {
            parse(reqJson)
        } catch (e: Exception) {
            reply(callbackId, false, emptyList(), e.message ?: "参数解析失败")
            return
        }
        val service = ImageGenService(context.applicationContext)
        service.generate(req, object : ImageGenService.Callback {
            override fun onSuccess(images: List<ImageStore.StoredImage>) {
                reply(callbackId, true, images, null)
                service.shutdown()
            }

            override fun onError(message: String) {
                reply(callbackId, false, emptyList(), message)
                service.shutdown()
            }
        })
    }

    private fun parse(json: String): ImageGenService.Request {
        val o = JSONObject(json)
        return ImageGenService.Request(
            url = o.getString("url"),
            apiKey = o.optString("apiKey", ""),
            model = o.optString("model", ""),
            prompt = o.getString("prompt"),
            size = o.optString("size", "").ifEmpty { null },
            n = o.optInt("n", 1),
            responseFormat = o.optString("responseFormat", "b64_json"),
            headers = toStringMap(o.optJSONObject("headers")),
            extraBody = toAnyMap(o.optJSONObject("extraBody")),
        )
    }

    private fun toStringMap(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = o.optString(k, "")
        }
        return out
    }

    private fun toAnyMap(o: JSONObject?): Map<String, Any?> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = o.opt(k)
        }
        return out
    }

    /** 回主线程，把结果以 JS 函数调用的形式送回网页。 */
    private fun reply(callbackId: String, ok: Boolean, images: List<ImageStore.StoredImage>, error: String?) {
        val payload = JSONObject().put("ok", ok)
        if (ok) {
            val arr = org.json.JSONArray()
            for (im in images) {
                arr.put(
                    JSONObject()
                        .put("path", im.path)
                        .put("uri", im.uri.toString())
                        .put("mime", im.mime),
                )
            }
            payload.put("images", arr)
        } else {
            payload.put("error", error ?: "未知错误")
        }
        val js = "window.__dhpOnImageResult && window.__dhpOnImageResult(" +
            JSONObject.quote(callbackId) + "," + payload + ")"
        MainActivity.runOnUi { MainActivity.getWebView()?.evaluateJavascript(js, null) }
    }
}

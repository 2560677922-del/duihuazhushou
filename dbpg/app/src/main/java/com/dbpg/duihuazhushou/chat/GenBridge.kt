package com.dbpg.duihuazhushou.chat

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.dbpg.duihuazhushou.MainActivity
import com.dbpg.duihuazhushou.settings.ConnectionStore
import com.dbpg.duihuazhushou.settings.EngineBridge
import com.dbpg.stcore.session.PromptMessage
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 原生 → ST 引擎的生成通道（单例，进程内一份）。
 *
 * 为什么要有这一层，而不是 Activity 直接 evaluateJavascript：
 *   1. JS 回调（onGenDelta / onGenDone）是**全局静态入口**，必须有个地方
 *      按 requestId 找回"这次生成属于哪个界面"；Activity 会被重建，
 *      把它自己注册进去迟早漏注销，泄漏整个 Activity。
 *   2. 停止生成、切会话、退出界面都要能"掐掉这一条"，而不是掐掉全部。
 *   3. 回调线程是 WebView 的 JavaBridge 线程，必须统一切主线程再碰 UI。
 *
 * 所以：Activity 只实现 [Sink]，进来时 [request]，走时 [release]。
 * 生成中途 Activity 没了（比如按返回），回调会被安全丢弃，不会崩。
 */
object GenBridge {

    /** 一次生成的接收者。全部在主线程回调。 */
    interface Sink {
        /** 累积后的全文（不是增量）。原生直接整段替换即可。 */
        fun onGenDelta(text: String)
        fun onGenDone(full: String)
        fun onGenError(message: String)
        /** 用户主动停止。partial 是已经生成出来的部分。 */
        fun onGenStopped(partial: String)
    }

    private class Holder(val sink: Sink) {
        @Volatile
        var latest: String = ""
    }

    private val entries = ConcurrentHashMap<String, Holder>()
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------ 发起

    /**
     * 发起一次生成。
     *
     * @param messages 已按"当前版本"导出、且剥掉图片标记的历史（见 stcore SessionHistory）
     * @param userInput 当前这一句；重新生成时是那一轮的原文
     * @return requestId；WebView 不可用时返回 null（调用方应提示"引擎没准备好"）
     */
    fun request(
        sessionId: String,
        messages: List<PromptMessage>,
        userInput: String,
        imageInstruction: Boolean?,
        imageMax: Int,
        assetBinding: JSONObject? = null,
        /** 显式预设 id：重新生成时也要带上，别让引擎靠全局状态猜 */
        presetId: String = "",
        /**
         * 本次扮演的角色卡 uid。
         *
         * 会话自己记着角色（SessionBindingStore.characterId），每次生成都带，
         * 这样两个会话交替发消息不会用错卡 —— 全局 currentCharacterId 只服务
         * 网页版和角色库入口。
         */
        characterId: String = "",
        /** 明确不注入角色卡（群聊公共区用户发言） */
        noCharacter: Boolean = false,
        /** 用户角色（资产页「我的角色」）：名字与人设，写进 {{user}} / {{persona}} */
        userPersona: JSONObject? = null,
        /** 本次用的 LLM 运营商 uid；空 = 全局当前选中 */
        configId: String = "",
        sink: Sink,
    ): String? {
        val wv: WebView = MainActivity.getWebView() ?: return null

        // 配置已改由原生 ConnectionStore 掌管。每次生成前补一次单向镜像推送：
        // 配置变更时引擎可能还没启动/刚重建，这里兜底保证 imageConfig() 与
        // buildMessages() 拿到的都是最新配置；req 里再带一份 LLM config 双保险。
        EngineBridge.pushToEngine()

        val id = "g" + System.nanoTime().toString(36)
        entries[id] = Holder(sink)

        val arr = JSONArray()
        for (m in messages) {
            arr.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        val req = JSONObject()
            .put("requestId", id)
            .put("sessionId", sessionId)
            .put("messages", arr)
            .put("userInput", userInput)
            // null = "还没读到配置，让 web 按它自己的设置判断"
            .put("imageInstruction", imageInstruction ?: JSONObject.NULL)
            .put("imageInstructionMax", imageMax)
            .put("config", ConnectionStore.llmRuntime(configId))
        // 顶层再带一份：web 侧 buildMessages 优先采用它，避免 assetBinding
        // 缺 presetId 时回退到「引擎当前预设」猜错（重新生成拒答的常见原因）
        if (presetId.isNotBlank()) req.put("presetId", presetId)
        // 本对话的资产引用（世界书/预设/正则）；为空走全局配置
        if (assetBinding != null && assetBinding.length() > 0) {
            req.put("assetBinding", assetBinding)
        }
        if (characterId.isNotBlank()) req.put("characterId", characterId)
        if (noCharacter) req.put("noCharacter", true)
        if (userPersona != null && userPersona.length() > 0) req.put("userPersona", userPersona)

        MainActivity.runOnUi {
            wv.evaluateJavascript(
                "window.__dhpGen && window.__dhpGen.generate ? " +
                    "window.__dhpGen.generate(${JSONObject.quote(req.toString())}) : null",
                null,
            )
        }
        return id
    }

    /**
     * 停止某一次生成（用户点「停止」）。
     *
     * ★ 刻意**不在这里删 entry**：web 会回 `onGenStopped`，那时还要靠它
     *   找回到哪个界面的回调。提前删掉的话，界面就会永远卡在"生成中"。
     */
    fun stop(requestId: String) {
        sendStop(requestId)
    }

    private fun sendStop(requestId: String) {
        val wv = MainActivity.getWebView() ?: return
        MainActivity.runOnUi {
            wv.evaluateJavascript(
                "window.__dhpGen && window.__dhpGen.stop ? " +
                    "window.__dhpGen.stop(${JSONObject.quote(requestId)}) : null",
                null,
            )
        }
    }

    /**
     * 界面销毁时调用：把属于它的请求全部摘掉。
     *
     * 先 remove 再发 stop —— 这样即便 web 还回回调，也找不到 sink，
     * 不会去碰一个已经销毁的 Activity。
     */
    fun release(sink: Sink) {
        val dead = entries.filterValues { it.sink === sink }.keys.toList()
        for (id in dead) {
            entries.remove(id)
            sendStop(id)
        }
    }

    // ------------------------------------------------------------ JS 回调（WebView 线程）

    fun onGenDelta(requestId: String, text: String) {
        val h = entries[requestId] ?: return
        h.latest = text
        main.post { h.sink.onGenDelta(text) }
    }

    fun onGenDone(requestId: String, full: String) {
        val h = entries.remove(requestId) ?: return
        main.post { h.sink.onGenDone(full) }
    }

    fun onGenError(requestId: String, message: String) {
        val h = entries.remove(requestId) ?: return
        main.post { h.sink.onGenError(message) }
    }

    fun onGenStopped(requestId: String) {
        val h = entries.remove(requestId) ?: return
        val partial = h.latest
        main.post { h.sink.onGenStopped(partial) }
    }

    // ------------------------------------------------------------ 配置快照（一次性读取）

    /**
     * 读一份"生图 + 标签优化"的配置快照。
     *
     * 读一次就够：这些是用户设置，不是会话数据。自动配图由原生自己发请求，
     * 所以拿到之后不再回头问 web。
     */
    fun fetchImageConfig(onResult: (String?) -> Unit) {
        evalJson("window.__dhpGen && window.__dhpGen.imageConfig ? window.__dhpGen.imageConfig() : null", onResult)
    }

    /** 读显示配置（正则 / 变量 / 宏上下文），界面打开时拉一次。 */
    fun fetchDisplayConfig(sessionId: String, onResult: (String?) -> Unit) {
        evalJson(
            "window.__dhpGen && window.__dhpGen.displayConfig ? " +
                "window.__dhpGen.displayConfig(${JSONObject.quote(sessionId)}) : null",
            onResult,
        )
    }

    /** 读"有哪些预设 / 世界书 / 角色可启用"（只含清单和当前项，不含正文）。 */
    fun fetchAssetCatalog(onResult: (String?) -> Unit) {
        evalJson(
            "window.__dhpGen && window.__dhpGen.assetCatalog ? window.__dhpGen.assetCatalog() : null",
            onResult,
        )
    }

    /**
     * 把「会话 → 角色卡」绑定到引擎（会话级映射，不动全局当前角色）。
     *
     * 打开会话时调一次：显示侧的 `{{char}}` 宏、生成侧取角色卡都按它走。
     * 引擎还没就绪就跳过 —— 生成请求本身也带 characterId，不会用错卡。
     */
    fun bindSession(sessionId: String, characterId: String) {
        if (sessionId.isBlank()) return
        val wv = MainActivity.getWebView() ?: return
        MainActivity.runOnUi {
            wv.evaluateJavascript(
                "window.__dhpGen && window.__dhpGen.bindSession ? " +
                    "window.__dhpGen.bindSession(" +
                    "${JSONObject.quote(sessionId)}, ${JSONObject.quote(characterId)}) : null",
                null,
            )
        }
    }

    /**
     * 切换启用项。数据仍在 ST 兼容层，原生只下指令 —— 不复制预设进来。
     *
     * 这是用户说的"安卓只负责启用哪个"的落点：原生出一个清单让用户点，
     * 真正的内容照旧存在网页里，两边不会分叉。
     */
    fun setActive(json: String) {
        val wv = MainActivity.getWebView() ?: return
        MainActivity.runOnUi {
            wv.evaluateJavascript(
                "window.__dhpGen && window.__dhpGen.setActive ? " +
                    "window.__dhpGen.setActive(${JSONObject.quote(json)}) : null",
                null,
            )
        }
    }

    private fun evalJson(script: String, onResult: (String?) -> Unit) {
        val wv = MainActivity.getWebView()
        if (wv == null) {
            main.post { onResult(null) }
            return
        }
        MainActivity.runOnUi {
            wv.evaluateJavascript(script) { raw ->
                val decoded = decodeJsString(raw)
                main.post { onResult(decoded) }
            }
        }
    }

    /**
     * evaluateJavascript 回来的是"JS 值的 JSON 序列化"：
     * 字符串会带一层引号并转义，null 是字面量 null。
     * 这里还原成原始字符串。
     */
    private fun decodeJsString(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        return runCatching {
            if (raw.startsWith("\"") && raw.endsWith("\"")) JSONObject("{\"v\":$raw}").getString("v")
            else raw
        }.getOrNull()
    }
}

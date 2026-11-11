package com.dbpg.duihuazhushou.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.dbpg.duihuazhushou.MainActivity
import com.dbpg.stcore.character.CharacterCardParser
import com.dbpg.stcore.session.SessionDoc
import com.dbpg.stcore.session.SessionStore
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 一次性数据迁移：把网页（IndexedDB）里的数据搬到原生存储。
 *
 * 分工：
 *   - 本类是**驱动方**：先要清单，再一条条要内容，收到就落盘；
 *   - web 侧 `__dhpData` 只读导出，不修改原数据；
 *   - 落盘由 [SessionStore] / [AssetStore] 负责，本类不碰文件格式。
 *
 * 为什么不用"一个大 JSON"：配图是 base64，几十个会话足以撑爆 WebView
 * 的字符串传输。分页拉取每次只过一个会话，内存占用可预测。
 *
 * 为什么每个请求都有超时：`evaluateJavascript` 是单向的，
 * 页面如果崩了/切走了就永远收不到回调。没有超时，界面会永远卡在"迁移中"。
 */
object MigrationCoordinator {

    private const val TAG = "DHP-MIG"
    private const val STEP_TIMEOUT_MS = 15_000L

    enum class Phase { IDLE, INDEX, SESSIONS, CHARACTERS, ASSETS, DONE, FAILED }

    data class Progress(
        val phase: Phase,
        val done: Int = 0,
        val total: Int = 0,
        val message: String = "",
        val finished: Boolean = false,
        val failed: Boolean = false,
    )

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dhp-migration") }

    private var listener: ((Progress) -> Unit)? = null
    private var token: String = ""
    private var appContext: Context? = null
    /** 是否还在跑。和 listener 分开：界面退出只是没人看进度，流程继续。 */
    private var running = false
    private var lastProgress: Progress? = null

    private var sessionStore: SessionStore? = null
    private var assetStore: AssetStore? = null

    private val sessionIds = ArrayDeque<String>()
    private val characterIds = ArrayDeque<String>()
    private val assetIds = ArrayDeque<String>()

    private var totalSessions = 0
    private var doneSessions = 0
    private var okSessions = 0
    private var failedSessions = 0
    private var totalAssets = 0
    private var doneAssets = 0
    private var okAssets = 0
    private var missingAssets = 0

    /** 当前步骤的超时任务；收到回包就取消。 */
    private var timeout: Runnable? = null

    // ------------------------------------------------------------ 对外

    fun isRunning(): Boolean = running

    fun start(context: Context, onProgress: (Progress) -> Unit) {
        if (running) {
            // 已经在跑：把新界面接上去看进度，不重开一轮（重开会让 token 失效）
            listener = onProgress
            lastProgress?.let { onProgress(it) }
            return
        }
        appContext = context.applicationContext
        listener = onProgress
        running = true
        lastProgress = null
        token = "mig-${System.currentTimeMillis()}"
        sessionStore = SessionStore(File(File(context.filesDir, "data"), "sessions"))
        assetStore = AssetStore(context)

        sessionIds.clear(); characterIds.clear(); assetIds.clear()
        totalSessions = 0; doneSessions = 0; okSessions = 0; failedSessions = 0; okAssets = 0; missingAssets = 0

        emit(Progress(Phase.INDEX, message = "正在读取网页数据清单…"))
        callJs("__dhpData && __dhpData.index('$token')")
        armTimeout { fail("读取清单超时，请确认网页已正常加载") }
    }

    /** 界面退出时调用：只是不再更新界面，流程继续跑完（写盘不依赖界面）。 */
    fun cancel() {
        listener = null
    }

    // ------------------------------------------------------------ JS 回调（来自 WebView 线程）

    fun onIndex(incomeToken: String, json: String) = onMain(incomeToken) {
        clearTimeout()
        val o = runCatching { JSONObject(json) }.getOrNull()
            ?: return@onMain fail("清单解析失败")

        // 会话
        o.optJSONArray("sessions")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let { sessionIds.add(it) }
            }
        }
        // 角色
        o.optJSONArray("characters")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("uid")?.takeIf { it.isNotEmpty() }?.let { characterIds.add(it) }
            }
        }
        // 图片资产
        o.optJSONArray("assetIds")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotEmpty() }?.let { assetIds.add(it) }
            }
        }
        totalSessions = sessionIds.size
        totalAssets = assetIds.size
        // 配置整包落盘（原生设置页以后直接读这一份）
        o.optJSONObject("configs")?.let { cfg -> io.execute { saveConfig(cfg) } }
        emit(Progress(Phase.SESSIONS, 0, totalSessions, "找到 $totalSessions 个会话、${characterIds.size} 张角色卡、$totalAssets 张图片"))
        nextSession()
    }

    fun onSession(incomeToken: String, id: String, json: String) = onMain(incomeToken) {
        clearTimeout()
        val doc = if (json.isEmpty()) null else parseSession(json)
        if (doc == null) {
            // 空包/坏包：计入失败但继续，不能因为一条卡住整轮
            failedSessions++
            afterSessionSaved()
            return@onMain
        }
        // 会话可能有几百 KB，落盘放后台，别在主线程顿一下
        io.execute {
            val ok = sessionStore?.save(doc) == true
            main.post {
                if (ok) okSessions++ else failedSessions++
                afterSessionSaved()
            }
        }
    }

    private fun afterSessionSaved() {
        doneSessions++
        emit(Progress(Phase.SESSIONS, doneSessions, totalSessions, "会话 $doneSessions/$totalSessions"))
        nextSession()
    }

    fun onCharacter(incomeToken: String, uid: String, json: String) = onMain(incomeToken) {
        clearTimeout()
        if (json.isNotEmpty()) io.execute { saveCharacter(uid, json) }
        nextCharacter()
    }

    fun onAsset(incomeToken: String, id: String, dataUrl: String) = onMain(incomeToken) {
        clearTimeout()
        if (dataUrl.isEmpty()) {
            missingAssets++
            afterAssetSaved()
            return@onMain
        }
        io.execute {
            val saved = assetStore?.saveDataUrl(id, dataUrl)
            main.post {
                if (saved != null) okAssets++ else missingAssets++
                afterAssetSaved()
            }
        }
    }

    private fun afterAssetSaved() {
        doneAssets++
        emit(Progress(Phase.ASSETS, doneAssets, totalAssets, "图片 $doneAssets/$totalAssets"))
        nextAsset()
    }

    // ------------------------------------------------------------ 队列推进

    private fun nextSession() {
        val id = sessionIds.removeFirstOrNull()
        if (id == null) {
            emit(Progress(Phase.CHARACTERS, okSessions, totalSessions, "会话完成：成功 $okSessions，跳过 $failedSessions"))
            nextCharacter()
            return
        }
        callJs("__dhpData && __dhpData.session('$token', ${JSONObject.quote(id)})")
        armTimeout { fail("读取会话 $id 超时") }
    }

    private fun nextCharacter() {
        val uid = characterIds.removeFirstOrNull()
        if (uid == null) {
            emit(Progress(Phase.ASSETS, 0, totalAssets, "角色卡完成，开始迁移图片…"))
            nextAsset()
            return
        }
        callJs("__dhpData && __dhpData.character('$token', ${JSONObject.quote(uid)})")
        armTimeout { fail("读取角色卡 $uid 超时") }
    }

    private fun nextAsset() {
        val id = assetIds.removeFirstOrNull()
        if (id == null) {
            finish()
            return
        }
        callJs("__dhpData && __dhpData.asset('$token', ${JSONObject.quote(id)})")
        armTimeout { fail("读取图片 $id 超时") }
    }

    private fun finish() {
        clearTimeout()
        running = false
        val msg = "迁移完成：会话成功 $okSessions / 失败 $failedSessions，" +
            "图片 $okAssets 张" + if (missingAssets > 0) "，缺失 $missingAssets 张" else ""
        emit(Progress(Phase.DONE, okSessions, totalSessions, msg, finished = true))
        listener = null
    }

    private fun fail(message: String) {
        clearTimeout()
        running = false
        Log.w(TAG, message)
        emit(Progress(Phase.FAILED, doneSessions, totalSessions, message, finished = true, failed = true))
        listener = null
    }

    // ------------------------------------------------------------ 落盘

    private fun parseSession(json: String): SessionDoc? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val session = root.optJSONObject("session") ?: return null
        return SessionDoc.parse(session.toString())
    }

    private fun saveCharacter(uid: String, json: String) {
        val ctx = appContext ?: return
        val dir = File(ctx.filesDir, "characters").apply { if (!exists()) mkdirs() }
        val name = runCatching { CharacterCardParser.parseJson(json)?.name }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: uid
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40).ifEmpty { "character" }
        val file = File(dir, "mig_${safe}_${uid.takeLast(6)}.json")
        runCatching { file.writeText(json) }
    }

    private fun saveConfig(cfg: JSONObject) {
        val ctx = appContext ?: return
        val dir = File(ctx.filesDir, "data/config").apply { if (!exists()) mkdirs() }
        cfg.optJSONObject("llm")?.let { runCatching { File(dir, "llm.json").writeText(it.toString()) } }
        cfg.optJSONObject("image")?.let { runCatching { File(dir, "image.json").writeText(it.toString()) } }
    }

    // ------------------------------------------------------------ 工具

    private fun callJs(script: String) {
        val wv: WebView? = MainActivity.getWebView()
        if (wv == null) {
            fail("网页还没准备好，请先回到聊天页再试")
            return
        }
        wv.evaluateJavascript(script, null)
    }

    private fun onMain(incomeToken: String, block: () -> Unit) {
        main.post {
            // 过期 token 的迟到回调直接丢掉（用户重开过一次迁移）
            // 注意：这里不能判断 listener 是否为空 —— 界面退出后流程要继续跑完
            if (incomeToken != token || !running) return@post
            block()
        }
    }

    private fun emit(p: Progress) {
        lastProgress = p
        listener?.invoke(p)
    }

    private fun armTimeout(onFire: () -> Unit) {
        clearTimeout()
        val r = Runnable { onFire() }
        timeout = r
        main.postDelayed(r, STEP_TIMEOUT_MS)
    }

    private fun clearTimeout() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
    }

}

package com.dbpg.duihuazhushou;

import android.content.Context;
import android.content.Intent;
import android.webkit.JavascriptInterface;

/**
 * 网页侧 ↔ 原生聊天界面的桥（对应 web 的 window.AndroidNative）。
 *
 * <p>只做一件事：网页把当前会话快照（JSON 字符串）交给原生端，
 * 原生端据此打开 {@link NativeChatActivity} 并接管渲染与输入。
 * 原生端要回传数据时，直接拿 {@link MainActivity#getWebView()} 上的
 * {@code window.__dhpNative} / {@code window.__dhpApp}，不再经过本类。
 *
 * <p>这样分界的理由：打开是"一次性"的，适合用参数把数据带过去；
 * 而"轮询快照 / 发送消息"是"持续"的，原生端持有 WebView 引用更顺手，
 * 也避免网页侧要维护一个回调注册表。
 */
public final class NativeBridge {

    private final Context context;

    public NativeBridge(Context context) {
        this.context = context;
    }

    /**
     * 由网页入口按钮调用：{@code AndroidNative.openNativeChatWithId(sessionId, snapshot)}。
     *
     * <p>★ 为什么把 sessionId 单独当一个参数：
     *
     * <p>原生端只要有 id 就能直接读本地会话文件，**不需要解析快照**。
     * 以前只有一个签名的 {@code openNativeChat(snapshotJson)}，原生为了拿 id
     * 必须在 onCreate 里把整个快照 JSONObject 化一遍；而快照里带着全部消息，
     * 会话一长就是几 MB 的字符串。主线程被这一步按住，第一帧画不出来，
     * 用户看到的就是"点一下原生聊天就黑屏卡住"。
     *
     * <p>现在 sessionId 走独立参数，原生零解析；快照退化成兜底数据，
     * 只有本地确实没有这份会话时才会用到。
     *
     * @param sessionId    会话 id，可为空（为空时原生再从快照里找）
     * @param snapshotJson 瘦身后的快照（不含图片 base64、不含正则表），可为空
     */
    @JavascriptInterface
    public void openNativeChatWithId(String sessionId, String snapshotJson) {
        launch(sessionId, snapshotJson);
    }

    /**
     * 旧签名，保留兼容（老网页产物 / 老 APK 混用时不会崩）。
     *
     * @param snapshotJson 网页 window.__dhpNative.getSnapshot() 序列化结果
     */
    @JavascriptInterface
    public void openNativeChat(String snapshotJson) {
        // 这里不做解析：把整串原样交给 Activity，由它在后台线程提取 id。
        launch("", snapshotJson);
    }

    private void launch(String sessionId, String snapshotJson) {
        try {
            Intent intent = new Intent(context, NativeChatActivity.class);
            intent.putExtra(NativeChatActivity.EXTRA_SESSION_ID, sessionId == null ? "" : sessionId);
            intent.putExtra(NativeChatActivity.EXTRA_SNAPSHOT, snapshotJson == null ? "" : snapshotJson);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            android.util.Log.w("DHP", "打开原生聊天失败", t);
        }
    }

    /**
     * 纯原生（OkHttp）生图：网页调用
     * {@code AndroidNative.generateImage(JSON.stringify(req), "cb1")}。
     *
     * <p>原生端负责请求、ZIP 解压、落盘，完成后调用
     * {@code window.__dhpOnImageResult("cb1", payload)} 把本地路径/ Uri 回传。
     * 具体编排见 {@code com.dbpg.duihuazhushou.imagegen.ImageGenBridge}。
     */
    @JavascriptInterface
    public void generateImage(String reqJson, String callbackId) {
        try {
            com.dbpg.duihuazhushou.imagegen.ImageGenBridge.INSTANCE
                    .handle(context, reqJson == null ? "{}" : reqJson,
                            callbackId == null ? "" : callbackId);
        } catch (Throwable t) {
            android.util.Log.w("DHP", "原生图图失败", t);
        }
    }

    // ------------------------------------------------------------------
    // 一次性数据迁移回调（web → 原生）
    //
    // 迁移是"原生主动要、web 回调给"的分页流程：原生 evaluateJavascript 调
    // __dhpData.index()/session()/asset()，web 处理完调这四个方法把结果送回来。
    // 每页只过一个会话/一张图，避免一次性把几十 MB base64 塞进一个字符串。
    // ------------------------------------------------------------------

    @JavascriptInterface
    public void onMigrationIndex(String token, String json) {
        com.dbpg.duihuazhushou.data.MigrationCoordinator.INSTANCE
                .onIndex(token == null ? "" : token, json == null ? "{}" : json);
    }

    @JavascriptInterface
    public void onMigrationSession(String token, String id, String json) {
        com.dbpg.duihuazhushou.data.MigrationCoordinator.INSTANCE
                .onSession(token == null ? "" : token, id == null ? "" : id, json == null ? "" : json);
    }

    @JavascriptInterface
    public void onMigrationCharacter(String token, String uid, String json) {
        com.dbpg.duihuazhushou.data.MigrationCoordinator.INSTANCE
                .onCharacter(token == null ? "" : token, uid == null ? "" : uid, json == null ? "" : json);
    }

    @JavascriptInterface
    public void onMigrationAsset(String token, String id, String dataUrl) {
        com.dbpg.duihuazhushou.data.MigrationCoordinator.INSTANCE
                .onAsset(token == null ? "" : token, id == null ? "" : id, dataUrl == null ? "" : dataUrl);
    }

    // ------------------------------------------------------------------
    // 世界书 / 正则组：原生权威存储（2026-11-09）
    //
    // 引擎启动时调 getNativeAssets() 拿快照（同步，JS 线程直接用）；
    // 之后每次 assets.save() 通过 onAssetsChanged(json) 回传，
    // 由 NativeAssetsStore 原子落盘到 filesDir/data/assets/assets.json。
    // 归一化仍由网页侧完成，这里只存不解释 —— 提示词组装不受影响。
    // ------------------------------------------------------------------

    @JavascriptInterface
    public String getNativeAssets() {
        return com.dbpg.duihuazhushou.data.NativeAssetsStore.get(context);
    }

    @JavascriptInterface
    public void onAssetsChanged(String json) {
        com.dbpg.duihuazhushou.data.NativeAssetsStore.save(context, json);
    }

    // ------------------------------------------------------------------
    // 生成通道回调（web ST 引擎 → 原生聊天界面）
    //
    // 原生把"当前版本的对话"交给 web 组装并发请求，web 边收边把**全文**
    // （不是增量，已节流到 80ms 一次）回传过来。原生直接整段替换，
    // 不需要自己拼接，也就不会因为丢块导致文字错位。
    // ------------------------------------------------------------------

    @JavascriptInterface
    public void onGenDelta(String requestId, String text) {
        com.dbpg.duihuazhushou.chat.GenBridge.INSTANCE
                .onGenDelta(requestId == null ? "" : requestId, text == null ? "" : text);
    }

    @JavascriptInterface
    public void onGenDone(String requestId, String full) {
        com.dbpg.duihuazhushou.chat.GenBridge.INSTANCE
                .onGenDone(requestId == null ? "" : requestId, full == null ? "" : full);
    }

    @JavascriptInterface
    public void onGenError(String requestId, String message) {
        com.dbpg.duihuazhushou.chat.GenBridge.INSTANCE
                .onGenError(requestId == null ? "" : requestId, message == null ? "" : message);
    }

    @JavascriptInterface
    public void onGenStopped(String requestId) {
        com.dbpg.duihuazhushou.chat.GenBridge.INSTANCE
                .onGenStopped(requestId == null ? "" : requestId);
    }

    // ------------------------------------------------------------------
    // API 配置桥的结果回传（web apiConfig store → 原生设置页）
    //
    // 原生设置页调 window.__dhpApi.saveConfig / fetchModels（异步），
    // web 处理完调这里把结果送回。监听是一次性的：设置页 onResume 注册、
    // onPause 注销，别的页面注册前收到结果直接丢弃。
    // ------------------------------------------------------------------

    /** 原生设置页等待 web 异步结果的回调。 */
    public interface ApiResultListener {
        void onApiResult(String json);
    }

    private static volatile ApiResultListener sApiListener;

    /** 设置页进入前台时注册，退到后台注销（见 ApiConfigActivity）。 */
    public static void setApiResultListener(ApiResultListener l) {
        sApiListener = l;
    }

    /**
     * 本地配置命令执行完后主动广播一条结果。
     *
     * 配置改成原生权威源之后，写命令不再等引擎回包；但设置页原本是靠
     * onApiResult(kind=命令名) 触发 loadConfig()/load() 的。这里复用同一条
     * 通道发一份本地结果，页面业务逻辑一行都不用改，也不会出现「改了不刷新」。
     */
    public static void fireLocal(String json) {
        ApiResultListener l = sApiListener;
        if (l == null) return;
        final String payload = json == null ? "{}" : json;
        MainActivity.runOnUi(() -> {
            ApiResultListener cur = sApiListener;
            if (cur != null) cur.onApiResult(payload);
        });
    }

    @JavascriptInterface
    public void onApiResult(String json) {
        fireLocal(json);
    }
}

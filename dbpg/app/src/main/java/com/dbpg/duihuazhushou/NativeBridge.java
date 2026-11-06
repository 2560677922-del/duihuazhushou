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
     * 由网页入口按钮调用：{@code AndroidNative.openNativeChat(JSON.stringify(snapshot))}。
     *
     * @param snapshotJson 网页 window.__dhpNative.getSnapshot() 序列化的会话 + 显示配置
     */
    @JavascriptInterface
    public void openNativeChat(String snapshotJson) {
        try {
            Intent intent = new Intent(context, NativeChatActivity.class);
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
}

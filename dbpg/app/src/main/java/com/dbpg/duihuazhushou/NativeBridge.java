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
}

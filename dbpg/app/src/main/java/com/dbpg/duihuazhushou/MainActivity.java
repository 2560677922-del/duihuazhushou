package com.dbpg.duihuazhushou;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 唯一的 Activity：一个全屏 WebView 加载打包在 APK 里的 Web 版本。
 *
 * <p>页面来源：{@code https://dbpg.local/index.html}。域名是虚构的，请求在
 * {@link AppWebViewClient#shouldInterceptRequest} 里被拦下来，直接读
 * {@code assets/www/} 下的文件返回，全程不联网。
 *
 * <p>为什么用一个"假的 https 域名"而不是 {@code file:///android_asset/...}：
 * <ul>
 *   <li>Vite 的 base 是 {@code /}，产物里全是 {@code /assets/xxx.js} 这种绝对路径，
 *       必须有真正的域名根才能对上；</li>
 *   <li>{@code file://} 的 origin 是 opaque，localStorage / IndexedDB（localforage）
 *       会直接失效，配置和素材全丢；</li>
 *   <li>https origin 下 import map、同域模块脚本都正常。</li>
 * </ul>
 *
 * <p>这个类只做三件 Web 工程自己做不了的事：
 * <ol>
 *   <li>注入 {@link FileBridge}，让手机端的文件落盘真正生效；</li>
 *   <li>接住 {@code <input type="file">}，弹出系统文件选择器；</li>
 *   <li>接住下载，把 data: URL 图片存进 App 的 Download 目录。</li>
 * </ol>
 */
public final class MainActivity extends Activity {

    private static final String TAG = "DHP";

    /** 应用自己的虚构域名。assets/www/ 被映射到这个域名的根。 */
    private static final String HOST = "dbpg.local";
    private static final String APP_URL = "https://" + HOST + "/index.html";
    /** assets 里 Web 资源的子目录 */
    private static final String ASSETS_WEB = "www";

    private static final int REQ_PICK_FILE = 1001;
    private static final int BG_COLOR = 0xFF0F1115; // 与 Web 版主题色一致，避免启动闪白

    private WebView webView;
    private TextView errorView;
    private android.webkit.ValueCallback<Uri[]> pendingPick;
    private long lastBackPress = 0;

    /**
     * 让其它组件（原生聊天界面）能拿到当前 WebView，把消息/快照回传给网页侧。
     * 应用只有一个 WebView，背景化时也不会被销毁，所以持一份静态引用是安全的。
     */
    private static WebView sWebView;
    public static WebView getWebView() { return sWebView; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BG_COLOR);
        getWindow().setNavigationBarColor(BG_COLOR);

        FrameLayout root = new FrameLayout(this);
        FrameLayout.LayoutParams fill = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        // 出错提示层，平时不可见
        errorView = new TextView(this);
        errorView.setVisibility(View.GONE);
        errorView.setGravity(Gravity.CENTER);
        errorView.setBackgroundColor(BG_COLOR);
        errorView.setTextColor(0xFFFFFFFF);
        errorView.setPadding(64, 64, 64, 64);
        errorView.setLineSpacing(0, 1.3f);
        errorView.setText("应用加载失败\n\n点一下重试");
        errorView.setOnClickListener(v -> loadApp());
        root.addView(errorView, fill);

        webView = createWebView();
        root.addView(webView, fill);

        setContentView(root);
        loadApp();
    }

    private WebView createWebView() {
        // 打开 WebView 调试：手机连电脑后访问 chrome://inspect 就能看到页面控制台。
        // 本项目只发 debug 签名包自用，不做混淆和上架，所以这里直接开。
        WebView.setWebContentsDebuggingEnabled(true);

        WebView w = new WebView(this);
        w.setBackgroundColor(BG_COLOR);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // localStorage / IndexedDB 必须开，localforage 靠它
        s.setDatabaseEnabled(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);
        s.setSaveFormData(true);

        /*
         * 允许 https 页面里加载 http 资源（混合内容）。
         *
         * 为什么必须开：
         *   本应用的页面来源是 https://dbpg.local（见 APP_URL），
         *   而用户自己的模型服务常常是局域网里的 http:// —— 比如
         *   http://192.168.137.1:8899（另一台电脑开的出图服务）。
         *   Chromium 默认把这种请求当"混合内容"**直接拒掉**，
         *   表现就是：本地 127.0.0.1 的服务能用（localhost 属于可信来源，
         *   不受这条限制）、第三方 https 平台也能用，
         *   唯独局域网里那台 http 服务连不上。
         *
         *   实测现象与此完全一致，所以这里放开。
         *   ALWAYS_ALLOW 比 COMPATIBILITY_MODE 更彻底：
         *   后者只放行图片/音视频，对 fetch() 的 XHR/JSON 请求仍然拦截，
         *   而现在要放行的正是 fetch 调用。
         *
         * 安全性：WebView 只加载本地 assets 里的页面（shouldInterceptRequest
         * 拦掉了 dbpg.local 的所有请求），不存在加载外部恶意页面的入口，
         * 放开这条的风险仅限于我们自己发出去的 API 请求。
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        w.setVerticalScrollBarEnabled(true);
        w.setHorizontalScrollBarEnabled(false);
        w.setWebViewClient(new AppWebViewClient());
        w.setWebChromeClient(new AppWebChromeClient());
        w.setDownloadListener(new AppDownloadListener());
        // 把 WebView 一起传进去：出图这类慢请求要靠它回调结果（见 FileBridge.httpAsync）
        w.addJavascriptInterface(new FileBridge(this, w), "dhp");
        // 原生聊天桥：网页侧通过 window.AndroidNative 打开原生聊天界面，
        // 原生界面再通过这份 WebView 把消息/快照回传网页（复用既有生成逻辑）。
        w.addJavascriptInterface(new NativeBridge(this), "AndroidNative");
        sWebView = w;
        return w;
    }

    private void loadApp() {
        errorView.setVisibility(View.GONE);
        webView.loadUrl(APP_URL);
    }

    private void showError(String text) {
        errorView.setText(text);
        errorView.setVisibility(View.VISIBLE);
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Throwable t) {
            Log.w(TAG, "打开外部链接失败：" + uri, t);
        }
    }

    // ------------------------------------------------------------ 导航与资源

    private final class AppWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost();

            // 应用自己的导航（静态资源请求由 shouldInterceptRequest 处理）
            if (scheme.equals("https") && HOST.equals(host)) return false;
            if (scheme.equals("file") || scheme.equals("data") || scheme.equals("blob")
                    || scheme.equals("about")) {
                return false;
            }
            // 其它外部链接（GitHub 导入、AI 文档等）交给系统浏览器
            if (scheme.equals("http") || scheme.equals("https")) {
                openExternally(uri);
                return true;
            }
            return true;
        }

        /**
         * 把 {@code https://dbpg.local/xxx} 拦下来，直接返回 assets/www/xxx 的内容。
         * 返回 null 表示"我不处理"，Chromium 就会真的去联网。
         */
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || !scheme.equals("https") || !HOST.equals(host)) return null;

            String path = uri.getPath();
            if (path == null || path.isEmpty() || path.equals("/")) path = "/index.html";
            if (!path.startsWith("/")) return null;
            path = path.substring(1);
            if (path.contains("..") || path.startsWith("/")) return null;   // 拒绝目录穿越

            InputStream in = null;
            try {
                in = getAssets().open(ASSETS_WEB + "/" + path);
            } catch (IOException ignored) {
                return null;   // 没有这个文件，交给 Chromium 报错
            }
            return new WebResourceResponse(guessMimeType(path), "UTF-8", in);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            errorView.setVisibility(View.GONE);
            view.scrollTo(0, 0);
            /*
             * 页面加载完 → 把 windowBackground 从"启动图"换回纯色。
             *
             * 不换的话启动图会一直铺在 WebView 底下，界面滚动到边缘
             * （比如下拉回弹、内容不足一屏）时就会露出来，看着像残影。
             */
            try {
                getWindow().setBackgroundDrawableResource(R.color.app_bg);
                view.setBackgroundColor(BG_COLOR);
            } catch (Throwable t) {
                Log.w(TAG, "切换启动主题失败", t);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            String desc = error.getDescription() == null
                    ? String.valueOf(error.getErrorCode())
                    : error.getDescription().toString();
            Log.e(TAG, "主框架加载失败: " + desc);
            showError("应用加载失败：" + desc + "\n\n通常是 Web 版本还没构建，\n"
                    + "请在上一级目录执行 npm run build，再重新打包。\n\n点一下重试");
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() >= 400) {
                showError("应用加载失败：HTTP " + response.getStatusCode() + "\n\n点一下重试");
            }
        }
    }

    // ------------------------------------------------------------ 文件选择器

    private final class AppWebChromeClient extends WebChromeClient {

        @Override
        public boolean onShowFileChooser(WebView webView,
                                         android.webkit.ValueCallback<Uri[]> filePathCallback,
                                         WebChromeClient.FileChooserParams fileChooserParams) {
            if (pendingPick != null) pendingPick.onReceiveValue(null);
            pendingPick = filePathCallback;
            try {
                Intent intent = fileChooserParams.createIntent();
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(resolveAcceptType(fileChooserParams.getAcceptTypes()));
                startActivityForResult(intent, REQ_PICK_FILE);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "拉起文件选择器失败", t);
                pendingPick.onReceiveValue(null);
                pendingPick = null;
                return false;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK_FILE) return;
        super.onActivityResult(requestCode, resultCode, data);

        android.webkit.ValueCallback<Uri[]> cb = pendingPick;
        pendingPick = null;
        if (cb == null) return;

        if (resultCode != Activity.RESULT_OK || data == null) {
            cb.onReceiveValue(null);
            return;
        }
        try {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
            } else {
                Uri u = data.getData();
                if (u != null) uris.add(u);
            }
            if (uris.isEmpty()) {
                cb.onReceiveValue(null);
                return;
            }
            cb.onReceiveValue(uris.toArray(new Uri[0]));
        } catch (Throwable t) {
            Log.w(TAG, "读取所选文件失败", t);
            cb.onReceiveValue(null);
        }
    }

    // ---------------------------------------------------------------- 下载

    private final class AppDownloadListener implements DownloadListener {

        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
            if (url == null) return;
            try {
                // data: URL 的图片下载 —— 存到 App 的 Download 目录
                if (url.startsWith("data:")) {
                    String name = nameFromDisposition(contentDisposition, mimeType);
                    String saved = FileBridge.saveDataUrl(getApplicationContext(), name, url);
                    Toast.makeText(MainActivity.this,
                            saved != null ? "已保存到 " + saved : "保存失败", Toast.LENGTH_LONG).show();
                    return;
                }
                // 普通 http(s) 链接交给系统处理
                openExternally(Uri.parse(url));
            } catch (Throwable t) {
                Log.w(TAG, "下载处理失败", t);
            }
        }
    }

    // -------------------------------------------------------------- 按键返回

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPress < 2000) {
            super.onBackPressed();
            return;
        }
        lastBackPress = now;
        Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ 工具

    /** 猜 MIME 类型。JS 模块必须返回 JS 类型，否则 Chromium 会拒绝执行。 */
    static String guessMimeType(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);

        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".txt") || p.endsWith(".md")) return "text/plain";
        if (p.endsWith(".csv")) return "text/csv";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".ttf") || p.endsWith(".otf")) return "font/ttf";
        if (p.endsWith(".mp4")) return "video/mp4";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }

    /** 把 CSS accept 列表（如 ".json" / "image/*"）换算成 Android 能识别的单个 MIME 类型 */
    private static String resolveAcceptType(String[] accept) {
        if (accept == null || accept.length == 0) return "*/*";
        Set<String> mimes = new LinkedHashSet<>();
        for (String raw : accept) {
            if (raw == null) continue;
            String s = raw.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            if (s.equals("*/*") || s.equals("*") || s.equals(".")) return "*/*";
            if (s.startsWith(".")) {
                String m = extToMime(s.substring(1));
                if (m == null) return "*/*";   // 认不出扩展名就放开让用户自己挑
                mimes.add(m);
            } else {
                mimes.add(s);
            }
        }
        if (mimes.isEmpty()) return "*/*";
        return mimes.size() == 1 ? mimes.iterator().next() : "*/*";
    }

    private static String extToMime(String ext) {
        switch (ext) {
            case "json": return "application/json";
            case "js":
            case "mjs":
            case "ts": return "text/javascript";
            case "txt": return "text/plain";
            case "csv": return "text/csv";
            case "md": return "text/markdown";
            case "png": return "image/png";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "svg": return "image/svg+xml";
            case "bmp": return "image/bmp";
            case "heic":
            case "heif": return "image/heif";
            case "pdf": return "application/pdf";
            case "zip": return "application/zip";
            default: return null;
        }
    }

    private static String nameFromDisposition(String contentDisposition, String mimeType) {
        if (contentDisposition != null) {
            for (String part : contentDisposition.split(";")) {
                String lower = part.trim().toLowerCase(Locale.ROOT);
                if (lower.startsWith("filename")) {
                    int eq = part.indexOf('=');
                    if (eq < 0) continue;
                    String v = part.substring(eq + 1).trim();
                    if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                        v = v.substring(1, v.length() - 1);
                    }
                    if (!v.isEmpty()) return v;
                }
            }
        }
        String ext = "bin";
        if (mimeType != null) {
            String m = mimeType.toLowerCase(Locale.ROOT);
            if (m.startsWith("image/png")) ext = "png";
            else if (m.startsWith("image/jpeg")) ext = "jpg";
            else if (m.startsWith("image/webp")) ext = "webp";
            else if (m.startsWith("image/gif")) ext = "gif";
            else if (m.startsWith("image/svg")) ext = "svg";
            else if (m.startsWith("text/plain")) ext = "txt";
            else if (m.startsWith("application/json")) ext = "json";
        }
        return "download-" + System.currentTimeMillis() + "." + ext;
    }
}

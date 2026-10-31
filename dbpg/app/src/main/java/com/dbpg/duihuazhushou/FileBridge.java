package com.dbpg.duihuazhushou;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 暴露给 JS 的原生文件能力，挂在 {@code window.dhp} 上（见 {@link MainActivity}）。
 *
 * <p>为什么需要它：Web 工程里 {@code src/lib/persistFile.ts} 用的是 Capacitor 的
 * {@code @capacitor/filesystem} 插件。这个项目不想引入 Capacitor 依赖，所以这里提供
 * 一个兼容层：JS 侧由 {@code assets/vendor/capacitor-compat.js} 把
 * {@code import("@capacitor/filesystem")} 映射过来，再把调用转发到这个类。
 *
 * <p>调用方式：JS 只调一个入口 {@code window.dhp.api(method, JSON字符串)}，
 * 返回 {@code {"ok":true,"value":{...}}} 或 {@code {"ok":false,"error":"..."}}。
 *
 * <p>安全：所有路径都是相对于目录根（默认 App 私有 filesDir）的相对路径，
 * 带 {@code ..} 越界的会被拒绝。
 */
public final class FileBridge {

    private final Context ctx;

    /**
     * 后台线程池。
     *
     * 为什么单独开池而不是用 AsyncTask：AsyncTask 早已废弃，
     * 而且它默认串行；这里希望"同时点几个操作互不阻塞"。
     * 固定 4 个线程够用 —— 同时开 4 个出图请求已经不是正常用法了。
     */
    private static final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newFixedThreadPool(4);

    /** 弱引用持有 WebView，用来把异步结果 evaluateJavascript 回去 */
    private final java.lang.ref.WeakReference<WebView> webViewRef;

    /**
     * @param ctx 用于文件与网络
     * @param webView 用来回填异步结果（可为 null —— 只做文件操作时不需要）
     */
    public FileBridge(Context ctx) {
        this(ctx, null);
    }

    public FileBridge(Context ctx, WebView webView) {
        this.ctx = ctx.getApplicationContext();
        this.webViewRef = new java.lang.ref.WeakReference<>(webView);
    }

    /** JS 侧唯一入口。必须是实例方法（@JavascriptInterface 的要求），内部转给静态 {@link #handle}。 */
    @JavascriptInterface
    public String api(String method, String payloadJson) {
        return handle(ctx, method, payloadJson);
    }

    /**
     * 异步版 HTTP —— **出图必须走这个**。
     *
     * <p>为什么必须异步（用户报的"本地部署生图时整个界面卡死"）：
     * {@code @JavascriptInterface} 是**同步**的：Java 方法返回之前，
     * WebView 的 JS 线程一直被占着。文件读写几十毫秒无所谓，
     * 但出图要 20~40 秒 —— 这期间 JS 完全停摆，界面就是一具死尸
     * （转圈不动、按钮点不了）。第三方平台走的是普通 fetch，天生异步，
     * 所以从来不会卡。这就是"别的接口没事、只有本地部署卡"的原因。
     *
     * <p>做法：立刻返回一个请求 id，真正的请求丢到后台线程池里跑，
     * 跑完用 {@code evaluateJavascript} 回调
     * {@code window.__dhpHttpDone(id, ok, status, bodyOrError)}。
     * JS 侧包成 Promise，和 fetch 一样 await。
     *
     * <p>注意 body 可能很大（base64 图几百 KB），用 JSONObject.quote 转义后
     * 直接内联进 JS 字符串 —— 不经过 String.format 免得被 % 搞坏。
     */
    @JavascriptInterface
    public void httpAsync(final String reqId, final String method, final String url,
                          final String headersJson, final String body, final int timeoutMs) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                String okFlag = "false";
                String status = "0";
                String payload = "";
                try {
                    JSONObject p = new JSONObject();
                    p.put("url", url);
                    p.put("body", body);
                    p.put("timeoutMs", timeoutMs);
                    if (headersJson != null && !headersJson.isEmpty()) {
                        p.put("headers", new JSONObject(headersJson));
                    }
                    String raw = handle(ctx, "POST".equalsIgnoreCase(method) ? "httpPost" : "httpGet", p.toString());
                    JSONObject res = new JSONObject(raw);
                    if (res.optBoolean("ok")) {
                        JSONObject v = res.optJSONObject("value");
                        if (v != null) {
                            okFlag = "true";
                            status = String.valueOf(v.optInt("status", 0));
                            payload = v.optString("body", "");
                        } else {
                            payload = "响应缺少 value";
                        }
                    } else {
                        payload = res.optString("error", "请求失败");
                    }
                } catch (Throwable t) {
                    payload = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                deliver(reqId, "true".equals(okFlag), status, payload);
            }
        });
    }

    /**
     * 把结果送回 JS。必须在 UI 线程调 evaluateJavascript。
     *
     * <p>payload 用 JSONObject.quote 转义（它会把引号、换行、反斜杠都处理好），
     * 这样几百 KB 的 base64 也能安全地内联成一个 JS 字符串字面量。
     */
    private void deliver(final String reqId, final boolean ok, final String status, final String payload) {
        final WebView wv = webViewRef.get();
        if (wv == null) return;
        final String js = "window.__dhpHttpDone && window.__dhpHttpDone("
                + JSONObject.quote(reqId) + ","
                + ok + ","
                + status + ","
                + JSONObject.quote(payload) + ")";
        wv.post(new Runnable() {
            @Override
            public void run() {
                try {
                    wv.evaluateJavascript(js, null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 给 {@link MainActivity} 的下载监听复用：保存 data: URL 到 Download/，成功返回相对路径。 */
    public static String saveDataUrl(Context ctx, String name, String dataUrl) {
        try {
            JSONObject p = new JSONObject();
            p.put("name", name);
            p.put("dataUrl", dataUrl);
            return extractPath(handle(ctx, "saveDataUrl", p.toString()));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把一张图片写进系统相册（MediaStore）。
     *
     * <p>为什么用 MediaStore 而不是直接往 Pictures/ 写文件：
     * <ul>
     *   <li>Android 10+ 的分区存储不允许随便往公共目录写文件；</li>
     *   <li>MediaStore 是官方推荐的"放进相册"方式，**不需要任何权限**；</li>
     *   <li>写进去之后系统媒体库会立刻知道这张图，相册里马上能看到。</li>
     * </ul>
     *
     * <p>返回一个可读的路径说明（失败返回 null，由调用方退回私有目录）。
     */
    static String saveImageToGallery(Context ctx, String name, byte[] bytes) {
        // Android 10（API 29）起才有分区存储的一套常量
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return null; // 老系统走原来的私有目录逻辑，避免又要申请权限
        }
        android.content.ContentResolver cr = ctx.getContentResolver();
        android.content.ContentValues cv = new android.content.ContentValues();
        String subDir = "Pictures/" + appLabel(ctx);
        cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeOf(name));
        cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, subDir);
        cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);

        Uri item = null;
        OutputStream out = null;
        try {
            item = cr.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (item == null) return null;
            out = cr.openOutputStream(item);
            if (out == null) return null;
            out.write(bytes);
            out.flush();

            // 清除 pending 标记 —— 不做这一步图还不会出现在相册里
            android.content.ContentValues done = new android.content.ContentValues();
            done.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(item, done, null, null);
            return subDir + "/" + name;
        } catch (Throwable t) {
            // 失败就把刚插入的半成品删掉，别留垃圾
            try {
                if (item != null) cr.delete(item, null, null);
            } catch (Throwable ignored) {
                /* 清理失败无所谓 */
            }
            return null;
        } finally {
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
                /* 关闭失败无所谓 */
            }
        }
    }

    /** 应用显示名，用来做相册子目录（取不到就用包名的末段） */
    private static String appLabel(Context ctx) {
        try {
            android.content.pm.ApplicationInfo ai = ctx.getApplicationInfo();
            CharSequence label = ctx.getPackageManager().getApplicationLabel(ai);
            if (label != null && label.length() > 0) {
                String s = label.toString().trim();
                // 去掉文件系统不喜欢的字符
                s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (!s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {
            /* 取不到就走下面的兜底 */
        }
        return "AppImages";
    }

    /** 按扩展名给 MIME，相册靠它归类 */
    private static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".bmp")) return "image/bmp";
        return "image/png";
    }

    // ------------------------------------------------------------------ 入口

    static String handle(Context ctx, String method, String payloadJson) {
        try {
            JSONObject p = new JSONObject(payloadJson == null || payloadJson.isEmpty() ? "{}" : payloadJson);
            String directory = optString(p, "directory", "DATA");
            File base = baseDir(ctx, directory);

            switch (method == null ? "" : method) {

                case "mkdir": {
                    File t = resolve(base, optString(p, "path", null));
                    if (!t.exists() && !t.mkdirs()) throw new IOException("无法创建目录：" + optString(p, "path", ""));
                    return ok();
                }

                /*
                 * HTTP 代理：绕过浏览器/WebView 的 CORS 与混合内容限制。
                 *
                 * 为什么需要：
                 *   用户的生图服务常常是自己局域网里跑的小服务
                 *   （比如 http://192.168.137.1:8899，FastAPI + ComfyUI）。
                 *   这类自建服务**默认不发 Access-Control-Allow-Origin**，
                 *   也不实现 OPTIONS 预检（实测返回 405）。
                 *   浏览器里 fetch 一定会报 "Failed to fetch"，WebView 同理 ——
                 *   而 RikkaHub 这类原生 App 不受 CORS 约束，所以它能连上。
                 *   这不是网络不通，是浏览器的同源策略。
                 *
                 *   解法：把请求交给原生发。原生 HTTP 没有"源"的概念，
                 *   既不会被 CORS 拦，也不受 https 页面发 http 请求的限制。
                 *
                 * 参数：{ url, headers:{...}, body:"...", timeoutMs }
                 * 返回：{ status, body } —— body 是原始响应文本（可能是 JSON 或 base64）
                 */
                case "httpPost": {
                    return httpRequest(optString(p, "url", null), "POST", p);
                }
                case "httpGet": {
                    return httpRequest(optString(p, "url", null), "GET", p);
                }

                case "writeFile": {
                    String data = optString(p, "data", "");
                    File t = resolve(base, optString(p, "path", null));
                    File parent = t.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("无法创建目录：" + parent);
                    }
                    writeBytes(t, data.getBytes(StandardCharsets.UTF_8));
                    return okValue(obj("uri", Uri.fromFile(t).toString()));
                }

                case "appendFile": {
                    String data = optString(p, "data", "");
                    File t = resolve(base, optString(p, "path", null));
                    File parent = t.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("无法创建目录：" + parent);
                    }
                    OutputStream os = new FileOutputStream(t, true);
                    try {
                        os.write(data.getBytes(StandardCharsets.UTF_8));
                    } finally {
                        os.close();
                    }
                    return okValue(obj("uri", Uri.fromFile(t).toString()));
                }

                case "readFile": {
                    File t = resolve(base, optString(p, "path", null));
                    if (!t.isFile()) throw new IOException("文件不存在：" + optString(p, "path", ""));
                    JSONObject v = new JSONObject();
                    v.put("data", new String(readBytes(t), StandardCharsets.UTF_8));
                    return okValue(v);
                }

                case "readdir": {
                    File t = resolve(base, optString(p, "path", ""));
                    File[] list = t.exists() ? t.listFiles() : null;
                    JSONArray files = new JSONArray();
                    if (list != null) {
                        for (File f : list) {
                            JSONObject o = new JSONObject();
                            o.put("name", f.getName());
                            o.put("size", f.length());
                            o.put("mtime", f.lastModified());
                            o.put("mode", f.isDirectory() ? "dir" : "file");
                            files.put(o);
                        }
                    }
                    JSONObject v = new JSONObject();
                    v.put("files", files);
                    return okValue(v);
                }

                case "stat": {
                    File t = resolve(base, optString(p, "path", null));
                    if (!t.exists()) throw new IOException("文件不存在：" + optString(p, "path", ""));
                    JSONObject v = new JSONObject();
                    v.put("size", t.length());
                    v.put("mtime", t.lastModified());
                    v.put("ctime", t.lastModified());
                    v.put("mode", t.isDirectory() ? "dir" : "file");
                    return okValue(v);
                }

                case "deleteFile": {
                    File t = resolve(base, optString(p, "path", null));
                    if (t.exists() && !t.delete()) throw new IOException("删除失败：" + optString(p, "path", ""));
                    return ok();
                }

                case "remove": {
                    File t = resolve(base, optString(p, "path", null));
                    if (t.exists() && !deleteRecursive(t)) throw new IOException("删除失败：" + optString(p, "path", ""));
                    return ok();
                }

                case "getUri": {
                    File t = resolve(base, optString(p, "path", ""));
                    return okValue(obj("uri", Uri.fromFile(t).toString()));
                }

                case "copyFile": {
                    File s = resolve(base, optString(p, "source", null));
                    File d = resolve(base, optString(p, "target", null));
                    if (!s.isFile()) throw new IOException("源文件不存在：" + optString(p, "source", ""));
                    File parent = d.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("无法创建目录：" + parent);
                    }
                    copyFile(s, d);
                    return ok();
                }

                case "moveFile": {
                    File s = resolve(base, optString(p, "source", null));
                    File d = resolve(base, optString(p, "target", null));
                    if (!s.exists()) throw new IOException("源不存在：" + optString(p, "source", ""));
                    File parent = d.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("无法创建目录：" + parent);
                    }
                    if (!s.renameTo(d)) {
                        copyFile(s, d);
                        if (s.isFile()) s.delete();
                    }
                    return ok();
                }

                case "saveDataUrl": {
                    String name = sanitizeName(optString(p, "name", "download.bin"));
                    String dataUrl = optString(p, "dataUrl", "");
                    if (dataUrl.isEmpty()) throw new IOException("dataUrl 为空");
                    byte[] bytes = decodeDataUrl(dataUrl);

                    /*
                     * 图片优先存进**系统相册**。
                     *
                     * 以前存到 App 私有的 filesDir/Download —— 相册扫不到、
                     * 文件管理器也看不见，用户以为"下载没成功"（用户反馈过这个问题）。
                     *
                     * 现在图片走系统媒体库（MediaStore）：
                     *   · 落进 Pictures/<应用名>/，相册立刻能看到
                     *   · Android 10+ 不需要任何存储权限
                     *   · 失败就退回原来的私有目录，功能不会整个坏掉
                     */
                    boolean isImage = name.toLowerCase(Locale.ROOT)
                            .matches(".*\\.(png|jpg|jpeg|webp|gif|bmp)$");
                    if (isImage) {
                        String galleryPath = saveImageToGallery(ctx, name, bytes);
                        if (galleryPath != null) {
                            JSONObject v = new JSONObject();
                            v.put("path", galleryPath);
                            v.put("uri", galleryPath);
                            v.put("size", bytes.length);
                            v.put("gallery", true);
                            return okValue(v);
                        }
                    }

                    File outDir = new File(base, "Download");
                    if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("无法创建下载目录");
                    File out = new File(outDir, name);
                    writeBytes(out, bytes);
                    JSONObject v = new JSONObject();
                    v.put("path", "Download/" + name);
                    v.put("uri", Uri.fromFile(out).toString());
                    v.put("size", out.length());
                    return okValue(v);
                }

                default:
                    return fail("不支持的方法：" + method);
            }
        } catch (Throwable t) {
            return fail(t.getMessage() == null ? String.valueOf(t) : t.getMessage());
        }
    }

    // ------------------------------------------------------------ 目录与路径

    /** directory 参数 → 真实目录。默认 DATA 就是 App 私有 filesDir。 */
    static File baseDir(Context ctx, String directory) {
        String d = directory == null ? "DATA" : directory.toUpperCase(Locale.ROOT);
        switch (d) {
            case "CACHE":
                return ctx.getCacheDir();
            case "DOCUMENTS":
            case "EXTERNAL_DOCUMENTS": {
                File f = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                return f != null ? f : ctx.getFilesDir();
            }
            case "EXTERNAL_STORAGE": {
                File f = ctx.getExternalFilesDir(null);
                return f != null ? f : ctx.getFilesDir();
            }
            case "EXTERNAL_DOWNLOADS": {
                File f = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                return f != null ? f : new File(ctx.getFilesDir(), "Download");
            }
            default:
                return ctx.getFilesDir(); // "DATA"
        }
    }

    /** 把相对路径安全地解析到 base 之内，拒绝绝对路径与 .. 越界。 */
    static File resolve(File base, String path) throws IOException {
        String p = path == null ? "" : path.trim();
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) return base;
        if (p.matches("^[a-zA-Z]:.*")) throw new IOException("不允许绝对路径：" + path);

        File target = new File(base, p);
        String baseCanonical = base.getCanonicalPath();
        String targetCanonical = target.getCanonicalPath();
        if (!targetCanonical.equals(baseCanonical)
                && !targetCanonical.startsWith(baseCanonical + File.separator)) {
            throw new IOException("路径越界：" + path);
        }
        return target;
    }

    static String sanitizeName(String name) {
        if (name == null) return "download.bin";
        String s = name.replace('\\', '/');
        int i = s.lastIndexOf('/');
        if (i >= 0) s = s.substring(i + 1);
        s = s.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f]", "_").trim();
        if (s.isEmpty() || s.equals(".") || s.equals("..")) s = "download.bin";
        return s.length() > 120 ? s.substring(s.length() - 120) : s;
    }

    /** 解析 data: URL（支持 base64 与普通编码） */
    static byte[] decodeDataUrl(String dataUrl) throws IOException {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) throw new IOException("不是有效的 data URL");
        String head = dataUrl.substring(5, comma); // 去掉 "data:"
        String body = dataUrl.substring(comma + 1);
        if (head.endsWith(";base64")) {
            byte[] decoded = Base64.decode(body.replaceAll("\\s", ""), Base64.DEFAULT);
            if (decoded == null) throw new IOException("base64 解码失败");
            return decoded;
        }
        try {
            return URLDecoder.decode(body, "UTF-8").getBytes(StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return body.getBytes(StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ 工具

    private static void writeBytes(File f, byte[] data) throws IOException {
        OutputStream os = new FileOutputStream(f);
        try {
            os.write(data);
        } finally {
            os.close();
        }
    }

    private static byte[] readBytes(File f) throws IOException {
        InputStream is = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) Math.max(1, f.length())];
            int off = 0, n;
            while (off < buf.length && (n = is.read(buf, off, buf.length - off)) != -1) off += n;
            byte[] out = new byte[off];
            System.arraycopy(buf, 0, out, 0, off);
            return out;
        } finally {
            is.close();
        }
    }

    private static void copyFile(File s, File d) throws IOException {
        InputStream is = new FileInputStream(s);
        OutputStream os = new FileOutputStream(d);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
        } finally {
            is.close();
            os.close();
        }
    }

    private static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) deleteRecursive(x);
        }
        return !f.exists() || f.delete();
    }

    private static String extractPath(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!o.optBoolean("ok", false)) return null;
            JSONObject v = o.optJSONObject("value");
            return v == null ? null : v.optString("path", null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 原生发一个 HTTP 请求，把响应原样带回来。
     *
     * <p>存在的唯一目的：绕开浏览器的同源策略。自建的局域网服务
     * （FastAPI / ComfyUI 那类）通常不发 CORS 头、也不实现 OPTIONS 预检，
     * 网页里 fetch 必然 "Failed to fetch"；改由原生发就没有这个限制，
     * 同时也不受"https 页面不能请求 http"的约束（见 MainActivity 里
     * setMixedContentMode 的说明）。
     *
     * <p>用 HttpURLConnection 而不是引入 OkHttp：这个桥只做几件小事，
     * 不值得为它加一个依赖、把包体撑大。
     */
    private static String httpRequest(String url, String method, JSONObject p) {
        if (url == null || url.isEmpty()) return fail("缺少 url");
        HttpURLConnection conn = null;
        try {
            /*
             * 跟随重定向要手动处理：HttpURLConnection 对 POST 的 3xx
             * 默认不会自动跟，而有些反代会先 302 一下。
             */
            int timeout = p == null ? 180000 : p.optInt("timeoutMs", 180000);
            String body = optString(p, "body", null);

            for (int hop = 0; hop < 4; hop++) {
                URL u = new URL(url);
                conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(15000);      // 连不上要快点报错，别让用户干等
                conn.setReadTimeout(timeout);       // 出图很慢，读超时要给足
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("Accept", "application/json, image/*, */*");

                JSONObject headers = p == null ? null : p.optJSONObject("headers");
                if (headers != null) {
                    java.util.Iterator<String> it = headers.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        conn.setRequestProperty(k, headers.optString(k, ""));
                    }
                }

                if (body != null && !"GET".equals(method)) {
                    conn.setDoOutput(true);
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    conn.setFixedLengthStreamingMode(bytes.length);
                    OutputStream os = conn.getOutputStream();
                    try {
                        os.write(bytes);
                    } finally {
                        os.close();
                    }
                }

                int status = conn.getResponseCode();

                // 3xx → 跟着 Location 再发一次
                if (status >= 300 && status < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (loc == null || loc.isEmpty()) return fail("重定向缺少 Location");
                    url = new URL(new URL(url), loc).toString();
                    continue;
                }

                InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String text = in == null ? "" : readAll(in);

                JSONObject value = new JSONObject();
                value.put("status", status);
                value.put("body", text);
                return okValue(value);
            }
            return fail("重定向次数过多");
        } catch (Throwable t) {
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            return fail("请求失败：" + msg);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 把一个流读完成字符串 */
    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String optString(JSONObject p, String key, String def) {
        if (p == null) return def;
        String v = p.optString(key, null);
        return v == null ? def : v;
    }

    private static JSONObject obj(String... kv) throws JSONException {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) o.put(kv[i], kv[i + 1]);
        return o;
    }

    private static String ok() {
        return "{\"ok\":true}";
    }

    private static String okValue(JSONObject value) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("value", value);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    private static String fail(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", message);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }
}

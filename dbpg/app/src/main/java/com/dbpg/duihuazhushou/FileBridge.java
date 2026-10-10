package com.dbpg.duihuazhushou;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    public FileBridge(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** JS 侧唯一入口。必须是实例方法（@JavascriptInterface 的要求），内部转给静态 {@link #handle}。 */
    @JavascriptInterface
    public String api(String method, String payloadJson) {
        return handle(ctx, method, payloadJson);
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

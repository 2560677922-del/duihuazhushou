/* ==========================================================================
 * capacitor-compat.js —— Android 原生桥接垫片
 * --------------------------------------------------------------------------
 * 这个文件只存在于打包后的 APK 里（assets/vendor/ 会被 syncWeb 复制进
 * assets/www/，再由 build.gradle.kts 的 patchWebIndex 任务注入到 index.html）。
 * Web 工程源码里没有任何关于它的引用，浏览器版本完全不受影响。
 *
 * 它解决三件事：
 *
 * 1. 让手机端的「落盘」真正生效
 *    src/lib/persist.ts 靠 `window.Capacitor.isNativePlatform()` 判断是否在
 *    原生 App 里；src/lib/persistFile.ts 再动态 import("@capacitor/filesystem")
 *    去拿 Filesystem 插件。这里把 window.Capacitor 建起来，并把那个裸导入用
 *    import map 映射成本文件，于是 App 私有目录里的 app-data.json 真的会被写出来，
 *    而不是退回可能被系统清理掉的 localStorage。
 *
 * 2. 补上 <a download> 在 WebView 里静默失败的问题
 *    「图片库」「场景配图」的下载按钮用的是 data: URL，Android WebView 不会真的
 *    保存。这里拦下来改走原生保存。
 *
 * 3. 剪贴板兜底
 *    部分 WebView 版本的 navigator.clipboard.writeText 会直接拒绝，退回
 *    execCommand('copy')。
 *
 * 加载方式：ES 模块（index.html 里以 <script type="module"> 加载，且排在应用
 * 主模块之前，document 顺序执行保证先就位）。
 * ========================================================================== */

const NATIVE = window.dhp; // MainActivity 通过 addJavascriptInterface 注入
const HAS_NATIVE = !!(NATIVE && typeof NATIVE.api === 'function');

function msg(e) {
  return e && e.message ? e.message : String(e);
}

/** 调一次原生方法，返回 Promise */
function call(method, payload) {
  return new Promise(function (resolve, reject) {
    var raw = null;
    try {
      raw = NATIVE.api(method, JSON.stringify(payload || {}));
    } catch (e) {
      reject(new Error('原生调用失败：' + msg(e)));
      return;
    }
    var out = null;
    try {
      out = JSON.parse(raw);
    } catch (e) {
      reject(new Error('原生返回无法解析'));
      return;
    }
    if (out && out.ok === true) {
      resolve(out.value === undefined ? {} : out.value);
    } else {
      reject(new Error(out && out.error ? out.error : '原生返回异常'));
    }
  });
}

/* ---------------------------------------------------------------- 目录常量
 * 值与 @capacitor/filesystem 的 Directory 枚举保持一致（大写字符串），
 * FileBridge.java 里按同样的字符串做映射。
 * ---------------------------------------------------------------- */
export const Directory = {
  Data: 'DATA',
  Documents: 'DOCUMENTS',
  ExternalStorage: 'EXTERNAL_STORAGE',
  ExternalDownloads: 'EXTERNAL_DOWNLOADS',
  ExternalDocuments: 'EXTERNAL_DOCUMENTS',
  Cache: 'CACHE',
};

/* -------------------------------------------------------------- 文件系统插件
 * persistFile.ts 用到的方法：mkdir / readFile / writeFile / readdir / deleteFile
 * 其余是 Capacitor 常见 API，一并补齐。
 * ---------------------------------------------------------------- */
export const Filesystem = {
  mkdir: (opts) => call('mkdir', opts),
  writeFile: (opts) => call('writeFile', opts),
  appendFile: (opts) => call('appendFile', opts),
  readFile: (opts) => call('readFile', opts),
  readdir: (opts) => call('readdir', opts),
  stat: (opts) => call('stat', opts),
  deleteFile: (opts) => call('deleteFile', opts),
  remove: (opts) => call('remove', opts),
  getUri: (opts) => call('getUri', opts),
  copyFile: (opts) => call('copyFile', opts),
  moveFile: (opts) => call('moveFile', opts),

  // Capacitor 早期版本的旧 API 名，留着以防以后用到
  makeDirectory: (opts) => call('mkdir', opts),
  write: (opts) => call('writeFile', opts),
  read: (opts) => call('readFile', opts),
  listDir: (opts) => call('readdir', opts),
  getSize: (opts) => call('stat', opts),
  removeFile: (opts) => call('deleteFile', opts),
};

/* --------------------------------------------------------- 挂到 window.Capacitor */
if (HAS_NATIVE && !window.Capacitor) {
  window.Capacitor = {
    isNativePlatform: () => true,
    getPlatform: () => 'android',
    getVersion: () => '0.1.0-dhp',
    Plugins: { Filesystem: Filesystem },
    Bridge: {
      call: (method, options) => call(method, options),
      getPlatform: () => 'android',
    },
  };
  console.info('[dhp] 原生桥接已启用');
} else if (!HAS_NATIVE) {
  console.warn('[dhp] 未检测到原生桥接（window.dhp），手机端文件存储将退回浏览器存储');
}

/* ------------------------------------------------- <a download> 走原生保存 */
if (HAS_NATIVE && window.HTMLAnchorElement) {
  const proto = window.HTMLAnchorElement.prototype;
  const origClick = proto.click;

  proto.click = function () {
    // a.download = 'x.png' 设的是 DOM 属性（会反射成 attribute），
    // 但直接写 a.download 时 hasAttribute 可能拿不到，所以两处都读一遍。
    const href = this.getAttribute('href') || this.href || '';
    const download = this.getAttribute('download') || this.download || '';
    if (download !== '' && href.indexOf('data:') === 0) {
      const name = download || 'download.bin';
      try {
        const raw = NATIVE.api(
          'saveDataUrl',
          JSON.stringify({ name: name, dataUrl: href }),
        );
        const r = JSON.parse(raw);
        if (r && r.ok === true) {
          // 图会进系统相册（原生侧用 MediaStore），告诉用户去哪找
          const where = (r.value && r.value.gallery) ? '相册' : (r.value && r.value.path);
          console.info('[dhp] 已保存到 ' + where);
          if (typeof window.__dhpOnSaved === 'function') {
            window.__dhpOnSaved(true, where, name);
          }
          return;
        }
        console.warn('[dhp] 保存失败：' + (r && r.error));
        if (typeof window.__dhpOnSaved === 'function') {
          window.__dhpOnSaved(false, (r && r.error) || '未知错误', name);
        }
      } catch (e) {
        console.warn('[dhp] 保存失败：' + msg(e));
        if (typeof window.__dhpOnSaved === 'function') {
          window.__dhpOnSaved(false, msg(e), name);
        }
      }
      // 走原生保存后不再触发默认动作（WebView 里默认动作也是静默失败）
      return;
    }
    return origClick.apply(this, arguments);
  };
}

/*
 * 给网页侧直接调用的保存入口。
 *
 * 为什么单独暴露一个函数、而不只靠 <a download> 劫持：
 *   ImageViewer 的"保存"按钮需要**知道结果**才能给用户一句准确的话
 *   （"已存到相册" / "保存失败：xxx"）。只劫持 click 拿不到返回值。
 *
 * 用法：const r = await window.dhpSaveImage(dataUrl, '名字.png')
 *       返回 { ok: true, where: '相册' } 或 { ok: false, error: '...' }
 */
if (HAS_NATIVE) {
  window.dhpSaveImage = function (dataUrl, name) {
    return new Promise(function (resolve) {
      try {
        const raw = NATIVE.api(
          'saveDataUrl',
          JSON.stringify({ name: name || 'image.png', dataUrl: dataUrl }),
        );
        const r = JSON.parse(raw);
        if (r && r.ok === true) {
          const where = (r.value && r.value.gallery) ? '相册' : ((r.value && r.value.path) || '本地');
          resolve({ ok: true, where: where, gallery: !!(r.value && r.value.gallery) });
        } else {
          resolve({ ok: false, error: (r && r.error) || '保存失败' });
        }
      } catch (e) {
        resolve({ ok: false, error: msg(e) });
      }
    });
  };
}

/* ------------------------------------------------------- 剪贴板兜底 */
if (
  window.navigator &&
  window.navigator.clipboard &&
  typeof window.navigator.clipboard.writeText === 'function'
) {
  const origWriteText = window.navigator.clipboard.writeText.bind(window.navigator.clipboard);
  try {
    Object.defineProperty(window.navigator.clipboard, 'writeText', {
      value: function (text) {
        return origWriteText(text).catch(function () {
          try {
            const ta = document.createElement('textarea');
            ta.value = text;
            ta.setAttribute('readonly', '');
            ta.style.position = 'fixed';
            ta.style.left = '-9999px';
            document.body.appendChild(ta);
            ta.select();
            const ok = document.execCommand('copy');
            document.body.removeChild(ta);
            if (!ok) throw new Error("execCommand('copy') 返回 false");
          } catch (e2) {
            throw new Error('剪贴板不可用：' + msg(e2));
          }
        });
      },
      configurable: true,
      writable: true,
    });
  } catch (e) {
    console.warn('[dhp] 剪贴板兜底未启用：' + msg(e));
  }
}

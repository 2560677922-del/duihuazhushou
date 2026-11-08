/*
 * 消息正文渲染运行时（render.js）。
 *
 * 职责：
 *   1. 把「跑完正则的原始文本」按 web 标准渲染出来
 *   2. 把内容高度回传给原生（原生据此设置 WebView 高度，列表才能正常滚动）
 *   3. 换主题（字号/行距/字体/颜色由原生注入，避免两套观感）
 *
 * ★ 为什么按「形态」分流，而不是一律 marked.parse
 *   角色扮演里同时存在三种内容：
 *     a) 纯 Markdown / 纯文本             —— *动作*、> 引用、``` 真代码块 ```
 *     b) HTML 片段                       —— <details>、<table>、<div class="card">
 *     c) 完整 HTML 文档                   —— <html><head><style><body>…，
 *        常由美化预设吐出，且多半被 ```html 代码围栏包住
 *   marked 只认 (a)(b)：遇到 (c) 会把整份文档当代码块，**转义成源码**显示出来
 *   （`<style>` 不生效、`@font-face` 不加载）—— 这正是「渲染没上去」的根因。
 *   浏览器渲染 (c) 的正确方式是把它当**独立文档**，所以这里用 iframe srcdoc。
 *
 * ★ 判定只依据 web 标准标记（<!DOCTYPE html / <html / <head），
 *   不列标签白名单，也不去猜某个预设用了什么正则。
 *
 * ★ 安全：iframe 带 sandbox="allow-scripts"（不给 allow-same-origin、
 *   不给 allow-top-navigation），里面跑的脚本在一个 opaque origin 里，
 *   碰不到宿主 WebView 的 DHPBridge、读不了 file://、跳不走整个气泡。
 */
(function () {
    'use strict';

    var root = document.getElementById('m');
    var lastH = -1;
    var timer = 0;
    var docFrames = [];

    /* ---------------------------------------------------------- 高度回传 */

    function measure() {
        if (!root) return;
        var h = Math.ceil(Math.max(root.scrollHeight, root.getBoundingClientRect().height));
        if (!(h > 0)) h = 1;
        var dpr = window.devicePixelRatio || 1;
        var px = Math.round(h * dpr);
        if (lastH > 0 && Math.abs(px - lastH) < 2) return;
        lastH = px;
        try {
            if (window.DHPBridge && DHPBridge.height) DHPBridge.height(px);
        } catch (e) { /* 页面已销毁 */ }
    }

    function schedule() {
        if (timer) return;
        timer = setTimeout(function () {
            timer = 0;
            measure();
        }, 24);
    }

    /* ------------------------------------------------------ 整页文档探测 */

    /* 围栏**内部**是不是一份文档：找文档标记即可（这里已经是独立的一块）。 */
    var DOC_MARK = /<!DOCTYPE\s+html|<html\b|<head\b/i;
    /* 整条消息本身是不是一份文档：必须从开头就是，不能用 contains 误判正文里提到的 <html> */
    var DOC_START = /^\s*(?:<!DOCTYPE\s+html[^>]*>\s*)?<html\b/i;
    /* 无语言标注的围栏还需要闭合标记才当文档：一行 `<html>示例` 是代码，不是文档 */
    var DOC_CLOSE = /<\/html\s*>|<\/head\s*>|<\/body\s*>/i;

    function isFullDoc(s) {
        return DOC_MARK.test(s || '');
    }

    /*
     * ``` 围栏（ST / Tavo 美化预设的通用约定：```html 块里放整页卡片）。
     * 只把「围栏内确实是整页文档」的块抽出来；其余围栏原样留给 marked，
     * 当作真正的代码块显示 —— 这是浏览器的标准语义，不能一刀切。
     *
     * ★ 判定随语言标注收紧：
     *   ```html —— 作者明确说是 HTML，含文档标记即可抽；
     *   无标注  —— 可能只是普通代码块里"提到"了 <html>（正文讲解标签用法
     *             是常见写法），必须从开头就是文档才抽，否则代码块被劫持。
     */
    var FENCE = /^[ \t]*```[ \t]*([a-zA-Z0-9_-]*)[ \t]*\r?\n([\s\S]*?)^[ \t]*```[ \t]*$/gm;

    function splitSegments(raw) {
        var segs = [], last = 0, m;
        FENCE.lastIndex = 0;
        while ((m = FENCE.exec(raw)) !== null) {
            var lang = (m[1] || '').toLowerCase();
            var body = m[2];
            var isDoc = lang === 'html' || lang === 'htm'
                ? isFullDoc(body)
                : DOC_START.test(body) && DOC_CLOSE.test(body);
            if (isDoc) {
                if (m.index > last) segs.push({ type: 'md', text: raw.slice(last, m.index) });
                segs.push({ type: 'doc', text: body });
                last = m.index + m[0].length;
            }
        }
        if (last < raw.length) segs.push({ type: 'md', text: raw.slice(last) });
        return segs;
    }

    function segmentsOf(raw) {
        /*
         * 先按围栏切。命中就返回分段结果 —— 围栏外的剧情/选项/摘要要和卡片
         * 拼在同一条消息里，不能丢。
         *
         * 没有围栏时，只有「整条消息开头就是 <html>/<!DOCTYPE html>」才当文档。
         * 不能用 contains：正文里聊到 `<html>` 标签、或后面还跟着剧情的
         * 混合消息，都会被误判成「整条是文档」，把围栏/正文塞进 srcdoc。
         */
        var segs = splitSegments(raw);
        for (var i = 0; i < segs.length; i++) {
            if (segs[i].type === 'doc') return segs;
        }
        if (DOC_START.test(raw)) return [{ type: 'doc', text: raw }];
        return [{ type: 'md', text: raw }];
    }

    /* ------------------------------------------------------------ 渲染 */

    function md(text) {
        var html;
        try {
            html = marked.parse(text == null ? '' : String(text), {
                gfm: true,
                breaks: true,        // 单换行保留 —— 角色扮演正文靠换行分段
                headerIds: false,
                mangle: false,
            });
        } catch (e) {
            html = null;
        }
        return (html == null || html === '') ? (text || '') : html;
    }

    /*
     * 注入到 iframe 文档末尾的高度探针。
     *
     * sandbox 不给 allow-same-origin，父页**读不到** iframe 的
     * contentDocument，所以高度只能由 iframe 自己 postMessage 报上来。
     * 预设作者常写自己的 updateIframeHeight()，但不一定有 —— 这段保证
     * 任何卡片都能自适应高度。它和模型脚本同处沙箱内，权限相同。
     */
    var PROBE = [
        '<script>(function(){',
        /*
         * 用 body 的自身高度，不要用 documentElement.scrollHeight：
         * 后者至少等于 iframe 当前视口高度（首帧占位 160px），内容折叠后
         * 永远缩不回去，卡片下方就会留一大块空白，把下面的正文挤开。
         */
        'function H(){var b=document.body,d=document.documentElement;',
        'var h=b?Math.max(b.scrollHeight,b.offsetHeight,b.getBoundingClientRect().height):0;',
        'if(!(h>0)&&d)h=d.scrollHeight;return h||0;}',
        'function R(){try{parent.postMessage({__dhpH:H()},"*");}catch(e){}}',
        'addEventListener("load",R);',
        'setTimeout(R,40);setTimeout(R,180);setTimeout(R,500);setTimeout(R,1200);',
        'if(document.fonts&&document.fonts.ready)document.fonts.ready.then(R);',
        'if(window.ResizeObserver){try{new ResizeObserver(R).observe(document.body);}catch(e){}}',
        '})();<\/script>'
    ].join('');

    /*
     * 宿主兼容层（JS 部分）。
     *
     * 预设卡片遵守的是 SillyTavern 的"插件契约"，不是在调我们的私有接口：
     *   - window.SillyTavern.getContext().generate()      直接发送
     *   - context.extensionSettings                       记住卡片自己的开关
     *   - context.saveSettingsDebounced()                 落盘
     *   - #send_textarea / #send_but                      改输入框 / 按发送
     * 这里把这几样按 ST 的形状搭出来，指向原生桥。于是「任何遵守 ST 契约的
     * 预设」都能点选项 —— 不需要为某个预设写适配，也不需要它知道我们存在。
     *
     * 注意：它同时跑在父壳（markdown 段落的按钮）和 iframe（整页卡片）里，
     * 所以取 DOM 必须走 parent.document；父壳里 parent === window，等价。
     */
    var HOST_SHIM = [
        '(function(){',
        'var P=window.parent||window;',
        'function B(){try{return P.DHPBridge||window.DHPBridge||null}catch(e){return null}}',
        'function D(){try{return (P&&P.document)||document}catch(e){return document}}',
        'function box(){try{return D().getElementById("send_textarea")}catch(e){return null}}',
        'var settings={};',
        'try{var b0=B();if(b0&&b0.getSettings)settings=JSON.parse(b0.getSettings()||"{}")||{}}catch(e){settings={}}',
        'function save(){try{var b=B();if(b&&b.saveSettings)b.saveSettings(JSON.stringify(settings))}catch(e){}}',
        'function fire(){try{var t=box();if(!t)return;var v=String(t.value||"");if(!v.replace(/\\s/g,""))return;',
        'var b=B();if(b&&b.send)b.send(v);t.value="";}catch(e){}}',
        'var es={on:function(){},once:function(){},off:function(){},emit:function(){},removeListener:function(){}};',
        'function ctx(){return {extensionSettings:settings,chat:[],characters:[],character:{},name1:"",name2:"",',
        'generate:fire,',
        'sendMessage:function(t){var e=box();if(e&&t!=null)e.value=String(t);fire()},',
        'saveSettings:save,saveSettingsDebounced:save,',
        'eventSource:es,eventTypes:{},event_types:{}}}',
        'var ST=window.SillyTavern||{};',
        'ST.getContext=ctx;ST.saveSettings=save;ST.saveSettingsDebounced=save;',
        'ST.eventSource=es;ST.event_types={};ST.libs=ST.libs||{};window.SillyTavern=ST;',
        'document.addEventListener("click",function(e){var n=e.target;while(n&&n!==document){',
        'if(n.id==="send_but"){e.preventDefault();fire();return}n=n.parentNode}},true);',
        '})();'
    ].join('');

    function installShim(doc) {
        try {
            var s = doc.createElement('script');
            s.setAttribute('data-dhp-host', '');
            s.textContent = HOST_SHIM;
            (doc.head || doc.documentElement).appendChild(s);
            s.parentNode.removeChild(s);
        } catch (e) { /* 老 WebView 或 CSP 挡住：退化为不可交互，渲染不受影响 */ }
    }

    function makeDocFrame(doc) {
        var f = document.createElement('iframe');
        f.className = 'card-doc';
        /*
         * allow-scripts：卡片自己的脚本（选项按钮、折叠、高度上报）必须能跑。
         * allow-same-origin：预设脚本是照 SillyTavern 写的 —— 它们会直接
         *   `window.parent.document.getElementById('send_textarea')` 去改宿主
         *   输入框。不给同源，这一句就抛 SecurityError，于是"选项点不动"。
         *   代价是卡片能摸到本壳的 DOM / DHPBridge；本壳只暴露
         *   height / input / send / settings 四个无权限接口，且不加载本地文件
         *   （allowFileAccessFromFileURLs=false），风险限制在这一条气泡内。
         */
        f.setAttribute('sandbox', 'allow-scripts allow-same-origin');
        f.setAttribute('scrolling', 'no');
        f.setAttribute('allowfullscreen', 'false');
        f.style.width = '100%';
        f.style.border = '0';
        f.style.display = 'block';
        f.style.background = 'transparent';
        f.style.height = '96px';         // 首帧占位，探针回报后立刻替换
        f.dataset.h = '';                // 收到过真实高度就置 1

        var full = doc;
        /* 卡片要按气泡宽度排版；作者没写 viewport 就补一个 */
        if (!/<meta\s+name=["']?viewport/i.test(full)) {
            var vp = '<meta name="viewport" content="width=device-width,initial-scale=1">';
            if (/<head\b[^>]*>/i.test(full)) {
                full = full.replace(/<head\b[^>]*>/i, function (h) { return h + vp; });
            } else {
                full = vp + full;
            }
        }
        /*
         * 只在作者没写 body 背景时保持透明，别给卡片盖一层白底；
         * 同时挡住固定宽度/大图把气泡撑破（作者自己的 CSS 仍优先）。
         *
         * ★ overflow-x 用 !important：预设卡片里常写 nowrap / 固定宽度，
         *   iframe 内部一旦超宽，用户就能左右拖动，每行字被切掉半边。
         *   这里把 html/body 锁死成"视口即边界"，卡片自己仍可纵向长高。
         */
        var guard = '<style data-dhp>' +
            'html,body{overflow-x:hidden!important;width:100%!important;' +
            'max-width:100%!important;margin:0!important}' +
            'body{background:transparent}' +
            'img,table,video,canvas,svg,iframe,pre{max-width:100%!important}' +
            '</style>';
        /*
         * 宿主 shim 必须比卡片自己的 <script> 更早执行 —— 卡片脚本在
         * <body> 里，所以插到 </head> 前。作者没写 head 时插最前面。
         */
        var hostShim = '<script data-dhp-host>' + HOST_SHIM + '</scr' + 'ipt>';
        if (/<\/head\s*>/i.test(full)) full = full.replace(/<\/head\s*>/i, hostShim + guard + '</head>');
        else full = hostShim + guard + full;
        /*
         * 作者在 <body> 里后写的 <style> 会覆盖 head 里的 guard，
         * 所以文档末尾再补一份（CSS 后加载者胜）。
         */
        var tailGuard = '<style data-dhp-tail>' +
            'html,body{overflow-x:hidden!important;width:100%!important;max-width:100%!important}' +
            '</style>';

        if (/<\/body\s*>/i.test(full)) full = full.replace(/<\/body\s*>/i, tailGuard + PROBE + '</body>');
        else full += tailGuard + PROBE;

        /* 用 DOM 属性设置，绕开 srcdoc 里引号/换行的转义地狱 */
        f.srcdoc = full;
        docFrames.push(f);

        /*
         * 兜底：个别 WebView（或 iframe 被系统挂起）不触发 load / ResizeObserver
         * 时，探针永远不回报，卡片会被首帧的 96px 裁成一条。按文档长度估一个
         * 上限 600px 的高度，至少让卡片完整可见；正常情况下不会走到这里。
         */
        setTimeout(function () {
            if (f.dataset.h || !f.isConnected) return;
            var est = Math.min(600, Math.max(180, Math.round(full.length / 70)));
            f.style.height = est + 'px';
            schedule();
        }, 2500);
        return f;
    }

    function render(raw, opts) {
        if (!root) return;
        var src = raw == null ? '' : String(raw);
        docFrames = [];
        root.innerHTML = '';

        var segs = segmentsOf(src);
        var hasDoc = false;
        for (var k = 0; k < segs.length; k++) if (segs[k].type === 'doc') { hasDoc = true; break; }

        if (!hasDoc) {
            /* 绝大多数消息：一次 marked，和之前完全一致 */
            root.innerHTML = md(src);
        } else {
            for (var i = 0; i < segs.length; i++) {
                var seg = segs[i];
                if (seg.type === 'doc') {
                    root.appendChild(makeDocFrame(seg.text));
                } else {
                    var d = document.createElement('div');
                    d.className = 'dhp-md';
                    d.innerHTML = md(seg.text);
                    root.appendChild(d);
                }
            }
        }

        /* 外链交给系统浏览器（WebViewClient 里拦），别把整个气泡跳走 */
        var links = root.querySelectorAll('a');
        for (var j = 0; j < links.length; j++) {
            links[j].setAttribute('target', '_blank');
            links[j].setAttribute('rel', 'noopener noreferrer');
        }

        lastH = -1;
        schedule();
        setTimeout(measure, 60);
        setTimeout(measure, 240);
        setTimeout(measure, 700);   // 卡片字体/图片加载完再校一次
    }

    /* ------------------------------------------------ iframe 高度消息 */

    window.addEventListener('message', function (e) {
        var d = e.data;
        if (!d || typeof d !== 'object') return;
        var h = 0;
        if (typeof d.__dhpH === 'number') h = d.__dhpH;
        else if (typeof d.height === 'number') h = d.height;   // 预设自带 updateIframeHeight
        else if (typeof d.iframeHeight === 'number') h = d.iframeHeight;
        if (!(h > 0)) return;

        var f = null;
        for (var i = 0; i < docFrames.length; i++) {
            try {
                if (docFrames[i].contentWindow === e.source) { f = docFrames[i]; break; }
            } catch (err) { /* 跨域，忽略 */ }
        }
        if (!f) return;
        f.dataset.h = '1';
        var px = Math.ceil(h);
        var cur = parseInt(f.style.height, 10) || 0;
        if (Math.abs(cur - px) > 2) {
            f.style.height = px + 'px';
            schedule();
        }
    }, false);

    /* -------------------------------------------------------------- 主题 */

    function theme(t) {
        if (!t) return;
        var s = document.documentElement.style;
        if (t.fg) s.setProperty('--fg', t.fg);
        if (t.accent) s.setProperty('--accent', t.accent);
        if (t.soft) s.setProperty('--soft', t.soft);
        if (t.line) s.setProperty('--line', t.line);
        if (t.code) s.setProperty('--code', t.code);
        if (t.size) s.setProperty('--size', t.size + 'px');
        if (t.lh) s.setProperty('--lh', String(t.lh));
        if (t.font) s.setProperty('--font', t.font);
        schedule();
    }

    function clear() {
        if (root) root.innerHTML = '';
        docFrames = [];
        lastH = -1;
    }

    window.DHP = { render: render, theme: theme, clear: clear };
    window.DHPReady = true;

    /* ------------------------------------------------ 宿主兼容层安装 */

    installShim(document);

    /*
     * 卡片把文字写进假输入框后，脚本只会 dispatch input/change（正是 ST 里的
     * 做法）。这里把它同步给原生输入框 —— 「填入 / 追加」这两种模式才看得见。
     * 「发送」模式走 shim 的 generate()，不经过这里。
     */
    (function () {
        var box = document.getElementById('send_textarea');
        if (!box) return;
        box.addEventListener('input', function () {
            try {
                if (window.DHPBridge && DHPBridge.input) DHPBridge.input(String(box.value || ''));
            } catch (e) { /* 页面已销毁 */ }
        });
    })();

    /* ---------------------------------------------------------- 高度监听 */

    // <details> 的 toggle 不冒泡，但捕获阶段能收到
    document.addEventListener('toggle', function (e) {
        if (e.target && e.target.tagName === 'DETAILS') {
            schedule();
            setTimeout(measure, 230);   // 等箭头动画走完
        }
    }, true);

    if (window.ResizeObserver) {
        try {
            new ResizeObserver(schedule).observe(root);
        } catch (e) { /* 老 WebView 无 ResizeObserver */ }
    }

    window.addEventListener('load', measure);
})();

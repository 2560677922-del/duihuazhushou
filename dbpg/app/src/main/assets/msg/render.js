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

    function isFullDoc(s) {
        return DOC_MARK.test(s || '');
    }

    /*
     * ``` 围栏（ST / Tavo 美化预设的通用约定：```html 块里放整页卡片）。
     * 只把「围栏内确实是整页文档」的块抽出来；其余围栏原样留给 marked，
     * 当作真正的代码块显示 —— 这是浏览器的标准语义，不能一刀切。
     */
    var FENCE = /^[ \t]*```[ \t]*([a-zA-Z0-9_-]*)[ \t]*\r?\n([\s\S]*?)^[ \t]*```[ \t]*$/gm;

    function splitSegments(raw) {
        var segs = [], last = 0, m;
        FENCE.lastIndex = 0;
        while ((m = FENCE.exec(raw)) !== null) {
            var lang = (m[1] || '').toLowerCase();
            var body = m[2];
            if (isFullDoc(body) && (lang === '' || lang === 'html' || lang === 'htm')) {
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
        'function H(){return Math.max(',
        'document.documentElement?document.documentElement.scrollHeight:0,',
        'document.body?document.body.scrollHeight:0);}',
        'function R(){try{parent.postMessage({__dhpH:H()},"*");}catch(e){}}',
        'addEventListener("load",R);',
        'setTimeout(R,40);setTimeout(R,180);setTimeout(R,500);',
        'if(window.ResizeObserver){try{new ResizeObserver(R).observe(document.documentElement);}catch(e){}}',
        '})();<\/script>'
    ].join('');

    function makeDocFrame(doc) {
        var f = document.createElement('iframe');
        f.className = 'card-doc';
        /* allow-scripts 是探针所必需；不给 same-origin / top-navigation / popups */
        f.setAttribute('sandbox', 'allow-scripts');
        f.setAttribute('scrolling', 'no');
        f.setAttribute('allowfullscreen', 'false');
        f.style.width = '100%';
        f.style.border = '0';
        f.style.display = 'block';
        f.style.background = 'transparent';
        f.style.height = '160px';        // 首帧占位，探针回报后立刻替换

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
         */
        var guard = '<style data-dhp>' +
            'html{overflow-x:hidden}body{background:transparent}' +
            'img,table,video,canvas,svg{max-width:100%;height:auto}' +
            '</style>';
        if (/<\/head\s*>/i.test(full)) full = full.replace(/<\/head\s*>/i, guard + '</head>');
        else full = guard + full;

        if (/<\/body\s*>/i.test(full)) full = full.replace(/<\/body\s*>/i, PROBE + '</body>');
        else full += PROBE;

        /* 用 DOM 属性设置，绕开 srcdoc 里引号/换行的转义地狱 */
        f.srcdoc = full;
        docFrames.push(f);
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

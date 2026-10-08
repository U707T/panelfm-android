/*
 * Office 只读预览的粘合层（PanelFM）。
 *
 * 约定（Kotlin 侧见 OfficeScreen.kt / OfficeFormats.kt）：
 *  - 页面从 https://office.panelfm/office/index.html?kind=… 加载（同源 + 全拦截，纯离线）
 *  - 文件字节：GET /doc/current
 *  - 渲染器：kind = docx | xlsx | pptx（其它值直接报错）
 *  - 三个渲染库都是第三方（许可见 third_party/office-web/），这里只做调用与错误兜底
 *
 * 设计原则（v1.9.2 起）：**任何失败都必须看得见**。以前出错只走 catch，
 * 一旦异常发生在异步链之外（window.onerror）或者渲染"成功但没有内容"，
 * 页面就是一片空白、用户与日志都拿不到线索。现在：
 *  - 全局 error / unhandledrejection 也进状态栏；
 *  - 渲染后检查"有没有实际内容"，空了就换纯文本兜底；
 *  - 每一步都 console.log（[OfficePreview] 前缀，Kotlin 侧会转发到 logcat）。
 */
(function () {
    'use strict';

    var params = new URLSearchParams(location.search);
    var kind = params.get('kind') || '';
    var mode = params.get('mode') || '';
    var statusEl = document.getElementById('status');
    var content = document.getElementById('content');
    var toolbar = document.getElementById('toolbar');
    var finished = false;

    /** 粘合层版本（跟着 App 版本走；诊断时一眼看出页面脚本是不是旧缓存） */
    var VIEWER_VERSION = '2.0.4';

    /** 单次 docx 渲染的最长等待（ms）：挂住时不再无限等，直接走兼容排版/纯文本兜底 */
    var DOCX_RENDER_TIMEOUT_MS = 8000;

    log('viewer ' + VIEWER_VERSION + ' | UA: ' + navigator.userAgent +
        ' | vp=' + window.innerWidth + 'x' + window.innerHeight + ' dpr=' + window.devicePixelRatio);

    function setStatus(text) {
        if (text) {
            statusEl.textContent = text;
            statusEl.style.display = 'flex';
        } else {
            statusEl.style.display = 'none';
        }
    }

    function describeError(e) {
        if (!e) return '未知错误';
        if (typeof e === 'string') return e;
        if (e.message) return e.message;
        try { return String(e); } catch (_e) { return '未知错误'; }
    }

    function log(msg) {
        try { console.log('[OfficePreview] ' + msg); } catch (_e) { /* ignore */ }
    }

    function warn(msg) {
        try { console.warn('[OfficePreview] ' + msg); } catch (_e) { /* ignore */ }
    }

    /** 未捕获异常也显示出来（否则整页空白、没有任何线索） */
    function fatal(where, e) {
        warn(where + ' 未捕获异常：' + describeError(e));
        if (!finished) setStatus('预览失败：' + describeError(e));
    }
    window.addEventListener('error', function (e) { fatal('window.onerror', e.error || e.message); });
    window.addEventListener('unhandledrejection', function (e) { fatal('unhandledrejection', e.reason); });

    async function fetchBytes() {
        var res = await fetch('/doc/current', { cache: 'no-store' });
        if (!res.ok) throw new Error('读取文件失败（HTTP ' + res.status + '）');
        var buf = await res.arrayBuffer();
        log('读取到 ' + buf.byteLength + ' 字节（kind=' + kind + '）');
        if (!buf || buf.byteLength === 0) throw new Error('文件内容为空');
        return buf;
    }

    // ------------------------------------------------------------------ Word

    /**
     * 渲染结果体检（结构 + **几何**）。为什么要看几何：实机 bug（2026-10-08 毕业设计 docx 空白）
     * 是页面被引擎压扁到几十像素、内容被 `overflow:hidden` 裁光 —— DOM 里"有 section、有文字"，
     * 但 `getBoundingClientRect()` 全是零高/零宽。只查 DOM 内容会漏判（旧版只看 textContent + img 标签）。
     */
    function docxStats(root) {
        var secs = root.querySelectorAll('section');
        var heights = [];
        for (var i = 0; i < secs.length && i < 5; i++) {
            heights.push(Math.round(secs[i].getBoundingClientRect().height));
        }
        var visibleText = 0;
        var nodes = root.querySelectorAll('span, p');
        for (var j = 0; j < nodes.length; j++) {
            var n = nodes[j];
            if ((n.textContent || '').replace(/\s+/g, '').length === 0) continue;
            var r = n.getBoundingClientRect();
            if (r.width > 0 && r.height > 0) {
                visibleText++;
                if (visibleText >= 10) break;
            }
        }
        return {
            sections: secs.length,
            pageHeights: heights,
            maxPageHeight: heights.length ? Math.max.apply(Math, heights) : 0,
            visibleText: visibleText,
            images: root.querySelectorAll('img').length,
        };
    }

    /** 正常文档的一页 ≥ 800px（min-height 来自页面尺寸）；< 200px 说明被压扁，需要换排版重试 */
    function docxLooksUsable(root) {
        var s = docxStats(root);
        if (s.sections > 0 && s.maxPageHeight < 200) return false;
        if (s.visibleText === 0 && s.images === 0) return false;
        return true;
    }

    /** 离屏渲染台：**挂在 body 上、参与布局、永不显示**。与可见区隔离，兜底/迟到结果都不打架 */
    function docxStage() {        var st = document.getElementById('docx-stage');
        if (!st) {
            st = document.createElement('div');
            st.id = 'docx-stage';
            st.style.cssText = 'position:fixed;left:-10000px;top:0;width:100%;visibility:hidden;pointer-events:none;';
            document.body.appendChild(st);
        }
        return st;
    }

    function removeNode(holder) {
        if (holder && holder.parentNode) holder.parentNode.removeChild(holder);
    }

    /** 把一个渲染好的挂载点搬到可见区域（保序搬子节点，保留 <style> 与页面结构） */
    function adoptDocxHolder(holder, compat) {
        content.className = 'docx-host' + (compat ? ' compat' : '');
        content.innerHTML = '';
        while (holder.firstChild) content.appendChild(holder.firstChild);
        removeNode(holder);
    }

    /** 在离屏台里渲染一次 docx（compat = 兼容排版），返回体检结果；超时返回 ok=false */
    function renderDocxIntoHolder(buf, compat) {
        return new Promise(function (resolve) {
            var holder = document.createElement('div');
            holder.className = 'docx-host' + (compat ? ' compat' : '');
            docxStage().appendChild(holder);

            var settled = false;
            var renderPromise = window.docx.renderAsync(buf, holder, holder, DOCX_OPTIONS);
            var timer = setTimeout(function () {
                if (settled) return;
                settled = true;
                warn('docx 渲染超时（' + DOCX_RENDER_TIMEOUT_MS + 'ms，compat=' + compat + '）：先走下一步，迟到结果再接住');
                resolve({ ok: false, timeout: true, holder: holder, late: renderPromise });
            }, DOCX_RENDER_TIMEOUT_MS);

            renderPromise.then(function () {
                if (settled) return;
                settled = true;
                clearTimeout(timer);
                var stats = docxStats(holder);
                log('docx 渲染完成（compat=' + compat + '）：' + JSON.stringify(stats));
                resolve({ ok: docxLooksUsable(holder), stats: stats, holder: holder });
            }, function (e) {
                if (settled) return;
                settled = true;
                clearTimeout(timer);
                warn('docx-preview 渲染抛错（compat=' + compat + '）：' + describeError(e));
                resolve({ ok: false, error: describeError(e), holder: holder });
            });
        });
    }

    /** 从 docx 字节里抽全文（纯文本兜底的数据来源） */
    async function extractDocxText(buf) {
        var zip = await window.JSZip.loadAsync(buf);
        var entry = zip.file('word/document.xml');
        if (!entry) throw new Error('文档结构异常（缺少 word/document.xml）');
        var xml = await entry.async('string');
        var doc = new DOMParser().parseFromString(xml, 'application/xml');
        var paragraphs = Array.prototype.slice.call(doc.getElementsByTagNameNS('*', 'p'));
        return paragraphs.map(function (p) {
            return Array.prototype.slice.call(p.getElementsByTagNameNS('*', 't'))
                .map(function (t) { return t.textContent || ''; })
                .join('');
        }).join('\n');
    }

    /** 纯文本兜底（保证一定看得到内容）；[manual] = 用户从诊断弹窗手动切的「纯文本预览」 */
    function showOnlyTextFallback(text, reason, manual) {
        content.className = 'docx-host';
        content.innerHTML = '';
        var tip = document.createElement('div');
        tip.className = 'fallback-tip';
        tip.textContent = manual
            ? '已按「纯文本预览」显示（右上角 ⓘ 里可切回排版预览）'
            : '排版渲染不可用（' + reason + '），已切换为纯文本预览';
        var pre = document.createElement('pre');
        pre.className = 'fallback-text';
        pre.textContent = text.replace(/\n{3,}/g, '\n\n') || '（文档里没有可提取的文字）';
        content.appendChild(tip);
        content.appendChild(pre);
        log('已使用纯文本兜底：' + reason);
    }

    var DOCX_OPTIONS = {
        className: 'docx',
        inWrapper: true,
        breakPages: true
    };

    // ------------------------------------------------------------------ 视口体检 / 自修复（v2.0.4）
    //
    // 实机（WebView 150 / Android 10）：docx 渲染完成、几何数据全部正常
    // （pageHeights 1123、visibleText 10），但用户只看到顶部一条 ≈32px 的白条。
    // 逐像素核对 = 「12px 内边距 + 32px 页面」= 滚动容器 #content 只剩 ~44px 高 ——
    // 布局视口被 WebView 的概览缩放（loadWithOverviewMode 在内容异步渲染前就做了测量）
    // 算坏了。这里三步：① 渲染后打 DIAG 体检；② 容器高度/缩放异常 → 重写 meta viewport
    // （Chromium 系会重新应用视口）；③ 仍异常 → 切 body 滚动（去掉绝对定位全屏容器）。

    /** DIAG 一行：视口 / 容器 / 页面 / 命中测试 —— 现场可复制回传 */
    function diagLine(tag) {
        try {
            var c = document.getElementById('content');
            var cr = c.getBoundingClientRect();
            var vv = window.visualViewport || {};
            function hit(x, y) {
                var el = document.elementFromPoint(x, y);
                if (!el) return 'null';
                var r = el.getBoundingClientRect();
                return el.tagName.toLowerCase() +
                    (el.className ? '.' + String(el.className).trim().split(/\s+/).join('.') : '') +
                    '@' + Math.round(r.x) + ',' + Math.round(r.y) +
                    ' ' + Math.round(r.width) + 'x' + Math.round(r.height);
            }
            var sec = document.querySelector('section');
            var sr = sec ? sec.getBoundingClientRect() : null;
            log('DIAG[' + tag + '] ' + JSON.stringify({
                inner: window.innerWidth + 'x' + window.innerHeight,
                visual: vv.width ? Math.round(vv.width) + 'x' + Math.round(vv.height) + ' scale=' + vv.scale : 'n/a',
                content: Math.round(cr.height) + 'px pos=' + getComputedStyle(c).position + ' scroll=' + c.scrollHeight,
                section: sr ? Math.round(sr.width) + 'x' + Math.round(sr.height) : null,
                hit: [hit(30, 80), hit(Math.round(window.innerWidth / 2), Math.round(window.innerHeight / 2))],
            }));
        } catch (e) {
            warn('DIAG 失败：' + describeError(e));
        }
    }

    /** 去掉绝对定位的全屏滚动容器，改由页面本体滚动（结构最简单、最不容易被引擎算坏） */
    function switchToBodyScroll() {
        document.documentElement.classList.add('body-scroll');
        log('已切换 body 滚动模式（去绝对定位容器）');
    }

    /** 重写 meta viewport（值不变）触发 Chromium/WebView 重新应用视口 */
    function repairViewport() {
        var meta = document.querySelector('meta[name=viewport]');
        if (!meta || !meta.parentNode) return false;
        var parent = meta.parentNode;
        parent.removeChild(meta);
        void document.documentElement.offsetHeight; // 强制重排
        parent.appendChild(meta);
        log('已重写 meta viewport（请求引擎重应用视口）');
        return true;
    }

    /** 渲染完成后调用：体检 → 异常就自修复（每步都留 DIAG，便于回传定位） */
    function checkAndRepairViewport() {
        diagLine('after-render');
        var c = document.getElementById('content');
        var h = c.getBoundingClientRect().height;
        var vv = window.visualViewport || {};
        // 只在「滚动容器高度异常」时升级修复：实机故障形态就是它只剩几十像素。
        // （不拿 zoom scale 当触发条件 —— 用户正双指缩放时 scale 本来就可能很小。）
        if (h >= 200 || window.innerHeight <= 200) return;
        warn('视口异常：content=' + Math.round(h) + 'px scale=' + (vv.scale || 'n/a') + ' → 尝试修复');
        if (repairViewport()) {
            setTimeout(function () {
                diagLine('after-meta-repair');
                var h2 = c.getBoundingClientRect().height;
                if (h2 < 200 && window.innerHeight > 200) {
                    switchToBodyScroll();
                    setTimeout(function () { diagLine('after-body-scroll'); }, 400);
                }
            }, 700);
        } else {
            switchToBodyScroll();
            setTimeout(function () { diagLine('after-body-scroll'); }, 400);
        }
    }

    /**
     * docx 渲染三级链路（任何一级成功就收工，保证「不会是一页空白」）：
     *  1. **标准排版**（docx-preview 默认）；
     *  2. **兼容排版**：去掉 section 的 column-flex / overflow:hidden ——
     *     部分 WebView 上「column flex + min-height + overflow:hidden」会把页面压扁到几十像素、
     *     内容被裁光（实机 2026-10-08：毕业设计 docx 整页空白），普通块级流没有这个坑；
     *  3. **纯文本兜底**：从 word/document.xml 抽段落文字。
     * 每次渲染都放到离屏台（[docxStage]）里做，超时（[DOCX_RENDER_TIMEOUT_MS]）先走下一步；
     * 迟到的结果如果可用，会再换进可见区域（弱机上「慢但能出图」的文档不被白等掉）。
     */
    async function renderDocx(buf) {
        if (!window.docx || !window.docx.renderAsync) throw new Error('docx-preview 未加载');

        var adopted = false;
        function adoptLate(holder) {
            if (adopted) { removeNode(holder); return; }
            adopted = true;
            adoptDocxHolder(holder, holder.className.indexOf('compat') >= 0);
            log('迟到的渲染结果已换入可见区域');
        }

        // 尝试 1：标准排版
        var a = await renderDocxIntoHolder(buf, false);
        if (a.ok) { adopted = true; adoptDocxHolder(a.holder, false); return; }
        // 渲染迟到但可用 → 接住（无论后面走到哪一步，都比兜底好）
        if (a.late) a.late.then(function () { if (docxLooksUsable(a.holder)) adoptLate(a.holder); else removeNode(a.holder); }, function () { removeNode(a.holder); });
        else removeNode(a.holder);

        // 尝试 2：兼容排版（原因见函数头）
        warn('标准排版不可用' + (a.stats ? '（' + JSON.stringify(a.stats) + '）' : a.error ? '（' + a.error + '）' : '（超时）') + '，改用兼容排版重试…');
        var b = await renderDocxIntoHolder(buf, true);
        if (b.ok && !adopted) { adopted = true; adoptDocxHolder(b.holder, true); return; }
        if (!b.ok) warn('兼容排版也不可用' + (b.stats ? '（' + JSON.stringify(b.stats) + '）' : b.error ? '（' + b.error + '）' : '（超时）'));
        if (b.late) b.late.then(function () { if (docxLooksUsable(b.holder)) adoptLate(b.holder); else removeNode(b.holder); }, function () { removeNode(b.holder); });
        else removeNode(b.holder);
        if (adopted) return;

        // 尝试 3：纯文本兜底
        showOnlyTextFallback(await extractDocxText(buf), '标准/兼容排版都不可用');
    }

    // ------------------------------------------------------------------ 表格

    async function renderXlsx(buf) {
        if (!window.XLSX) throw new Error('SheetJS 未加载');
        var wb = window.XLSX.read(new Uint8Array(buf), { type: 'array' });
        var names = wb.SheetNames || [];
        if (!names.length) throw new Error('工作簿里没有工作表');

        content.className = 'xlsx-host';
        toolbar.innerHTML = '';
        toolbar.style.display = 'flex';

        function show(index) {
            var html = window.XLSX.utils.sheet_to_html(wb.Sheets[names[index]], { header: '', footer: '' });
            content.innerHTML = html;
            Array.prototype.forEach.call(toolbar.querySelectorAll('button'), function (btn, i) {
                btn.classList.toggle('active', i === index);
            });
            content.scrollTop = 0;
            content.scrollLeft = 0;
            log('工作表「' + names[index] + '」渲染完成');
        }

        names.forEach(function (name, i) {
            var btn = document.createElement('button');
            btn.textContent = name;
            btn.addEventListener('click', function () { show(i); });
            toolbar.appendChild(btn);
        });
        show(0);
        if (!content.querySelector('table')) throw new Error('工作表内容为空');
    }

    // ------------------------------------------------------------------ 幻灯片

    async function renderPptx(buf) {
        var mod = await import('/office/vendor/pptx-renderer.es.js');
        if (!mod || !mod.PptxViewer) throw new Error('pptx-renderer 未加载');
        content.className = 'pptx-host';
        await mod.PptxViewer.open(buf, content, {
            zipLimits: mod.RECOMMENDED_ZIP_LIMITS,
            listOptions: { windowed: true }
        });
        log('幻灯片渲染完成');
    }

    // ------------------------------------------------------------------ 入口

    (async function main() {
        try {
            setStatus('正在读取文件…');
            var buf = await fetchBytes();
            if (kind === 'docx') {
                if (mode === 'text') {
                    // 诊断弹窗里手动切的「纯文本预览」：跳过排版渲染，直接抽文字
                    setStatus('正在提取文字…');
                    showOnlyTextFallback(await extractDocxText(buf), '手动选择纯文本', true);
                } else {
                    setStatus('正在渲染 Word 文档…');
                    await renderDocx(buf);
                }
            } else if (kind === 'xlsx') {
                setStatus('正在渲染表格…');
                await renderXlsx(buf);
            } else if (kind === 'pptx') {
                setStatus('正在渲染演示文稿…');
                await renderPptx(buf);
            } else {
                throw new Error('不支持的文档类型：' + kind);
            }
            finished = true;
            setStatus(null);
            log('预览完成');
            // 渲染完成后再体检一次视口（实机「页面正常但只有一条白条」就是这里发现的）
            setTimeout(checkAndRepairViewport, 500);
        } catch (e) {
            finished = true;
            warn('预览失败：' + describeError(e));
            setStatus('预览失败：' + describeError(e));
        }
    })();
})();

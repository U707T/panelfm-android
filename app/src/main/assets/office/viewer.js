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
    var statusEl = document.getElementById('status');
    var content = document.getElementById('content');
    var toolbar = document.getElementById('toolbar');
    var finished = false;

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

    /** 渲染结果里到底有没有东西（docx-preview 有时会"成功"但产出空壳） */
    function docxLooksEmpty() {
        if (!content.querySelector('section')) return true;
        if (content.querySelector('img, table, svg, canvas')) return false;
        return content.textContent.replace(/[\s\u2003\u00a0]+/g, '').length === 0;
    }

    /** 兜底：直接从 word/document.xml 抽文字（保证不是空白页） */
    async function renderDocxTextFallback(buf, reason) {
        var zip = await window.JSZip.loadAsync(buf);
        var entry = zip.file('word/document.xml');
        if (!entry) throw new Error('文档结构异常（缺少 word/document.xml）');
        var xml = await entry.async('string');
        var doc = new DOMParser().parseFromString(xml, 'application/xml');
        var paragraphs = Array.prototype.slice.call(doc.getElementsByTagNameNS('*', 'p'));
        var text = paragraphs.map(function (p) {
            return Array.prototype.slice.call(p.getElementsByTagNameNS('*', 't'))
                .map(function (t) { return t.textContent || ''; })
                .join('');
        }).join('\n');
        content.className = 'docx-host';
        content.innerHTML = '';
        var tip = document.createElement('div');
        tip.className = 'fallback-tip';
        tip.textContent = '排版渲染不可用（' + reason + '），已切换为纯文本预览';
        var pre = document.createElement('pre');
        pre.className = 'fallback-text';
        pre.textContent = text.replace(/\n{3,}/g, '\n\n') || '（文档里没有可提取的文字）';
        content.appendChild(tip);
        content.appendChild(pre);
        log('已使用纯文本兜底');
    }

    async function renderDocx(buf) {
        if (!window.docx || !window.docx.renderAsync) throw new Error('docx-preview 未加载');
        content.className = 'docx-host';
        try {
            await window.docx.renderAsync(buf, content, null, {
                className: 'docx',
                inWrapper: true,
                breakPages: true
            });
        } catch (e) {
            warn('docx-preview 渲染抛错：' + describeError(e));
            await renderDocxTextFallback(buf, describeError(e));
            return;
        }
        if (docxLooksEmpty()) {
            warn('docx-preview 渲染结果为空');
            await renderDocxTextFallback(buf, '渲染结果为空');
        }
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
                setStatus('正在渲染 Word 文档…');
                await renderDocx(buf);
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
        } catch (e) {
            finished = true;
            warn('预览失败：' + describeError(e));
            setStatus('预览失败：' + describeError(e));
        }
    })();
})();

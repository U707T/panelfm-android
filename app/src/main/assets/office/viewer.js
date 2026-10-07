/*
 * Office 只读预览的粘合层（PanelFM）。
 *
 * 约定（Kotlin 侧见 OfficeScreen.kt / OfficeFormats.kt）：
 *  - 页面通过 loadDataWithBaseURL 注入，baseUrl = https://office.panelfm/office/
 *    （子资源与 /doc/current 都由 WebViewClient.shouldInterceptRequest 提供，纯离线）
 *  - 文件字节：GET /doc/current
 *  - 渲染器：query 参数 kind = docx | xlsx | pptx（其它值直接报错）
 *  - 三个渲染库都是第三方（许可见 third_party/office-web/），这里只做调用与错误兜底
 */
(async function () {
    'use strict';

    var params = new URLSearchParams(location.search);
    var kind = params.get('kind') || '';
    var statusEl = document.getElementById('status');
    var content = document.getElementById('content');
    var toolbar = document.getElementById('toolbar');

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
        return e.message || String(e);
    }

    async function fetchBytes() {
        var res = await fetch('/doc/current', { cache: 'no-store' });
        if (!res.ok) throw new Error('读取文件失败（HTTP ' + res.status + '）');
        var buf = await res.arrayBuffer();
        if (!buf || buf.byteLength === 0) throw new Error('文件内容为空');
        return buf;
    }

    // ---- Word（docx-preview：UMD 全局 docx）----
    async function renderDocx(buf) {
        if (!window.docx || !window.docx.renderAsync) throw new Error('docx-preview 未加载');
        content.className = 'docx-host';
        await window.docx.renderAsync(buf, content, null, {
            className: 'docx',
            inWrapper: true,
            breakPages: true,
            experimental: true
        });
    }

    // ---- 表格（SheetJS：UMD 全局 XLSX；多工作表用顶部标签切换，只渲染当前表）----
    async function renderXlsx(buf) {
        if (!window.XLSX) throw new Error('SheetJS 未加载');
        var wb = window.XLSX.read(new Uint8Array(buf), { type: 'array' });
        var names = wb.SheetNames || [];
        if (!names.length) throw new Error('工作簿里没有工作表');

        content.className = 'xlsx-host';
        toolbar.innerHTML = '';
        toolbar.style.display = 'flex';

        function show(index) {
            var table = window.XLSX.utils.sheet_to_html(wb.Sheets[names[index]], { header: '', footer: '' });
            content.innerHTML = table;
            Array.prototype.forEach.call(toolbar.querySelectorAll('button'), function (btn, i) {
                btn.classList.toggle('active', i === index);
            });
            content.scrollTop = 0;
            content.scrollLeft = 0;
        }

        names.forEach(function (name, i) {
            var btn = document.createElement('button');
            btn.textContent = name;
            btn.addEventListener('click', function () { show(i); });
            toolbar.appendChild(btn);
        });
        show(0);
    }

    // ---- 幻灯片（@aiden0z/pptx-renderer：ESM，动态 import；自带 JSZip + ECharts）----
    async function renderPptx(buf) {
        var mod = await import('/office/vendor/pptx-renderer.es.js');
        if (!mod || !mod.PptxViewer) throw new Error('pptx-renderer 未加载');
        content.className = 'pptx-host';
        await mod.PptxViewer.open(buf, content, {
            zipLimits: mod.RECOMMENDED_ZIP_LIMITS,
            listOptions: { windowed: true }
        });
    }

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
        setStatus(null);
    } catch (e) {
        setStatus('预览失败：' + describeError(e));
    }
})();

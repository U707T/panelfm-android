'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');
const assets = path.resolve(__dirname, '../../app/src/main/assets/office');

test('security-sensitive vendor assets match the reviewed manifest', () => {
    const manifest = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../third_party/office-web/asset-manifest.json'), 'utf8'));
    const crypto = require('node:crypto');
    for (const [name, info] of Object.entries(manifest)) {
        const bytes = fs.readFileSync(path.join(assets, 'vendor', name));
        assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'), info.sha256, name);
    }
});

async function preview(kind, makeBytes) {
    const dom = new JSDOM('<!doctype html><div id="toolbar"></div><div id="content"></div><div id="status"></div>', {
        url: `https://office.panelfm/office/index.html?kind=${kind}`, runScripts: 'outside-only'
    });
    const w = dom.window;
    w.TextDecoder = TextDecoder;
    for (const name of ['xlsx.full.min.js', 'purify.min.js', 'marked.min.js']) {
        w.eval(fs.readFileSync(path.join(assets, 'vendor', name), 'utf8'));
    }
    let resolveDone;
    let rejectDone;
    const done = new Promise((resolve, reject) => { resolveDone = resolve; rejectDone = reject; });
    w.console = {
        log: message => { if (String(message).includes('预览完成')) resolveDone(); },
        warn: message => { if (String(message).includes('预览失败')) rejectDone(new Error(String(message))); },
        error: message => rejectDone(new Error(String(message)))
    };
    const bytes = makeBytes(w);
    w.fetch = async url => {
        assert.equal(url, '/doc/current');
        return { ok: true, arrayBuffer: async () => bytes };
    };
    const timeout = setTimeout(() => rejectDone(new Error('preview did not finish')), 3000);
    try {
        w.eval(fs.readFileSync(path.join(assets, 'viewer.js'), 'utf8'));
        await done;
        return { dom, content: w.document.getElementById('content'), version: w.XLSX.version };
    } catch (e) {
        dom.window.close();
        throw e;
    } finally { clearTimeout(timeout); }
}

test('bundled XLSX renders an actual workbook but removes active hyperlinks and markup', async () => {
    const result = await preview('xlsx', w => {
        const sheet = w.XLSX.utils.aoa_to_sheet([
            ['name', 'value'], ['audit hyperlink', '<img src=x onerror=alert(1)>'], ['中文', 42]
        ]);
        sheet.A2.l = { Target: 'javascript:globalThis.auditMarker=1' };
        const book = w.XLSX.utils.book_new();
        w.XLSX.utils.book_append_sheet(book, sheet, '工作表');
        return w.XLSX.write(book, { type: 'array', bookType: 'xlsx' });
    });
    try {
        assert.equal(result.version, '0.20.3');
        assert.ok(result.content.querySelector('table'));
        assert.match(result.content.textContent, /中文/);
        assert.match(result.content.textContent, /audit hyperlink/);
        assert.match(result.content.textContent, /<img src=x onerror=alert\(1\)>/);
        assert.equal(result.content.querySelector('a,script,iframe,form,[href],[onerror]'), null);
        assert.equal(result.dom.window.auditMarker, undefined);
    } finally { result.dom.window.close(); }
});

test('CSV still renders Chinese cells after upgrading the parser', async () => {
    const result = await preview('csv', () => new TextEncoder().encode('名称,数值\n测试,7\n').buffer);
    try {
        assert.ok(result.content.querySelector('table'));
        assert.match(result.content.textContent, /测试/);
        assert.match(result.content.textContent, /7/);
    } finally { result.dom.window.close(); }
});

test('Markdown sanitizes embedded script and preserves useful formatting', async () => {
    const result = await preview('markdown', () => new TextEncoder().encode(
        '# Title\n**safe**\n<script>globalThis.auditMarker=1</script>\n<img src="x" onerror="alert(1)">\n[click](javascript:alert(1))'
    ).buffer);
    try {
        assert.equal(result.content.querySelector('h1').textContent, 'Title');
        assert.equal(result.content.querySelector('strong').textContent, 'safe');
        assert.equal(result.content.querySelector('script,[onerror],a[href^="javascript:"]'), null);
        assert.equal(result.dom.window.auditMarker, undefined);
    } finally { result.dom.window.close(); }
});

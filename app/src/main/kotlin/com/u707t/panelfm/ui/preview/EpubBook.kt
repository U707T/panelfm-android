package com.u707t.panelfm.ui.preview

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

/** EPUB 目录模型：解析出来的一本书（只读阅读用）。 */
internal data class EpubChapter(val title: String, val path: String)

internal data class EpubBook(val title: String, val chapters: List<EpubChapter>)

/**
 * EPUB 结构解析（EPUB 2 / 3，够用就好的最小实现）：
 *  - `META-INF/container.xml` → OPF 路径；
 *  - OPF：`dc:title` + manifest（id→href）+ spine（阅读顺序）；
 *  - 章节标题：优先 NCX（EPUB2，spine@toc），其次 EPUB3 nav（manifest 里 properties 含 nav 的 XHTML）；
 *    都没有就退化成文件名。
 *
 * 全部是**纯函数**（XML 以字符串传入，读其它文件用回调），JVM 单测直接覆盖（见 EpubBookTest）。
 * ⚠️ 只解析、不执行内容：真正的书本页面在 WebView 里 **关 JS** 渲染（见 EpubScreen）。
 */
internal object EpubParser {

    /** `META-INF/container.xml` → OPF 的压缩包内路径。 */
    fun parseContainerXml(containerXml: String): String? {
        val doc = parseXml(containerXml) ?: return null
        val rootFiles = doc.getElementsByTagNameNS("*", "rootfile")
        for (i in 0 until rootFiles.length) {
            val e = rootFiles.item(i) as? Element ?: continue
            val fullPath = e.getAttribute("full-path").takeIf { it.isNotBlank() }
            if (fullPath != null) return fullPath
        }
        return null
    }

    /**
     * 解析 OPF。
     *
     * @param opfPath OPF 在压缩包内的路径（用来算相对目录）
     * @param readText 读取压缩包内其它文件（NCX / nav 文档）的文本；不可用返回 null
     */
    fun parseBook(opfPath: String, opfXml: String, readText: (String) -> String?): EpubBook {
        val doc = parseXml(opfXml) ?: throw IllegalStateException("EPUB 结构异常：OPF 不是合法 XML")
        val opfDir = epubDirOf(opfPath)

        val title = doc.getElementsByTagNameNS("*", "title").item(0)?.textContent?.trim()
            .orEmpty().ifBlank { "EPUB" }

        // manifest：id → href + properties
        val manifest = LinkedHashMap<String, String>()
        val properties = HashMap<String, String>()
        val items = doc.getElementsByTagNameNS("*", "item")
        for (i in 0 until items.length) {
            val e = items.item(i) as? Element ?: continue
            val id = e.getAttribute("id")
            val href = e.getAttribute("href")
            if (id.isNotBlank() && href.isNotBlank()) {
                manifest[id] = href
                properties[id] = e.getAttribute("properties").orEmpty()
            }
        }

        // 章节标题：NCX（EPUB2）优先，没有就用 EPUB3 nav
        val titles = runCatching {
            val tocId = doc.getElementsByTagNameNS("*", "spine").item(0)?.let { (it as? Element)?.getAttribute("toc") }
            val byToc = tocId?.takeIf { it.isNotBlank() }?.let { id ->
                val path = manifest[id]?.let { resolveEpubPath(opfDir, it) } ?: return@let null
                val xml = readText(path) ?: return@let null
                path to xml
            }
            if (byToc != null) return@runCatching parseNcxTitles(byToc.second, epubDirOf(byToc.first))
            val navId = manifest.entries.firstOrNull { "nav" in properties[it.key].orEmpty().split(' ') }?.key
            val byNav = navId?.let { id ->
                val path = manifest[id]?.let { resolveEpubPath(opfDir, it) } ?: return@let null
                val xml = readText(path) ?: return@let null
                path to xml
            }
            if (byNav != null) parseNavTitles(byNav.second, epubDirOf(byNav.first)) else emptyMap()
        }.getOrDefault(emptyMap<String, String>())

        // spine：阅读顺序
        val spine = doc.getElementsByTagNameNS("*", "spine").item(0) as? Element
        val itemRefs = doc.getElementsByTagNameNS("*", "itemref")
        val linear = ArrayList<String>()
        val all = ArrayList<String>()
        for (i in 0 until itemRefs.length) {
            val e = itemRefs.item(i) as? Element ?: continue
            val idRef = e.getAttribute("idref")
            val href = manifest[idRef] ?: continue
            val path = resolveEpubPath(opfDir, href) ?: continue
            all += path
            if (!e.getAttribute("linear").equals("no", ignoreCase = true)) linear += path
        }
        val paths = if (linear.isNotEmpty()) linear else all
        if (paths.isEmpty()) throw IllegalStateException("EPUB 结构异常：spine 里没有可读章节")

        val chapters = paths.map { path ->
            EpubChapter(title = titles[path]?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/'), path = path)
        }
        return EpubBook(title = title, chapters = chapters)
    }

    /** EPUB2 NCX：navPoint →（content@src, navLabel/text）。 */
    fun parseNcxTitles(ncxXml: String, ncxDir: String): Map<String, String> {
        val doc = parseXml(ncxXml) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        val points = doc.getElementsByTagNameNS("*", "navPoint")
        for (i in 0 until points.length) {
            val point = points.item(i) as? Element ?: continue
            val label = point.getElementsByTagNameNS("*", "text").item(0)?.textContent?.trim().orEmpty()
            val contents = point.getElementsByTagNameNS("*", "content")
            for (j in 0 until contents.length) {
                val src = (contents.item(j) as? Element)?.getAttribute("src") ?: continue
                val path = resolveEpubPath(ncxDir, src) ?: continue
                if (label.isNotBlank() && path !in out) out[path] = label
            }
        }
        return out
    }

    /** EPUB3 nav（XHTML）：取所有 `<a href>` 的标题（够用；不做 nav 嵌套树）。 */
    fun parseNavTitles(navXml: String, navDir: String): Map<String, String> {
        val doc = parseXml(navXml) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        val anchors = doc.getElementsByTagNameNS("*", "a")
        for (i in 0 until anchors.length) {
            val a = anchors.item(i) as? Element ?: continue
            val href = a.getAttribute("href")
            val label = a.textContent?.trim().orEmpty()
            val path = resolveEpubPath(navDir, href) ?: continue
            if (label.isNotBlank() && path !in out) out[path] = label
        }
        return out
    }

    private fun parseXml(xml: String) = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // 书是不可信输入：尽量关掉 DOCTYPE / 外部实体；个别解析器不认这些 feature，认不出就跳过
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { isExpandEntityReferences = false }
        }
        factory.newDocumentBuilder().parse(
            ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)),
        )
    }.getOrNull()

    // ------------------------------------------------------------------ 路径（纯函数，单测覆盖）

    /** `a/b/c.xhtml` → `a/b`；没有目录返回空串。 */
    fun epubDirOf(path: String): String = path.substringBeforeLast('/', "")

    /**
     * 把 `href`（相对 OPF / 章节所在目录）解析成压缩包内路径：
     *  - 去掉 `#fragment` 与 `?query`；
     *  - 百分号解码（UTF-8，**不**把 `+` 当空格 —— 那不是路径语义）；
     *  - 逐段消化 `.` / `..`；**越出压缩包根（.. 过多）返回 null**（防目录穿越）。
     */
    fun resolveEpubPath(baseDir: String, href: String): String? {
        val clean = percentDecode(href.substringBefore('#').substringBefore('?'))
        if (clean.isBlank()) return null
        val segments = ArrayList<String>()
        if (!clean.startsWith("/")) {
            baseDir.split('/').filterTo(segments) { it.isNotEmpty() }
        }
        for (part in clean.trimStart('/').split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> {
                    if (segments.isEmpty()) return null
                    segments.removeAt(segments.size - 1)
                }
                else -> segments += part
            }
        }
        if (segments.isEmpty()) return null
        return segments.joinToString("/")
    }

    /** 百分号解码（UTF-8；非法转义原样保留）。 */
    fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val bytes = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    bytes.write(v)
                    i += 3
                    continue
                }
            }
            val chunk = c.toString().toByteArray(StandardCharsets.UTF_8)
            bytes.write(chunk, 0, chunk.size)
            i++
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }
}

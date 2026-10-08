package com.u707t.panelfm.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * EPUB 解析（container / OPF / NCX / nav）与路径工具（纯 JVM，XML 用字符串喂）。
 * 真渲染依赖 WebView，无 JVM 单测 —— 实机抽验（见 CHANGELOG）。
 */
class EpubBookTest {

    private val containerXml = """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    private val opf = """
        <?xml version="1.0" encoding="utf-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>测试书籍</dc:title>
          </metadata>
          <manifest>
            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
            <item id="c2" href="text/ch%202.xhtml" media-type="application/xhtml+xml"/>
            <item id="css" href="style/main.css" media-type="text/css"/>
          </manifest>
          <spine toc="ncx">
            <itemref idref="c1"/>
            <itemref idref="c2"/>
            <itemref idref="css" linear="no"/>
          </spine>
        </package>
    """.trimIndent()

    private val ncx = """
        <?xml version="1.0"?>
        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
          <navMap>
            <navPoint id="p1" playOrder="1"><navLabel><text>第一章</text></navLabel><content src="text/ch1.xhtml"/></navPoint>
            <navPoint id="p2" playOrder="2"><navLabel><text>第二章</text></navLabel><content src="text/ch%202.xhtml#start"/></navPoint>
          </navMap>
        </ncx>
    """.trimIndent()

    @Test
    fun `container 与 opf 解析（NCX 标题、linear=no 过滤、百分号解码）`() {
        assertEquals("OEBPS/content.opf", EpubParser.parseContainerXml(containerXml))
        val book = EpubParser.parseBook("OEBPS/content.opf", opf) { path ->
            if (path == "OEBPS/toc.ncx") ncx else null
        }
        assertEquals("测试书籍", book.title)
        assertEquals(2, book.chapters.size)
        assertEquals("OEBPS/text/ch1.xhtml", book.chapters[0].path)
        assertEquals("第一章", book.chapters[0].title)
        // 百分号解码 + 去 fragment
        assertEquals("OEBPS/text/ch 2.xhtml", book.chapters[1].path)
        assertEquals("第二章", book.chapters[1].title)
    }

    @Test
    fun `没有 NCX 时用 EPUB3 nav 标题，全都没有则退化为文件名`() {
        val opf3 = opf.replace("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>",
            "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
            .replace("<spine toc=\"ncx\">", "<spine>")
        val nav = """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><body>
              <nav epub:type="toc" xmlns:epub="http://www.idpf.org/2007/ops">
                <ol><li><a href="text/ch1.xhtml">起始章</a></li></ol>
              </nav>
            </body></html>
        """.trimIndent()
        val withNav = EpubParser.parseBook("OEBPS/content.opf", opf3) { path ->
            if (path == "OEBPS/nav.xhtml") nav else null
        }
        assertEquals("起始章", withNav.chapters[0].title)
        // nav 的第一章没被 nav 覆盖到 → 文件名兜底
        assertEquals("ch 2.xhtml", withNav.chapters[1].title)

        val noToc = EpubParser.parseBook("OEBPS/content.opf", opf3) { null }
        assertEquals("ch1.xhtml", noToc.chapters[0].title)
    }

    @Test
    fun `路径解析拒绝越界`() {
        assertEquals("a/b/c.xhtml", EpubParser.resolveEpubPath("a/b", "c.xhtml"))
        assertEquals("a/x.xhtml", EpubParser.resolveEpubPath("a/b", "../x.xhtml"))
        assertEquals("x.xhtml", EpubParser.resolveEpubPath("a", "/x.xhtml"))
        assertEquals("a/b.xhtml", EpubParser.resolveEpubPath("a", "./b.xhtml"))
        assertEquals("带 空格.xhtml", EpubParser.resolveEpubPath("", "%E5%B8%A6%20%E7%A9%BA%E6%A0%BC.xhtml"))
        // 回到压缩包根之外 → null（防目录穿越）
        assertNull(EpubParser.resolveEpubPath("a", "../../x.xhtml"))
        assertNull(EpubParser.resolveEpubPath("", "../x.xhtml"))
        assertNull(EpubParser.resolveEpubPath("a", "#frag-only"))
        assertNull(EpubParser.resolveEpubPath("a", "?query-only"))
    }

    @Test
    fun `百分号解码不把加号当空格`() {
        assertEquals("a+b.xhtml", EpubParser.percentDecode("a+b.xhtml"))
        assertEquals("a b.xhtml", EpubParser.percentDecode("a%20b.xhtml"))
        // 非法转义原样保留
        assertEquals("a%zz.xhtml", EpubParser.percentDecode("a%zz.xhtml"))
    }

    @Test
    fun `书本内文件 MIME`() {
        assertEquals("text/html", mimeOfEntry("OEBPS/text/ch1.xhtml"))
        assertEquals("text/css", mimeOfEntry("OEBPS/style/main.css"))
        assertEquals("image/jpeg", mimeOfEntry("OEBPS/img/cover.jpeg"))
        assertEquals("application/xml", mimeOfEntry("OEBPS/toc.ncx"))
        assertEquals("application/octet-stream", mimeOfEntry("OEBPS/mystery.bin"))
    }

    @Test
    fun `损坏的 XML 不崩溃且抛可读错误`() {
        assertNull(EpubParser.parseContainerXml("<container><rootfiles"))
        val error = runCatching {
            EpubParser.parseBook("OEBPS/content.opf", "<package", readText = { null })
        }.exceptionOrNull()
        assertEquals(true, error is IllegalStateException)
    }
}

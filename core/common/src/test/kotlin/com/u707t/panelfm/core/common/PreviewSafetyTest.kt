package com.u707t.panelfm.core.common

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PreviewSafetyTest {
    @Test fun matrixRejectsAudited5000LineInputBeforeAllocation() {
        val text = (1..5000).joinToString("\n") { "line-$it" }
        val failure = runCatching { TextDiffEngine.diff(text, text) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("矩阵上限"))
    }

    @Test fun matrixBudgetBoundaryIsExplicit() {
        val text = (1..999).joinToString("\n") { "$it" }
        assertEquals(999, TextDiffEngine.diff(text, text).sameCount)
        assertTrue(runCatching { TextDiffEngine.diff("$text\n1000", "$text\n1000") }.isFailure)
    }

    @Test fun diffStopsOnCancellationCheckDuringMatrixWork() {
        var checks = 0
        val text = (1..100).joinToString("\n")
        val failure = runCatching {
            TextDiffEngine.diff(text, text, checkCancelled = {
                if (++checks == 110) throw CancellationException("stop")
            })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(110, checks)
    }

    private fun xmlSignature(xml: String): Pair<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray())).documentElement
        return root.textContent to root.getAttribute("v")
    }

    @Test fun xmlPreservesSignificantLeafAndAttributeWhitespace() {
        for (xml in listOf("<a> x </a>", "<a>   </a>", "<a v=\"x  y\"/>", "<a v=\"x&#x9;y\"> a </a>")) {
            assertEquals(xmlSignature(xml), xmlSignature(CodeFormatter.formatXml(xml)))
        }
    }

    @Test fun xmlMixedContentAndPreservationScopesStayByteExact() {
        for (xml in listOf(
            "<p>Hello <b>world</b> !</p>", "<p> <b/> </p>",
            "<a xml:space=\"preserve\"> <b/> x </a>",
            "<a><![CDATA[ x ]]><b/></a>",
            "<!DOCTYPE a [<!ELEMENT a ANY>]><a> x </a>",
            "<a> <!-- x --> </a>",
        )) assertEquals(xml, CodeFormatter.formatXml(xml))
    }

    @Test fun xmlEmptyLeafDoesNotGainWhitespace() {
        assertEquals("<a></a>", CodeFormatter.formatXml("<a></a>"))
        assertEquals("", xmlSignature(CodeFormatter.formatXml("<a></a>")).first)
    }

    @Test fun jsonDepthLimitRejectsSmallAuditedBomb() {
        val json = "[".repeat(10000) + "0" + "]".repeat(10000)
        val failure = runCatching { CodeFormatter.formatJson(json) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("嵌套"))
    }

    @Test fun jsonOutputLimitRejectsIndentExpansion() {
        val json = "[".repeat(64) + List(10000) { "0" }.joinToString(",") + "]".repeat(64)
        val failure = runCatching { CodeFormatter.formatJson(json) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("输出"))
    }

    @Test fun jsonSupportsCancellationAndBracketValidation() {
        assertNull(CodeFormatter.format("{]", "json"))
        val failure = runCatching {
            CodeFormatter.formatJson("[1,2]", checkCancelled = { throw CancellationException("stop") })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test fun boundedCopyDistinguishesExactLimitFromExcessWithoutOversizedWrite() = runBlocking {
        for (size in listOf(0, 7, 8, 9, 100000)) {
            val source = ByteArrayInputStream(ByteArray(size) { 42 })
            val out = ByteArrayOutputStream()
            val capped = copyPreviewBounded(8, { b, o, n -> source.read(b, o, n) }) { b, n -> out.write(b, 0, n) }
            assertEquals(size > 8, capped)
            assertEquals(minOf(size, 8), out.size())
        }
    }

    @Test fun boundedCopyFailsAfterThreeZeroReads() = runBlocking {
        var reads = 0
        val failure = runCatching { copyPreviewBounded(8, { _, _, _ -> ++reads; 0 }) { _, _ -> fail() } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(3, reads)
    }

    @Test fun boundedCopyToleratesOccasionalZeroReads() = runBlocking {
        var reads = 0
        val capped = copyPreviewBounded(8, { _, _, _ -> if (++reads == 1) 0 else -1 }) { _, _ -> fail() }
        assertFalse(capped)
    }

    @Test fun boundedCopyInheritsParentCancellationAndClosesCallerReader() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var closed = false
        val child = launch {
            try {
                copyPreviewBounded(8, { _, _, _ -> entered.complete(Unit); CompletableDeferred<Int>().await() }) { _, _ -> fail() }
            } finally { closed = true }
        }
        entered.await()
        child.cancelAndJoin()
        assertTrue(closed)
    }

    @Test fun decodedGzipCannotBypassActualByteLimit() {
        val compressed = ByteArrayOutputStream().also { target ->
            GZIPOutputStream(target).use { it.write(ByteArray(100000) { 32 }) }
        }.toByteArray()
        assertTrue(compressed.size < 1024)
        val failure = runCatching {
            PreviewLimitedInputStream(GZIPInputStream(ByteArrayInputStream(compressed)), 1024).use { it.readBytes() }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test fun streamBudgetChecksSingleBulkAndSkipReads() {
        assertEquals(8, PreviewLimitedInputStream(ByteArrayInputStream(ByteArray(8)), 8).readBytes().size)
        assertTrue(runCatching { PreviewLimitedInputStream(ByteArrayInputStream(ByteArray(9)), 8).readBytes() }.exceptionOrNull() is IOException)
        assertTrue(runCatching { PreviewLimitedInputStream(ByteArrayInputStream(ByteArray(9)), 8).skip(9) }.exceptionOrNull() is IOException)
        val stream = PreviewLimitedInputStream(ByteArrayInputStream(ByteArray(2)), 1)
        assertEquals(0, stream.read())
        assertTrue(runCatching { stream.read() }.exceptionOrNull() is IOException)
    }

    @Test fun pdfExtremeDimensionsStayWithinPixelAndEdgeBudgets() {
        for ((width, height) in listOf(612 to 792, 1 to Int.MAX_VALUE, Int.MAX_VALUE to 1, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val (w, h) = pdfRenderSize(width, height)
            assertTrue(w >= 1 && h >= 1)
            assertTrue(w.toLong() * h <= 4_000_000)
            assertTrue(w <= 1600 && h <= 4096)
        }
        assertTrue(runCatching { pdfRenderSize(0, 1) }.isFailure)
    }
}

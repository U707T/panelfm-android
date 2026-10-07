package com.u707t.panelfm.core.vfs.s3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * ListObjectsV2 解析回归（第 6 批 🔴1）。
 *
 * 用**真实 XmlPullParser 实现**（kxml2，Android 内置 KXmlParser 的上游同源代码）驱动解析纯函数；
 * 单测环境里 `android.util.Xml` 是桩、不可用。覆盖两个历史坑：
 *  - 厂商回显的请求 `<Prefix>` / 上一条 `<Contents>` 的 Key 顶替第一个 `<CommonPrefixes>`；
 *  - 缩进格式响应的空白文本污染 key / size / IsTruncated / NextContinuationToken。
 */
class S3ListParseTest {

    private fun parse(xml: String): S3Client.ListResult {
        val parser = KXmlParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        return parseListResult(parser)
    }

    /** AWS 风格紧凑响应：2 文件 + 2 子目录，带请求 prefix 回显（`dir/`） */
    private val mixed = """<?xml version="1.0" encoding="UTF-8"?>""" +
        """<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">""" +
        """<Name>photos</Name><Prefix>dir/</Prefix><KeyCount>4</KeyCount><MaxKeys>1000</MaxKeys>""" +
        """<Delimiter>/</Delimiter><IsTruncated>false</IsTruncated>""" +
        """<Contents><Key>dir/f1.txt</Key><LastModified>2026-10-01T10:00:00.000Z</LastModified><ETag>"e1"</ETag><Size>10</Size></Contents>""" +
        """<Contents><Key>dir/f2.txt</Key><LastModified>2026-10-01T10:00:01.000Z</LastModified><ETag>"e2"</ETag><Size>20</Size></Contents>""" +
        """<CommonPrefixes><Prefix>dir/s1/</Prefix></CommonPrefixes>""" +
        """<CommonPrefixes><Prefix>dir/s2/</Prefix></CommonPrefixes>""" +
        """</ListBucketResult>"""

    @Test
    fun `回显 prefix 与 Contents 残留不再顶替首个 CommonPrefixes`() {
        val r = parse(mixed)
        assertEquals(
            listOf("dir/f1.txt", "dir/f2.txt", "dir/s1/", "dir/s2/"),
            r.entries.map { it.key },
        )
        // f2 只允许以文件形态出现一次（旧实现会复制出一个同名「目录」条目）
        assertEquals(1, r.entries.count { it.key == "dir/f2.txt" })
        assertEquals(20L, r.entries.first { it.key == "dir/f2.txt" }.size)
        assertTrue(r.entries.first { it.key == "dir/s1/" }.isPrefix)
        assertTrue(r.entries.first { it.key == "dir/s2/" }.isPrefix)
    }

    @Test
    fun `仅子目录时不丢第一个子目录`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">""" +
            """<Name>photos</Name><Prefix>dir/</Prefix><IsTruncated>false</IsTruncated>""" +
            """<CommonPrefixes><Prefix>dir/s1/</Prefix></CommonPrefixes>""" +
            """<CommonPrefixes><Prefix>dir/s2/</Prefix></CommonPrefixes></ListBucketResult>"""
        val r = parse(xml)
        assertEquals(listOf("dir/s1/", "dir/s2/"), r.entries.map { it.key })
        assertTrue(r.entries.all { it.isPrefix })
    }

    @Test
    fun `根列表（回显为空）不受影响`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">""" +
            """<Name>photos</Name><Prefix></Prefix><IsTruncated>false</IsTruncated>""" +
            """<CommonPrefixes><Prefix>sub1/</Prefix></CommonPrefixes>""" +
            """<CommonPrefixes><Prefix>sub2/</Prefix></CommonPrefixes></ListBucketResult>"""
        assertEquals(listOf("sub1/", "sub2/"), parse(xml).entries.map { it.key })
    }

    @Test
    fun `紧凑分页元数据解析正确`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">""" +
            """<Name>photos</Name><Prefix>dir/</Prefix><IsTruncated>true</IsTruncated>""" +
            """<NextContinuationToken>tok-abc</NextContinuationToken>""" +
            """<Contents><Key>dir/f1.txt</Key><Size>10</Size></Contents></ListBucketResult>"""
        val r = parse(xml)
        assertTrue(r.truncated)
        assertEquals("tok-abc", r.nextToken)
        assertEquals(10L, r.entries.single().size)
    }

    @Test
    fun `缩进响应的空白文本不污染字段`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Name>photos</Name>
                <Prefix>dir/</Prefix>
                <IsTruncated>true</IsTruncated>
                <NextContinuationToken>tok-abc</NextContinuationToken>
                <Contents>
                    <Key>dir/f1.txt</Key>
                    <Size>10</Size>
                </Contents>
            </ListBucketResult>"""
        val r = parse(xml)
        assertTrue("缩进不应把 IsTruncated 覆盖回 false", r.truncated)
        assertEquals("tok-abc", r.nextToken)
        val entry = r.entries.single()
        assertEquals("dir/f1.txt", entry.key)
        assertEquals(10L, entry.size)
        assertFalse(entry.isPrefix)
    }

    @Test
    fun `空响应无条目且无异常`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">""" +
            """<Name>b</Name><IsTruncated>false</IsTruncated></ListBucketResult>"""
        val r = parse(xml)
        assertTrue(r.entries.isEmpty())
        assertNull(r.nextToken)
        assertFalse(r.truncated)
    }
}

package com.u707t.panelfm.core.apk

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * AXML（Android 二进制 XML）的最小实现 —— 只为「APKS / XAPK 转 APK」服务，**不是**通用 AXML 编辑器：
 *
 *  - 能读：元素树（名字 + 属性 + 类型化值），够解析 `AndroidManifest.xml`；
 *  - 能改：① 布尔属性改值（`isSplitRequired`）；② 字符串属性置空（`requiredSplitTypes` / `splitTypes`）；
 *          ③ 元素改名（`uses-split` → 不认识的标签，等于删掉这条声明）；
 *  - **不能**增删元素、改元素顺序、动资源表。
 *
 * 结构（frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h）：
 * ```
 * ResXMLTree_header { type=0x0003, headerSize=8, size }
 * ResStringPool      { 0x0001 }  ← 唯一会被重建的块（改名要把新名字追加进池）
 * ResXMLTree_resourceMap { 0x0180 }  ← 原样保留
 * 节点块 0x0100 命名空间 / 0x0102 开始元素 / 0x0103 结束元素 / 0x0104 CDATA ← 原样保留（属性就地改）
 * ```
 * 块内偏移都是「相对本块起点」，所以重建字符串池（变长）不影响后面的块 ——
 * 只需要更新外层 XML 头的 size。这是这里敢做变长改动的前提。
 */
internal class AxmlFile private constructor(
    private val strings: MutableList<String>,
    private var utf8Pool: Boolean,
    private var poolChunk: ByteArray,
    private var poolDirty: Boolean,
    private val resourceMapChunk: ByteArray?,
    private val nodes: List<ByteArray>,
    private val styleCount: Int,
) {

    companion object {
        private const val TYPE_XML = 0x0003
        private const val TYPE_STRING_POOL = 0x0001
        private const val TYPE_RESOURCE_MAP = 0x0180
        private const val TYPE_START_ELEMENT = 0x0102

        private const val TYPE_STRING = 0x03
        private const val TYPE_INT_BOOLEAN = 0x12
        private const val TYPE_NULL = 0x00

        private const val NO_INDEX = -1

        fun parse(bytes: ByteArray): AxmlFile {
            require(bytes.size >= 8) { "AXML 长度不足" }
            require(u16(bytes, 0) == TYPE_XML) { "不是 AXML（chunk type=0x${u16(bytes, 0).toString(16)}）" }
            val headerSize = u16(bytes, 2).coerceAtLeast(8)
            val declared = u32(bytes, 4)
            val end = if (declared in 8..bytes.size) declared else bytes.size

            var off = headerSize
            var pool: ByteArray? = null
            var resMap: ByteArray? = null
            val nodeChunks = ArrayList<ByteArray>()
            while (off + 8 <= end) {
                val type = u16(bytes, off)
                val size = u32(bytes, off + 4)
                require(size >= 8 && off + size <= end) { "AXML 块越界：off=$off size=$size end=$end" }
                val chunk = bytes.copyOfRange(off, off + size)
                when (type) {
                    TYPE_STRING_POOL -> if (pool == null) pool = chunk
                    TYPE_RESOURCE_MAP -> resMap = chunk
                    // 其余（命名空间 / 开始 / 结束 / CDATA）原样保留
                    else -> nodeChunks += chunk
                }
                off += size
            }
            val poolBytes = pool ?: error("AXML 缺少字符串池")
            return decode(poolBytes, resMap, nodeChunks)
        }

        private fun decode(pool: ByteArray, resMap: ByteArray?, nodes: List<ByteArray>): AxmlFile {
            val headerSize = u16(pool, 2)
            val count = u32(pool, 8)
            val styleCount = u32(pool, 12)
            val flags = u32(pool, 16)
            val stringsStart = u32(pool, 20)
            val utf8 = (flags and 0x100) != 0
            require(count >= 0 && headerSize + 4 * count <= pool.size) { "AXML 字符串池头异常" }
            val strings = ArrayList<String>(count)
            for (i in 0 until count) {
                val rel = u32(pool, headerSize + 4 * i)
                strings += readString(pool, stringsStart + rel, utf8)
            }
            return AxmlFile(strings.toMutableList(), utf8, pool, false, resMap, nodes, styleCount)
        }

        private fun readString(b: ByteArray, offset: Int, utf8: Boolean): String {
            var p = offset
            if (utf8) {
                var u16Len = u8(b, p)
                p += 1
                if (u16Len and 0x80 != 0) {
                    u16Len = ((u16Len and 0x7F) shl 8) or u8(b, p)
                    p += 1
                }
                var u8Len = u8(b, p)
                p += 1
                if (u8Len and 0x80 != 0) {
                    u8Len = ((u8Len and 0x7F) shl 8) or u8(b, p)
                    p += 1
                }
                require(p + u8Len <= b.size) { "AXML 字符串越界" }
                return String(b, p, u8Len, StandardCharsets.UTF_8)
            }
            var len = u16(b, p)
            p += 2
            if (len and 0x8000 != 0) {
                len = ((len and 0x7FFF) shl 16) or u16(b, p)
                p += 2
            }
            require(p + 2 * len <= b.size) { "AXML 字符串越界" }
            return String(b, p, 2 * len, StandardCharsets.UTF_16LE)
        }
    }

    /** 文档顺序的全部开始元素（结束元素、命名空间不返回） */
    fun elements(): List<AxmlElement> = nodes.mapNotNull { chunk ->
        if (u16(chunk, 0) != TYPE_START_ELEMENT) return@mapNotNull null
        val attrStart = u16(chunk, 24)
        val attrSize = u16(chunk, 26).coerceAtLeast(20)
        val attrCount = u16(chunk, 28)
        AxmlElement(this, chunk, 16 + attrStart, attrSize, attrCount)
    }

    fun firstElement(name: String): AxmlElement? = elements().firstOrNull { it.name == name }

    fun stringAt(index: Int): String =
        if (index < 0 || index >= strings.size) "" else strings[index]

    /** 取已有下标，没有就追加（会触发字符串池重建） */
    fun intern(value: String): Int {
        val existing = strings.indexOf(value)
        if (existing >= 0) return existing
        check(styleCount == 0) { "AXML 字符串池带 style，拒绝重建" }
        strings += value
        poolDirty = true
        return strings.size - 1
    }

    fun toBytes(): ByteArray {
        val pool = if (poolDirty) buildPool() else poolChunk
        val map = resourceMapChunk
        val total = 8 + pool.size + (map?.size ?: 0) + nodes.sumOf { it.size }
        val out = ByteArrayOutputStream(total)
        val header = ByteArray(8)
        putU16(header, 0, TYPE_XML)
        putU16(header, 2, 8)
        putU32(header, 4, total)
        out.write(header)
        out.write(pool)
        if (map != null) out.write(map)
        nodes.forEach { out.write(it) }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ 字符串池重建

    private fun buildPool(): ByteArray {
        val headerSize = 28
        val count = strings.size
        val encoded = strings.map { encodeString(it) }
        val dataSize = encoded.sumOf { it.size }
        val stringsStart = headerSize + 4 * count
        val size = stringsStart + ((dataSize + 3) and 3.inv())
        val out = ByteArray(size)
        putU16(out, 0, TYPE_STRING_POOL)
        putU16(out, 2, headerSize)
        putU32(out, 4, size)
        putU32(out, 8, count)
        putU32(out, 12, 0)
        putU32(out, 16, if (utf8Pool) 0x100 else 0)
        putU32(out, 20, stringsStart)
        putU32(out, 24, 0)
        var pos = 0
        encoded.forEachIndexed { i, bytes ->
            putU32(out, headerSize + 4 * i, pos)
            bytes.copyInto(out, stringsStart + pos)
            pos += bytes.size
        }
        return out
    }

    private fun encodeString(value: String): ByteArray {
        val out = ByteArrayOutputStream(value.length * 2 + 8)
        if (utf8Pool) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            writeUtf8Len(out, value.length)
            writeUtf8Len(out, bytes.size)
            out.write(bytes)
            out.write(0)
        } else {
            writeUtf16Len(out, value.length)
            for (ch in value) {
                out.write(ch.code and 0xFF)
                out.write((ch.code ushr 8) and 0xFF)
            }
            out.write(0)
            out.write(0)
        }
        return out.toByteArray()
    }

    private fun writeUtf8Len(out: ByteArrayOutputStream, len: Int) {
        if (len > 0x7F) {
            out.write(((len ushr 8) or 0x80) and 0xFF)
            out.write(len and 0xFF)
        } else {
            out.write(len)
        }
    }

    private fun writeUtf16Len(out: ByteArrayOutputStream, len: Int) {
        if (len > 0x7FFF) {
            val high = (len ushr 16) or 0x8000
            out.write(high and 0xFF)
            out.write((high ushr 8) and 0xFF)
            out.write(len and 0xFF)
            out.write((len ushr 8) and 0xFF)
        } else {
            out.write(len and 0xFF)
            out.write((len ushr 8) and 0xFF)
        }
    }

    // ------------------------------------------------------------------ 节点

    internal class AxmlElement(
        private val file: AxmlFile,
        internal val chunk: ByteArray,
        private val attrBase: Int,
        private val attrSize: Int,
        val attributeCount: Int,
    ) {
        val name: String get() = file.stringAt(u32(chunk, 20))

        fun attributes(): List<AxmlAttribute> = (0 until attributeCount).map { attribute(it) }

        fun attribute(i: Int): AxmlAttribute = AxmlAttribute(file, chunk, attrBase + i * attrSize)

        /** 按属性名找（忽略命名空间：清单里同名属性不会跨命名空间重复出现） */
        fun attr(name: String): AxmlAttribute? = attributes().firstOrNull { it.name == name }

        fun stringAttr(name: String): String? = attr(name)?.stringValue

        fun intAttr(name: String): Long? = attr(name)?.intValue

        fun boolAttr(name: String): Boolean? = attr(name)?.boolValue

        /** 改元素名（等于「删掉」这条声明：改成不认识的标签后系统解析器会整段跳过） */
        fun renameTo(newName: String) {
            putU32(chunk, 20, file.intern(newName))
        }
    }

    internal class AxmlAttribute(
        private val file: AxmlFile,
        internal val chunk: ByteArray,
        private val base: Int,
    ) {
        val name: String get() = file.stringAt(u32(chunk, base + 4))

        val dataType: Int get() = u8(chunk, base + 15)

        val stringValue: String?
            get() = if (dataType == TYPE_STRING) file.stringAt(u32(chunk, base + 16)) else null

        val intValue: Long?
            get() = when (dataType) {
                TYPE_INT_BOOLEAN, 0x10, 0x11 -> u32u(chunk, base + 16)
                TYPE_STRING -> file.stringAt(u32(chunk, base + 16)).toLongOrNull()
                else -> null
            }

        val boolValue: Boolean?
            get() = when (dataType) {
                TYPE_INT_BOOLEAN -> u32(chunk, base + 16) != 0
                TYPE_STRING -> when (file.stringAt(u32(chunk, base + 16))) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
                else -> null
            }

        fun setBoolean(value: Boolean) {
            chunk[base + 15] = TYPE_INT_BOOLEAN.toByte()
            putU32(chunk, base + 8, NO_INDEX)
            putU32(chunk, base + 16, if (value) -1 else 0)
        }

        /** 置为「无值」（字符串属性 → 系统读出来是 null，等于没写） */
        fun clearValue() {
            chunk[base + 15] = TYPE_NULL.toByte()
            putU32(chunk, base + 8, NO_INDEX)
            putU32(chunk, base + 16, 0)
        }
    }
}

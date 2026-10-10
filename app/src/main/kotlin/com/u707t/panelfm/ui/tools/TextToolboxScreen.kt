package com.u707t.panelfm.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.safeAreaPadding
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

// ================================================================================================
// 字符串工具箱（v2.0.12）：编码 / 摘要 / 文本 / 进制 四组操作，一屏完成。
//
// 借鉴 NP管理器 3.0.84「工具箱」（ToolboxActivity，解析见 /workspace/np-analysis/NP-UI-网络工具-解析.md §9）：
//  - **交互模型照抄**：输入 → 一屏平铺的操作 chips（不做下拉/弹窗，操作全部可见）→ 输出；
//  - **"结果变原始"**（NP `toolbox_exchange`）：把结果灌回输入，支持 Base64解码 → HEX解码 → MD5 这类链式；
//  - 操作集取长补短：保留 NP 的 Base64 / HEX / Unicode / 大小写 / 去空格 / 进制转换，
//    补 URL 编解码（文件管理器高频）、SHA-1 / SHA-256（NP 只有 MD5）；
//  - 不做 DES（NP 带"设置密匙"对话框；已过时且需要密钥管理 UI，用到再加）。
// ================================================================================================

/** 纯逻辑（可单测）：不依赖 Compose / Android。解码类返回 null 表示输入不是有效格式。 */
object TextToolboxKit {

    // ---------------------------------------------------------------- 编码（返回 null = 输入非法）

    fun base64Encode(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    /** 宽容解码：先剔除空白与换行，再把 URL-safe 字母表（-_）映射回标准表（+/）。 */
    fun base64Decode(s: String): String? {
        val clean = s.filterNot { it.isWhitespace() }
            .replace('-', '+')
            .replace('_', '/')
        return runCatching { String(Base64.getDecoder().decode(clean), Charsets.UTF_8) }.getOrNull()
    }

    /** 空格分隔、大写、两位一组（UTF-8 字节）。 */
    fun hexEncode(s: String): String =
        s.toByteArray(Charsets.UTF_8).joinToString(" ") { "%02X".format(it) }

    /** 宽容解码：允许空白 / 逗号 / 分号分隔与 `0x` 前缀；长度为奇数或含非法字符时返回 null。 */
    fun hexDecode(s: String): String? {
        val stripped = s.replace(Regex("0[xX]"), "")
            .filterNot { it.isWhitespace() || it == ',' || it == ';' }
        if (stripped.isEmpty()) return ""
        if (stripped.length % 2 != 0) return null
        if (!stripped.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val bytes = ByteArray(stripped.length / 2) { i ->
            stripped.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    private val Unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~".toSet()

    /** RFC 3986 百分号编码（空格 → %20，不是表单的 +）；路径 / 查询串都适用。 */
    fun urlEncode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in Unreserved) append(c) else append('%').append("%02X".format(b))
        }
    }

    /** 只解码合法 `%XX` 序列；非法序列（如 `100% wrong`）原样保留，不做破坏性替换。 */
    fun urlDecode(s: String): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = hexVal(s[i + 1])
                val lo = hexVal(s[i + 2])
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            // 普通字符按码点写入（保住代理对，如 emoji）
            val cp = s.codePointAt(i)
            out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
            i += Character.charCount(cp)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** 全量转义（含 ASCII），Java 风格 `\u4F60`、大写、每 UTF-16 码元一个。 */
    fun unicodeEncode(s: String): String = buildString {
        for (ch in s) append("\\u").append("%04X".format(ch.code))
    }

    /** 只替换合法 `\uXXXX`（大小写均可）；非法处（如 `C:\users`）原样保留。 */
    fun unicodeDecode(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 6 <= s.length && (s[i + 1] == 'u' || s[i + 1] == 'U')) {
                var v = 0
                var ok = true
                for (k in 2..5) {
                    val h = hexVal(s[i + k])
                    if (h < 0) {
                        ok = false
                        break
                    }
                    v = (v shl 4) or h
                }
                if (ok) {
                    out.append(v.toChar())
                    i += 6
                    continue
                }
            }
            out.append(s[i])
            i++
        }
        return out.toString()
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    // ---------------------------------------------------------------- 摘要（UTF-8，小写十六进制）

    fun md5(s: String): String = digest("MD5", s)
    fun sha1(s: String): String = digest("SHA-1", s)
    fun sha256(s: String): String = digest("SHA-256", s)

    private fun digest(algorithm: String, s: String): String =
        MessageDigest.getInstance(algorithm).digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- 文本

    fun upper(s: String): String = s.uppercase()
    fun lower(s: String): String = s.lowercase()

    /** 去掉全部空白（空格 / 制表符 / 换行）。 */
    fun removeSpaces(s: String): String = s.filterNot { it.isWhitespace() }

    // ---------------------------------------------------------------- 进制（返回 null = 输入非法）

    /** 10 → 16：容忍千分位逗号与下划线；负数按 64 位补码输出（与 Java Long.toHexString 一致）。 */
    fun decToHex(s: String): String? {
        val v = s.trim().replace(",", "").replace("_", "").toLongOrNull() ?: return null
        return java.lang.Long.toHexString(v).uppercase()
    }

    /** 16 → 10：容忍空白 / 逗号 / `0x` 前缀与符号。 */
    fun hexToDec(s: String): String? {
        val t = s.trim().removePrefix("0x").removePrefix("0X")
            .filterNot { it.isWhitespace() || it == ',' }
        return t.toLongOrNull(16)?.toString()
    }
}

/** 一个操作 chip：label 是按钮文案，run 返回 null = 输入不合法。 */
private data class ToolOp(val label: String, val run: (String) -> String?)

/** 操作全集（顺序即展示顺序：编码 → 摘要 → 文本 → 进制）。 */
private val TextToolboxOps: List<ToolOp> = listOf(
    ToolOp("Base64 编码") { TextToolboxKit.base64Encode(it) },
    ToolOp("Base64 解码") { TextToolboxKit.base64Decode(it) },
    ToolOp("HEX 编码") { TextToolboxKit.hexEncode(it) },
    ToolOp("HEX 解码") { TextToolboxKit.hexDecode(it) },
    ToolOp("URL 编码") { TextToolboxKit.urlEncode(it) },
    ToolOp("URL 解码") { TextToolboxKit.urlDecode(it) },
    ToolOp("Unicode 编码") { TextToolboxKit.unicodeEncode(it) },
    ToolOp("Unicode 解码") { TextToolboxKit.unicodeDecode(it) },
    ToolOp("MD5") { TextToolboxKit.md5(it) },
    ToolOp("SHA-1") { TextToolboxKit.sha1(it) },
    ToolOp("SHA-256") { TextToolboxKit.sha256(it) },
    ToolOp("转大写") { TextToolboxKit.upper(it) },
    ToolOp("转小写") { TextToolboxKit.lower(it) },
    ToolOp("去空格") { TextToolboxKit.removeSpaces(it) },
    ToolOp("10→16进制") { TextToolboxKit.decToHex(it) },
    ToolOp("16→10进制") { TextToolboxKit.hexToDec(it) },
)

/**
 * 字符串工具箱：输入 → chips → 输出，单屏完成；「结果变原始」支持链式操作。
 *
 * @param container 预留（后续可接"把结果存成文件"；当前页无容器依赖）
 */
@Composable
fun TextToolboxScreen(container: AppContainer, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    fun apply(op: ToolOp) {
        val r = op.run(input)
        if (r == null) {
            status = "「${op.label}」失败：输入不是有效格式（结果区保留上一次内容）"
        } else {
            output = r
            status = if (r.isEmpty()) "已应用：${op.label}（结果为空）" else "已应用：${op.label}"
        }
    }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(title = "字符串工具箱", subtitle = "编码 · 摘要 · 文本 · 进制", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("原始字符串") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            // 操作 chips：一屏平铺（NP「工具箱」的 FlowLayout 同款交互）
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextToolboxOps.forEach { op ->
                    ToolChip(op.label) { apply(op) }
                }
            }
            OutlinedTextField(
                value = output,
                onValueChange = { output = it },
                label = { Text("输出字符串") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            if (status.isNotEmpty()) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status.contains("失败")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                // NP `toolbox_exchange`「结果变原始」：链式操作的灵魂
                TextButton(
                    onClick = {
                        val t = input
                        input = output
                        output = t
                        status = "已交换：结果 → 原始"
                    },
                    enabled = output.isNotEmpty(),
                ) { Text("结果变原始") }
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(output))
                        status = "已复制结果"
                    },
                    enabled = output.isNotEmpty(),
                ) { Text("复制结果") }
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(input))
                        status = "已复制原始字符串"
                    },
                    enabled = input.isNotEmpty(),
                ) { Text("复制原始") }
                TextButton(
                    onClick = {
                        input = ""
                        output = ""
                        status = "已清空"
                    },
                    enabled = input.isNotEmpty() || output.isNotEmpty(),
                ) { Text("清空") }
            }
            Column(Modifier.padding(bottom = 12.dp)) {}
        }
    }
}

/** 操作 chip（NP 是动态 Button；这里用 MT 观感的浅色圆角块）。 */
@Composable
private fun ToolChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(MtSpec.CornerSmall))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

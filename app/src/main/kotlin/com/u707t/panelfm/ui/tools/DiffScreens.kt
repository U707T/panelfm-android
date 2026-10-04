package com.u707t.panelfm.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件对比器（对齐 MT 的「文件对比器」中的文本/ZIP 部分）：
 *  - 两个文本文件 → 行级 LCS 差异（+ 新增 / - 删除 / 空格 未变）
 *  - 两个压缩包 → 条目清单差异（新增/删除/大小变化）
 * 不含 DEX / ARSC / AXML 对比（这些属于逆向工具链，本项目不做）。
 */
enum class DiffLineKind { SAME, ADD, REMOVE }

data class DiffLine(val kind: DiffLineKind, val text: String, val leftNo: Int?, val rightNo: Int?)

object TextDiff {

    fun diff(leftText: String, rightText: String, maxLines: Int = 20000): List<DiffLine> {
        val a = leftText.split('\n').take(maxLines)
        val b = rightText.split('\n').take(maxLines)
        // LCS 动态规划（大文件截断，避免内存爆炸）
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val out = ArrayList<DiffLine>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    out.add(DiffLine(DiffLineKind.SAME, a[i], i + 1, j + 1)); i++; j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out.add(DiffLine(DiffLineKind.REMOVE, a[i], i + 1, null)); i++
                }
                else -> {
                    out.add(DiffLine(DiffLineKind.ADD, b[j], null, j + 1)); j++
                }
            }
        }
        while (i < n) { out.add(DiffLine(DiffLineKind.REMOVE, a[i], i + 1, null)); i++ }
        while (j < m) { out.add(DiffLine(DiffLineKind.ADD, b[j], null, j + 1)); j++ }
        return out
    }

    /** 两个压缩包的条目清单差异（文本形式） */
    fun diffZipEntries(left: List<String>, right: List<String>): List<String> {
        val leftSet = left.toSet()
        val rightSet = right.toSet()
        val out = ArrayList<String>()
        left.filter { it !in rightSet }.forEach { out.add("- 仅左侧：$it") }
        right.filter { it !in leftSet }.forEach { out.add("+ 仅右侧：$it") }
        if (out.isEmpty()) out.add("两侧条目完全一致（${left.size} 项）")
        return out
    }
}

@Composable
fun TextDiffScreen(container: AppContainer, left: VfsUri, right: VfsUri, onBack: () -> Unit) {
    var lines by remember { mutableStateOf<List<DiffLine>?>(null) }
    var summary by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(left, right) {
        try {
            val result = withContext(Dispatchers.IO) {
                val leftVfs = container.locator.find(left) ?: throw IllegalStateException("左侧会话不可用")
                val rightVfs = container.locator.find(right) ?: throw IllegalStateException("右侧会话不可用")
                val leftBytes = readAll(leftVfs, left)
                val rightBytes = readAll(rightVfs, right)
                val leftText = com.u707t.panelfm.core.common.TextEncodings.decode(leftBytes).text
                val rightText = com.u707t.panelfm.core.common.TextEncodings.decode(rightBytes).text
                TextDiff.diff(leftText, rightText)
            }
            lines = result
            summary = "共 ${result.size} 行 · 新增 ${result.count { it.kind == DiffLineKind.ADD }} · " +
                "删除 ${result.count { it.kind == DiffLineKind.REMOVE }}"
        } catch (e: Exception) {
            error = e.message
        }
    }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("文本对比", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "左：${left.displayPath}\n右：${right.displayPath}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        when {
            error != null -> Text("对比失败：$error", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            lines == null -> LoadingState("正在对比…")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(lines!!) { line ->
                    val bg = when (line.kind) {
                        DiffLineKind.ADD -> Color(0x2200C853)
                        DiffLineKind.REMOVE -> Color(0x22FF5252)
                        DiffLineKind.SAME -> Color.Transparent
                    }
                    val mark = when (line.kind) {
                        DiffLineKind.ADD -> "+"
                        DiffLineKind.REMOVE -> "-"
                        DiffLineKind.SAME -> " "
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(bg)
                            .padding(horizontal = 8.dp, vertical = 1.dp),
                    ) {
                        Text(
                            mark,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(
                            line.text.ifEmpty { " " },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun readAll(
    vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
    uri: VfsUri,
    max: Long = 2L * 1024 * 1024,
): ByteArray {
    val reader = vfs.openRead(uri)
    val out = java.io.ByteArrayOutputStream()
    try {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < max) {
            val n = kotlinx.coroutines.runBlocking { reader.read(buf, 0, minOf(buf.size.toLong(), max - total).toInt()) }
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
    } finally {
        runCatching { reader.close() }
    }
    return out.toByteArray()
}

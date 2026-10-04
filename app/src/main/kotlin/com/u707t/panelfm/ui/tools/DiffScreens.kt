package com.u707t.panelfm.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.DiffIgnore
import com.u707t.panelfm.core.common.DiffLine
import com.u707t.panelfm.core.common.DiffLineKind
import com.u707t.panelfm.core.common.DiffViewMode
import com.u707t.panelfm.core.common.TextDiffEngine
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文件对比器（对齐 MT 的「文件对比器」中的文本 / ZIP 部分）：
 *  - 两个文本文件 → 行级 LCS 差异（+ 新增 / - 删除 / 空格 未变）
 *  - 两个压缩包 → 条目清单差异（新增 / 删除）
 *
 * **本轮补齐 MT 对比器菜单 `0x7f0e000a` 的交互**：
 *  - 「忽略」四档：不忽略 / 忽略首尾空格 / 忽略全部空格 / 忽略空格和空行（`0x7f110144/145/142/143`）
 *  - 「区分大小写」勾选（`0x7f1103bc`）
 *  - 「浏览模式」三档：自动切换 / 双列 / 单列（`0x7f1101fb/1fc/1fd/1fe`）
 *  - 「上一个 / 下一个差异」跳转（`0x7f11055f` / `0x7f1104c5`）
 *  - 「未找到任何差异」空态（`0x7f1104d2`）
 *
 * 不含 DEX / ARSC / AXML 对比（这些属于逆向工具链，本项目不做）。
 */

object TextDiff {
    /** 兼容旧调用点：默认「不忽略 + 区分大小写」 */
    fun diff(leftText: String, rightText: String, maxLines: Int = 20000): List<DiffLine> =
        TextDiffEngine.diff(leftText, rightText, maxLines = maxLines).lines

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

/** 对比器的 ⋮ 菜单（复刻 MT `0x7f0e000a`） */
private enum class DiffMenu { IGNORE, VIEW, CASE }

@Composable
fun TextDiffScreen(container: AppContainer, left: VfsUri, right: VfsUri, onBack: () -> Unit) {
    var result by remember { mutableStateOf<TextDiffEngine.DiffResult?>(null) }
    var rawLeft by remember { mutableStateOf("") }
    var rawRight by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    var ignore by remember { mutableStateOf(DiffIgnore.NONE) }
    var caseSensitive by remember { mutableStateOf(true) }
    var wideEnough by remember { mutableStateOf(true) }
    var viewMode by remember { mutableStateOf(DiffViewMode.AUTO) }
    var menu by remember { mutableStateOf<DiffMenu?>(null) }
    var hunkIndex by remember { mutableStateOf(-1) }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val configuration = LocalConfiguration.current
    // MT 的「自动切换」：宽屏（≥ 600dp）双列，窄屏单列
    val autoWide = configuration.screenWidthDp >= 600
    val effectiveMode = if (viewMode == DiffViewMode.AUTO) DiffViewMode.autoFor(autoWide) else viewMode
    wideEnough = effectiveMode == DiffViewMode.SIDE_BY_SIDE

    // 读取两侧文本（只读一次，切换忽略档位时本地重算，不重新读网络）
    LaunchedEffect(left, right) {
        try {
            val pair = withContext(Dispatchers.IO) {
                val leftVfs = container.locator.find(left) ?: throw IllegalStateException("左侧会话不可用")
                val rightVfs = container.locator.find(right) ?: throw IllegalStateException("右侧会话不可用")
                readAll(leftVfs, left) to readAll(rightVfs, right)
            }
            rawLeft = com.u707t.panelfm.core.common.TextEncodings.decode(pair.first).text
            rawRight = com.u707t.panelfm.core.common.TextEncodings.decode(pair.second).text
        } catch (e: Exception) {
            error = e.message
        }
    }

    // 档位变化 → 重算（后台线程）
    LaunchedEffect(rawLeft, rawRight, ignore, caseSensitive) {
        if (rawLeft.isEmpty() && rawRight.isEmpty()) return@LaunchedEffect
        result = withContext(Dispatchers.Default) {
            TextDiffEngine.diff(rawLeft, rawRight, ignore = ignore, caseSensitive = caseSensitive)
        }
        hunkIndex = -1
    }

    /** 跳到第 [dir] 个差异块（1 = 下一个，-1 = 上一个） */
    fun jumpHunk(dir: Int) {
        val hunks = result?.hunks ?: return
        if (hunks.isEmpty()) {
            container.browser.showStatus("未找到任何差异")
            return
        }
        hunkIndex = ((hunkIndex + dir) % hunks.size + hunks.size) % hunks.size
        scope.launch { listState.animateScrollToItem(hunks[hunkIndex]) }
    }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("文本对比", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            // MT：上一个 / 下一个差异
            TextButton(onClick = { jumpHunk(-1) }) { Text("↑ 上个差异") }
            TextButton(onClick = { jumpHunk(1) }) { Text("↓ 下个差异") }
            // MT：⋮ 菜单（浏览模式 / 忽略 / 区分大小写）
            Box {
                TextButton(onClick = { menu = DiffMenu.IGNORE }) { Text("⋮") }
                DropdownMenu(expanded = menu == DiffMenu.IGNORE, onDismissRequest = { menu = null }) {
                    Text("忽略", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                    DiffIgnore.entries.forEach { opt ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = opt == ignore, onClick = null)
                                    Text(opt.label)
                                }
                            },
                            onClick = { ignore = opt; menu = null },
                        )
                    }
                }
            }
            Box {
                TextButton(onClick = { menu = DiffMenu.VIEW }) { Text("◫") }
                DropdownMenu(expanded = menu == DiffMenu.VIEW, onDismissRequest = { menu = null }) {
                    Text("浏览模式", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                    DiffViewMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = mode == viewMode, onClick = null)
                                    Text(mode.label)
                                }
                            },
                            onClick = { viewMode = mode; menu = null },
                        )
                    }
                }
            }
            Box {
                TextButton(onClick = { menu = DiffMenu.CASE }) { Text("Aa") }
                DropdownMenu(expanded = menu == DiffMenu.CASE, onDismissRequest = { menu = null }) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = caseSensitive, onCheckedChange = null)
                                Text("区分大小写")
                            }
                        },
                        onClick = { caseSensitive = !caseSensitive; menu = null },
                    )
                }
            }
        }

        val current = result
        Text(
            buildString {
                append("左：").append(left.displayPath).append("\n右：").append(right.displayPath)
                if (current != null) {
                    append("\n共 ").append(current.lines.size).append(" 行 · 新增 ").append(current.added)
                    append(" · 删除 ").append(current.removed)
                    append(" · 差异块 ").append(current.hunks.size)
                    if (ignore != DiffIgnore.NONE || !caseSensitive) {
                        append(" · 已应用：")
                        if (ignore != DiffIgnore.NONE) append(ignore.label)
                        if (ignore != DiffIgnore.NONE && !caseSensitive) append(" + ")
                        if (!caseSensitive) append("不区分大小写")
                    }
                    if (current.truncated) append("\n⚠ 文件较大，已截断到 20000 行后对比")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        when {
            error != null -> Text("对比失败：$error", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            current == null -> LoadingState("正在对比…")
            current.added == 0 && current.removed == 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // MT 0x7f1104d2「未找到任何差异」
                Text("未找到任何差异", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(current.lines) { line ->
                    DiffRow(line, wideEnough = wideEnough)
                }
            }
        }
    }
}

/** 单行差异：单列 = 带 +/- 前缀；双列 = 左右两栏并排（MT 的「双列」浏览模式） */
@Composable
private fun DiffRow(line: DiffLine, wideEnough: Boolean) {
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
    if (!wideEnough) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(bg)
                .padding(horizontal = 8.dp, vertical = 1.dp),
        ) {
            Text(
                (line.leftNo?.toString() ?: "").padStart(5) + " " + (line.rightNo?.toString() ?: "").padStart(5) + " $mark ",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                line.text.ifEmpty { " " },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    Row(Modifier.fillMaxWidth().background(bg)) {
        // 左栏（只显示左侧的行：SAME / REMOVE）
        Box(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 1.dp)) {
            if (line.kind != DiffLineKind.ADD) {
                Text(
                    (line.leftNo?.toString() ?: "").padStart(5) + "  " + line.text.ifEmpty { " " },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        // 右栏（只显示右侧的行：SAME / ADD）
        Box(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 1.dp)) {
            if (line.kind != DiffLineKind.REMOVE) {
                Text(
                    (line.rightNo?.toString() ?: "").padStart(5) + "  " + line.text.ifEmpty { " " },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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



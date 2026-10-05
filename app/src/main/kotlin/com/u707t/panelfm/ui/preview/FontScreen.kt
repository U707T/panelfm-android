package com.u707t.panelfm.ui.preview

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.ui.LoadingState
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 字体预览（M7）：把字体文件落到临时目录 → Typeface → 多字号示例 + 字形网格（BMP 区前若干码位）。
 */
@Composable
fun FontScreen(container: AppContainer, uri: VfsUri, onBack: () -> Unit) {
    val context = LocalContext.current
    var typeface by remember { mutableStateOf<Typeface?>(null) }
    var sample by remember { mutableStateOf("PanelFM 字体预览 AaBbCc 0123456789 你好，世界") }
    var sizeText by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(0) }

    LaunchedEffect(uri) {
        val tf = withContext(Dispatchers.IO) {
            runCatching {
                // 缓存名用完整 URI 的 hash（不同目录的同名字体不互相覆盖）
                val local = File(container.appDirs.tmpDir, "font-${uri.toString().hashCode()}-${uri.name}")
                if (!local.exists()) {
                    val vfs = container.resolveSession(uri) ?: throw IllegalStateException("会话不可用")
                    val reader = vfs.openRead(uri)
                    try {
                        local.outputStream().use { out ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                val n = reader.read(buf, 0, buf.size)
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                        }
                    } finally {
                        runCatching { reader.close() }
                    }
                }
                Typeface.createFromFile(local)
            }.getOrNull()
        }
        typeface = tf
        sizeText = runCatching {
            val vfs = container.resolveSession(uri)
            vfs?.stat(uri)?.size?.let { Fmt.size(it) }.orEmpty()
        }.getOrDefault("")
    }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(
            title = uri.name,
            subtitle = sizeText.ifBlank { null },
            onBack = onBack,
        )

        val tf = typeface
        if (tf == null) {
            LoadingState("解析字体…")
            return@Column
        }

        Column(Modifier.padding(12.dp)) {
            Text("示例文本", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.text.BasicTextField(
                value = sample,
                onValueChange = { sample = it },
                singleLine = false,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
            listOf(14, 20, 28, 40).forEach { size ->
                Text(
                    sample,
                    fontSize = size.sp,
                    fontFamily = FontFamily(tf),
                    maxLines = 1,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("字形网格", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { page = (page - 1).coerceAtLeast(0) }) { Text("上一页") }
            Text("${page + 1}", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { page += 1 }) { Text("下一页") }
        }

        val start = page * GLYPHS_PER_PAGE
        LazyVerticalGrid(
            columns = GridCells.Adaptive(56.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items((start until start + GLYPHS_PER_PAGE).toList()) { code ->
                Box(
                    Modifier
                        .height(56.dp)
                        .background(Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        String(Character.toChars(code)),
                        fontSize = 24.sp,
                        fontFamily = FontFamily(tf),
                    )
                }
            }
        }
    }
}

private const val GLYPHS_PER_PAGE = 192

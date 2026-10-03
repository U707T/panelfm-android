package com.u707t.panelfm.ui.bookmarks

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.ui.EmptyState
import com.u707t.panelfm.core.ui.FileIcon
import com.u707t.panelfm.core.ui.MtListRow

/** 书签管理：点开跳到对应目录，长按删除。 */
@Composable
fun BookmarksScreen(container: AppContainer, onBack: () -> Unit, onOpen: () -> Unit) {
    var version by remember { mutableStateOf(0) }
    val bookmarks = remember(version) { container.browser.bookmarks() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("书签", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "${bookmarks.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (bookmarks.isEmpty()) {
            EmptyState("还没有书签", "在双列页 ⋮ 菜单里「添加书签」")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(bookmarks, key = { it.id }) { bookmark ->
                    MtListRow(
                        title = bookmark.name.ifEmpty { bookmark.uri.name.ifEmpty { "/" } },
                        subtitle = bookmark.uri.toString(),
                        icon = { FileIcon(name = bookmark.name, isDirectory = true, size = 38.dp) },
                        onClick = {
                            container.browser.openBookmark(bookmark)
                            onOpen()
                        },
                        trailing = {
                            TextButton(onClick = {
                                container.browser.removeBookmark(bookmark.id)
                                version++
                            }) { Text("删除", style = MaterialTheme.typography.labelSmall) }
                        },
                        titleColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

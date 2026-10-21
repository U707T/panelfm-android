package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.model.ConflictInfo
import com.u707t.panelfm.core.model.ConflictPolicy
import com.u707t.panelfm.core.model.defaultPolicy
import com.u707t.panelfm.core.model.explanationText
import com.u707t.panelfm.core.ui.DialogIcon
import com.u707t.panelfm.core.ui.DialogIconMode
import com.u707t.panelfm.core.ui.MtIcon


/** 移动二次确认（需求：移动必须确认，且写明跨协议中转语义） */
@Composable
fun MoveConfirmDialog(pending: PendingMove, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("移动到对面窗格？") },
        text = {
            Column {
                Text("共 ${pending.count} 项" + if (pending.bytes > 0) "，约 ${Fmt.size(pending.bytes)}" else "")
                Text("从：${pending.fromLabel}", style = MaterialTheme.typography.bodySmall)
                Text("到：${pending.toLabel}", style = MaterialTheme.typography.bodySmall)
                if (pending.crossVfs) {
                    Text(
                        "跨存储移动 = 先复制再删除源文件；中断时可能出现副本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("移动") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

/**
 * 同名冲突（复刻 MT 0x7f0c0098「文件已存在」）。
 *
 * MT 用**三个单选**而不是三个按钮：
 *   复制并替换 / 跳过该文件 / 复制但保留两个文件（移动场景换成「移动并…」）
 * 另有 ☐ 为后续冲突执行相同操作（MT 0x7f1100a5）。
 */
@Composable
fun ConflictDialog(
    info: ConflictInfo,
    dialogIconMode: Int = 0,
    onDecision: (ConflictPolicy, Boolean) -> Unit,
) {
    // 按「冲突」重置：键必须是全局唯一 token。只用文件名会让不同目录下的同名文件
    // （两个 index.html）沿用上一次的选择，甚至继承 applyAll。
    var applyAll by remember(info.token) { mutableStateOf(false) }
    // 默认预选项由模型决定：只有「文件 → 文件夹」（替换 = 递归删除整个文件夹）默认「跳过」。
    var policy by remember(info.token) { mutableStateOf(info.defaultPolicy()) }
    val verb = if (info.isMove) "移动" else "复制"

    AlertDialog(
        onDismissRequest = { onDecision(ConflictPolicy.SKIP, applyAll) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // MT 的「对话框图标」在冲突框也出现（深浅自适应）；图标换成 MT 的真实矢量
                DialogIcon(MtIcon.ERROR, DialogIconMode.of(dialogIconMode), size = 32.dp)
                Text("文件已存在", modifier = Modifier.padding(start = 10.dp))
            }
        },
        text = {
            Column {
                Text("源：${info.sourceName}")
                Text("目标：${info.destName}" + if (info.destSize >= 0) "（${Fmt.size(info.destSize)}）" else "")
                Text(
                    info.explanationText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
                // 「文件 → 文件夹」的替换有整目录删除后果，选项标签直接写明（与解说文案同源）。
                val overwriteLabel =
                    if (!info.sourceIsDirectory && info.isDirectory) "${verb}并替换（将删除文件夹）" else "${verb}并替换"
                listOf(
                    ConflictPolicy.OVERWRITE to overwriteLabel,
                    ConflictPolicy.SKIP to "跳过该文件",
                    ConflictPolicy.KEEP_BOTH to "${verb}但保留两个文件",
                ).forEach { (p, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { policy = p }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = policy == p, onClick = { policy = p })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Checkbox(checked = applyAll, onCheckedChange = { applyAll = it })
                    Text("为后续冲突执行相同操作", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDecision(policy, applyAll) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = { onDecision(ConflictPolicy.SKIP, applyAll) }) { Text("取消") } },
    )
}

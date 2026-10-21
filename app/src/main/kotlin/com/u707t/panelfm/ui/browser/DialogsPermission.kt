package com.u707t.panelfm.ui.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.common.Fmt

// ---------------------------------------------------------------------------
// 权限对话框（复刻 MT 0x7f0c0096「权限」）
//
// MT 布局：读/写/执行 × 所有者/用户组/其它 九宫格 + `---- 特殊权限 ----`
// + 设置UID / 设置GID / 粘滞 + ☑ 同时应用到所有子文件 / ☑ 同时应用到所有子文件夹。
// 旧实现只有一个八进制输入框（用户必须自己算位），这里换成勾选式。
// ---------------------------------------------------------------------------

@Composable
fun MtPermissionDialog(
    fileName: String,
    isDirectory: Boolean,
    initialMode: Int?,
    canRecurse: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (mode: Int, recurseFiles: Boolean, recurseDirs: Boolean) -> Unit,
) {
    // 默认 644（文件）/ 755（目录）——与 MT 的常见默认一致
    var mode by remember(fileName, initialMode) {
        mutableStateOf(initialMode ?: if (isDirectory) 0b111_101_101 else 0b110_100_100)
    }
    var recurseFiles by remember(fileName) { mutableStateOf(false) }
    var recurseDirs by remember(fileName) { mutableStateOf(false) }

    fun bit(mask: Int) = mode and mask != 0
    fun setBit(mask: Int, on: Boolean) {
        mode = if (on) mode or mask else mode and mask.inv()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("权限") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(fileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 表头
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("", modifier = Modifier.width(56.dp))
                    listOf("读", "写", "执行").forEach {
                        Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    }
                }
                // 三行：所有者 / 用户组 / 其它
                listOf(
                    Triple("所有者", 0b100_000_000, 0b010_000_000),
                    Triple("用户组", 0b000_100_000, 0b000_010_000),
                    Triple("其它", 0b000_000_100, 0b000_000_010),
                ).forEach { (label, readMask, writeMask) ->
                    val execMask = readMask shr 2
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp))
                        Checkbox(checked = bit(readMask), onCheckedChange = { setBit(readMask, it) }, modifier = Modifier.weight(1f))
                        Checkbox(checked = bit(writeMask), onCheckedChange = { setBit(writeMask, it) }, modifier = Modifier.weight(1f))
                        Checkbox(checked = bit(execMask), onCheckedChange = { setBit(execMask, it) }, modifier = Modifier.weight(1f))
                    }
                }
                Text(
                    "---- 特殊权限 ----",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = bit(0b100_000_000_000), onCheckedChange = { setBit(0b100_000_000_000, it) })
                    Text("设置UID (SUID)", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = bit(0b010_000_000_000), onCheckedChange = { setBit(0b010_000_000_000, it) })
                    Text("设置GID (SGID)", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = bit(0b001_000_000_000), onCheckedChange = { setBit(0b001_000_000_000, it) })
                    Text("粘滞 (Sticky)", style = MaterialTheme.typography.bodySmall)
                }
                if (canRecurse) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = recurseFiles, onCheckedChange = { recurseFiles = it })
                        Text("同时应用到所有子文件", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = recurseDirs, onCheckedChange = { recurseDirs = it })
                        Text("同时应用到所有子文件夹", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "当前：" + Fmt.modeLong(mode, isDirectory, false) + "(" + Integer.toOctalString(mode and 0xFFF) + ")",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(mode, recurseFiles, recurseDirs) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

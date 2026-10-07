package com.u707t.panelfm.ui.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.vfs.FileMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/**
 * DualPaneScreen 的屏幕局部 UI 状态（重审 🔴1b 拆分产物）。
 *
 * 原本是主 Composable 里 36 个 `remember { mutableStateOf(...) }`；拆出顶栏 / 底栏 / 菜单 /
 * 对话框四个组件时集中到这里，组件用 `with(ds) { ... }` 共享同一份状态与读写。
 *
 * 注意：这里只放**屏幕局部**状态；持久业务状态（窗格、选择、任务…）仍在 BrowserController。
 */
internal class BrowserDialogsState {

    // 长按动作菜单 / 工具子菜单
    var rowAction by mutableStateOf<FileMetadata?>(null)
    var toolsFor by mutableStateOf<FileMetadata?>(null)

    // 重命名 / 删除 / 压缩 / 新建
    var renaming by mutableStateOf<FileMetadata?>(null)
    var deleting by mutableStateOf<List<FileMetadata>?>(null)
    var compressTargets by mutableStateOf<List<FileMetadata>?>(null)
    var creatingFolder by mutableStateOf(false)
    var creatingFile by mutableStateOf(false)
    var showCreateMenu by mutableStateOf(false)

    // 顶栏 ⋮ 菜单（含两级子菜单）
    var showMoreMenu by mutableStateOf(false)
    var hiddenSub by mutableStateOf(false)
    var browseSub by mutableStateOf(false)
    var showSortDialog by mutableStateOf(false)
    var sortManage by mutableStateOf(false)

    // 输入框 / 过滤 / 类型过滤
    var gotoPath by mutableStateOf(false)
    var filterInput by mutableStateOf(false)
    var showTypeFilter by mutableStateOf(false)

    // 搜索（对话框 + 结果 + 二次确认）
    var showSearch by mutableStateOf(false)
    var searchResults by mutableStateOf<List<FileMetadata>?>(null)
    var searchResultsOpen by mutableStateOf(false)
    var searchResultsDismissed by mutableStateOf(false)
    var searching by mutableStateOf(false)
    var searchStopped by mutableStateOf(false)
    var searchJob by mutableStateOf<Job?>(null)
    var refineInput by mutableStateOf(false)
    var searchAsk by mutableStateOf<Pair<Int, CompletableDeferred<Boolean>>?>(null)

    // 单窗口操作（长按「复制/移动 ->」）
    var singleWindowOp by mutableStateOf<TransferOp?>(null)
    var singleWindowTargets by mutableStateOf<List<FileMetadata>>(emptyList())

    // 权限 / 校验值消息 / 打开方式
    var permissionFor by mutableStateOf<FileMetadata?>(null)
    var message by mutableStateOf<Pair<String, String>?>(null)
    var openWithFor by mutableStateOf<FileMetadata?>(null)
    var openWithManage by mutableStateOf(false)

    // 批量重命名 / 压缩 / 解压 / 压缩包内重命名
    var batchRenameFor by mutableStateOf<List<FileMetadata>?>(null)
    var compressFormatPicker by mutableStateOf(false)
    var extractDirPicker by mutableStateOf(false)
    var extractDialogFor by mutableStateOf<FileMetadata?>(null)
    var archiveRename by mutableStateOf<FileMetadata?>(null)
}

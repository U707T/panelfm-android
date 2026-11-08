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
    /** 不参与重组：只在「停止 / 新搜索」时取消用；放 state 里会让每次搜索都多一次全屏重组 */
    var searchJob: Job? = null
    /**
     * 搜索代次：旧协程被取消后仍会跑完 catch 块的几行，只有当前代次允许写 UI 状态。
     * 旧实现没有这层隔离，重搜时旧协程会把 `searching` 置 false，新搜索在根目录就被掐断。
     *
     * `@Volatile`：`isCancelled` 在内容搜索的 IO 线程上读它，「停止」必须立刻可见。
     */
    @Volatile
    var searchGeneration: Int = 0
    var searchFailedDirs by mutableStateOf(0)
    var searchContentFailures by mutableStateOf(0)
    var refineInput by mutableStateOf(false)
    var searchAsk by mutableStateOf<Pair<Int, CompletableDeferred<Boolean>>?>(null)
    /** 本次搜索的起点路径（结果行显示相对路径用） */
    var searchRootPath by mutableStateOf<String?>(null)

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

    /**
     * 传输进度模态对话框是否打开（v2.3.2）。
     *
     * 传输入队 / 有任务在跑时自动置 true，用户点「后台运行」或按返回置 false；
     * 底部胶囊点回来再置 true。任务列表清空时由 [DualPaneScreen] 重置。
     */
    var transferDialogOpen by mutableStateOf(false)

    /**
     * 已经自动弹过对话框的任务 id。
     *
     * 为什么需要它：只看「有没有在传」不行 —— 用户点「后台运行」关掉对话框后，任务仍在传，
     * 条件恒为真，每 200ms 一次的快照发射会把对话框重新弹出来（等于关不掉）。
     * 记住弹过的 id，就只有**新任务**才会再次自动弹出；列表清空时重置。
     */
    var autoOpenedTaskIds by mutableStateOf<Set<String>>(emptySet())

    /** 新任务被冲突框挡下的那次自动弹出（冲突解决后补弹一次，见 DualPaneScreen）。 */
    var pendingAutoOpen by mutableStateOf(false)
}

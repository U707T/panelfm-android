package com.u707t.panelfm.ui.browser

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.Spacer
import com.u707t.panelfm.core.ui.DividerPx
import com.u707t.panelfm.core.ui.HSeparator
import com.u707t.panelfm.core.ui.IconTextButton
import com.u707t.panelfm.core.ui.MtActionButton
import com.u707t.panelfm.core.ui.MtBottomIconButton
import com.u707t.panelfm.core.ui.MtGesture
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtMenuRow
import com.u707t.panelfm.core.ui.LocalPanelDarkTheme
import com.u707t.panelfm.core.ui.MtSpec
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.ui.PaneEdgeShadow
import com.u707t.panelfm.core.ui.VDividerPx
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.FileSearch
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.data.PrefsStore
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.model.SortBy
import com.u707t.panelfm.core.model.SortSpec
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import com.u707t.panelfm.core.transfer.overallProgress
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.SpaceInfo
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.local.LocalVolume
import com.u707t.panelfm.core.vfs.local.LocalVolumes
import com.u707t.panelfm.ui.preview.OpenWithDialog
import com.u707t.panelfm.ui.preview.OpenWithManageDialog
import com.u707t.panelfm.ui.preview.OpenWithOption
import com.u707t.panelfm.ui.preview.PreviewMode
import com.u707t.panelfm.core.vfs.VfsUri
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// ================================================================================================
// 从 DualPaneScreen 拆出（重审 🔴1b）：全部对话框/输入框/搜索结果/状态提示/冲突与移动确认。
// ================================================================================================

@Composable
internal fun BrowserDialogHost(
    container: AppContainer,
    controller: BrowserController,
    ui: BrowserUiState,
    ds: BrowserDialogsState,
) {
    val focused = ui.focusedPane
    val focusSide = ui.focused
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by container.settings.collectAsState()
    with(ds) {
        // ---------------- 各种输入框
        if (gotoPath) {
            TextInputDialog(
                title = "跳转路径",
                initial = focused.uri.displayPath,
                label = "路径",
                hint = "可写完整 URI（s3://bucket/dir、/share/sub），也可写相对路径",
                onConfirm = { input ->
                    val text = input.trim()
                    val dest = if (text.contains("://")) runCatching { VfsUri.parse(text) }.getOrNull()
                    else focused.uri.withPath(if (text.startsWith("/")) text else "/$text")
                    if (dest != null) controller.open(focusSide, dest, focused.tab.connectionId, focused.tab.label)
                    else controller.showStatus("路径格式不正确")
                },
                onDismiss = { gotoPath = false },
            )
        }
        if (filterInput) {
            // MT `app:recordKey="filter_record"`：过滤词带历史
            TextInputDialog(
                title = "过滤",
                initial = focused.search,
                label = "关键字",
                hint = "普通文本=包含；!文本=不包含；/正则；!/正则=正则否定。留空清除。",
                history = settings.inputHistory[PrefsStore.RecordKeys.FILTER].orEmpty(),
                // 允许留空提交 = 清除过滤（旧实现空文本不提交 → 过滤设上就取消不了）
                allowEmpty = true,
                onConfirm = { q ->
                    controller.setSearch(focusSide, q)
                    // 「清除」时把类型过滤也一并取消，否则列表仍被类型条件卡住
                    if (q.isBlank() && focused.filterKind != null) controller.setFilter(focusSide, null)
                    if (q.isNotBlank()) scope.launch { container.prefs.addInputHistory(PrefsStore.RecordKeys.FILTER, q) }
                    controller.refresh(focusSide)
                },
                onDismiss = { filterInput = false },
            )
        }
        // MT 的「过滤」类型下拉（文件夹 / 图片 / 视频 / 音频 / 压缩包 / 文档…）：
        // 控制器与状态（PaneState.filterKind）早已支持，此前 UI 没有任何入口 → 补齐
        if (showTypeFilter) {
            val kinds = listOf(
                null to "全部类型",
                "dir" to "文件夹",
                com.u707t.panelfm.core.common.MimeTypes.Kind.IMAGE.name to "图片",
                com.u707t.panelfm.core.common.MimeTypes.Kind.VIDEO.name to "视频",
                com.u707t.panelfm.core.common.MimeTypes.Kind.AUDIO.name to "音频",
                com.u707t.panelfm.core.common.MimeTypes.Kind.ARCHIVE.name to "压缩包",
                com.u707t.panelfm.core.common.MimeTypes.Kind.APK.name to "APK",
                com.u707t.panelfm.core.common.MimeTypes.Kind.PDF.name to "PDF",
                com.u707t.panelfm.core.common.MimeTypes.Kind.FONT.name to "字体",
                com.u707t.panelfm.core.common.MimeTypes.Kind.CODE.name to "代码",
                com.u707t.panelfm.core.common.MimeTypes.Kind.TEXT.name to "文本",
            )
            AlertDialog(
                onDismissRequest = { showTypeFilter = false },
                title = { Text("类型过滤") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        kinds.forEach { (kind, label) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickableNoRipple {
                                        showTypeFilter = false
                                        controller.setFilter(focusSide, kind)
                                        controller.refresh(focusSide)
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = focused.filterKind == kind, onClick = null)
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showTypeFilter = false }) { Text("关闭") } },
            )
        }
        // MT 的搜索：文件名 + 搜索子目录 + 高级搜索（内容 / 大小范围）
        // v1.0 补：搜索结果上限询问（0x7f110430）、停止搜索（0x7f110686）、在当前结果中搜索（0x7f110619）
        if (showSearch) {
            MtSearchDialog(
                initialQuery = focused.search,
                history = settings.searchHistory,
                onSearch = { q, field, recursive, minSize, maxSize ->
                    val hasSizeFilter = minSize >= 0 || maxSize >= 0
                    // MT 0x7f110602「正则表达式有误」：写错的正则不再静默（旧实现会全命中 / 全不匹配）
                    val regexInvalid = field == SearchField.REGEX && FileSearch.regexError("/$q")
                    when {
                        regexInvalid -> controller.showStatus("正则表达式有误")
                        // 仅当前目录 + 无大小条件：等价于目录内过滤（沿用 /regex、!text、* 通配语法）
                        !recursive && !hasSizeFilter && field == SearchField.NAME -> controller.setSearch(focusSide, q)
                        !recursive && !hasSizeFilter && field == SearchField.REGEX -> controller.setSearch(focusSide, "/$q")
                        else -> {
                            searching = true
                            searchStopped = false
                            searchResults = emptyList()
                            searchResultsOpen = true
                            searchResultsDismissed = false
                            searchRootPath = focused.uri.displayPath
                            searchJob?.cancel()
                            searchJob = scope.launch {
                                container.prefs.addSearchQuery(q)
                                // 注意：**不能用 runCatching 包 `searchTree`** —— 点「停止搜索」时
                                // `searchJob.cancel()` 会让它抛 CancellationException，而 runCatching
                                // 会把「取消」当成失败：弹一条「搜索失败：Job was cancelled」，
                                // 并把刚才的「已停止搜索（已找到 N 条）」顶掉。
                                val outcome = try {
                                    controller.searchTree(
                                        side = focusSide,
                                        nameQuery = if (field == SearchField.CONTENT) "" else q,
                                        recursive = recursive,
                                        contentQuery = if (field == SearchField.CONTENT) q else "",
                                        minSize = minSize,
                                        maxSize = maxSize,
                                        nameRegex = field == SearchField.REGEX,
                                        // MT 0x7f110430：到 300 条先问「你确定继续搜索？」
                                        confirmEvery = 300,
                                        onAskContinue = { n ->
                                            val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
                                            searchAsk = n to gate
                                            gate.await()
                                        },
                                        isCancelled = { !searching },
                                        onPartial = { partial ->
                                            searchResults = partial
                                            // 只在用户没有收起结果时自动刷新弹窗（F13）
                                            if (!searchResultsDismissed) searchResultsOpen = true
                                        },
                                    )
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    null        // 取消不是错误；「已停止搜索」已由 onStop 提示过
                                } catch (e: Exception) {
                                    controller.showStatus((e as? VfsException)?.userMessage ?: "搜索失败：${e.message}")
                                    null
                                }
                                searching = false
                                if (outcome != null) {
                                    searchResults = outcome.items
                                    searchStopped = outcome.stopped
                                    if (!searchResultsDismissed) {
                                        searchResultsOpen = true
                                    } else {
                                        controller.showStatus("搜索完成：${outcome.items.size} 条（可从 ⋮ →「搜索结果」查看）")
                                    }
                                    if (outcome.stopped && outcome.items.size >= 300) {
                                        controller.showStatus("搜索结果数量过多，已停止搜索")
                                    }
                                }
                            }
                        }
                    }
                },
                onDismiss = { showSearch = false },
            )
        }
        if (searchResultsOpen) searchResults?.let { results ->
            MtSearchResultsDialog(
                results = results,
                searching = searching,
                stopped = searchStopped,
                rootPath = searchRootPath,
                onStop = {
                    // MT 0x7f110686「停止搜索」
                    searchJob?.cancel()
                    searching = false
                    searchStopped = true
                    controller.showStatus("已停止搜索（已找到 ${results.size} 条）")
                },
                onRefine = {
                    // MT 0x7f110619「在当前结果中搜索」：在现有结果里再筛（复用目录内过滤语法）
                    refineInput = true
                },
                onClear = {
                    searchResults = null
                    searchResultsOpen = false
                    searchResultsDismissed = false
                    searchStopped = false
                    searchRootPath = null
                    controller.showStatus("已清除搜索")
                },
                onPick = { item ->
                    // F13：收起但保留结果数据（可从 ⋮ →「搜索结果」重开）；后台搜索不再把它顶回来
                    searchResultsOpen = false
                    searchResultsDismissed = true
                    controller.reveal(focusSide, item.uri)
                },
                onDismiss = {
                    searchResultsOpen = false
                    searchResultsDismissed = true
                    if (searching) {
                        controller.showStatus("搜索继续中（已找到 ${searchResults?.size ?: 0} 条）：可从 ⋮ →「搜索结果」重新打开")
                    }
                },
            )
        }
        if (refineInput) {
            TextInputDialog(
                title = "在当前结果中搜索",
                initial = "",
                label = "关键字",
                hint = "在当前 ${searchResults?.size ?: 0} 条结果里再筛（支持 /正则、!否定）",
                onConfirm = { keyword ->
                    val base = searchResults.orEmpty()
                    searchResults = base.filter { controller.matchesSearch(it.name, keyword) }
                    refineInput = false
                },
                onDismiss = { refineInput = false },
            )
        }
        // MT 0x7f110430「已搜索到 %s 个结果，你确定继续搜索？」（暂停搜索等回答）
        searchAsk?.let { (count, gate) ->
            AlertDialog(
                onDismissRequest = { searchAsk = null; gate.complete(false) },
                title = { Text("搜索结果较多") },
                text = { Text("已搜索到 $count 个结果，你确定继续搜索？") },
                confirmButton = {
                    TextButton(onClick = { searchAsk = null; gate.complete(true) }) { Text("继续搜索") }
                },
                dismissButton = {
                    TextButton(onClick = { searchAsk = null; gate.complete(false) }) { Text("停止搜索") }
                },
            )
        }
        if (singleWindowOp != null) {
            val op = singleWindowOp!!
            TextInputDialog(
                title = if (op == TransferOp.COPY) "复制到（本窗格内）" else "移动到（本窗格内）",
                initial = focused.uri.displayPath,
                label = "目标目录",
                hint = "单窗口操作：目标仍在当前窗格内，输入目录后立即执行",
                onConfirm = { path ->
                    val dest = focused.uri.withPath(if (path.startsWith("/")) path else "/$path")
                    // F8：显式目标必须跟着菜单长按的那次操作走（onDismiss 会在提交后被调用，先取出来）
                    val targets = singleWindowTargets.map { it.uri }
                    if (op == TransferOp.COPY) controller.copyWithinPane(focusSide, dest, targets)
                    else controller.moveWithinPane(focusSide, dest, targets)
                },
                onDismiss = {
                    singleWindowOp = null
                    singleWindowTargets = emptyList()
                },
            )
        }
        permissionFor?.let { item ->
            MtPermissionDialog(
                fileName = item.name,
                isDirectory = item.isDirectory,
                initialMode = item.permissions,
                canRecurse = item.isDirectory,
                onDismiss = { permissionFor = null },
                onConfirm = { mode, recurseFiles, recurseDirs ->
                    permissionFor = null
                    controller.changePermissions(item.uri, mode, recurseFiles, recurseDirs)
                },
            )
        }
        // 解压（复刻 MT 0x7f0c00ce「解压」）
        //  · extractDirPicker = 在压缩包**内部**时打开（目标默认本目录）
        //  · extractDialogFor = 在文件列表里长按**压缩包本身**时打开（MT 的三项：当前目录 / 单独文件夹 / 文件夹…）
        if (extractDirPicker || extractDialogFor != null) {
            val pickedArchive = extractDialogFor
            val encoded = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(focused.uri.path)
            val host = encoded?.let { VfsUri.decodeHost(it) }?.let { runCatching { VfsUri.parse(it) }.getOrNull() }
            val archiveParent = pickedArchive?.uri?.parent ?: host?.parent
            val archiveName = pickedArchive?.name ?: host?.name ?: "压缩包"
            val otherPaneUri = ui.pane(focusSide.other).uri.takeIf { it.scheme != "archive" }
            // F10：路径输入的解析锚点（压缩包内 = 压缩包宿主存储；否则 = 当前目录所在存储）
            val pathAnchor = host ?: focused.uri
            // F10：路径输入的对照基准（与它相同 = 未自定义 → 保持原「选择当前目录」模式）
            val basePath = if (pickedArchive != null) focused.uri.displayPath
            else archiveParent?.displayPath ?: focused.uri.displayPath
            MtExtractDialog(
                archiveName = archiveName.substringBeforeLast('.', archiveName),
                currentDirPath = basePath,
                otherPanePath = otherPaneUri?.displayPath,
                onDismiss = { extractDirPicker = false; extractDialogFor = null },
                onConfirm = { target, customPath, useOtherPane ->
                    val archiveItem = pickedArchive
                    extractDirPicker = false
                    extractDialogFor = null
                    // 把「目标路径 / 另一窗口路径」解析成真实目标；解析不出来 = null（走选择目录模式）
                    val typed = typedExtractPath(customPath, basePath)
                    val direct: VfsUri? = if (target == ExtractTarget.PICK_FOLDER) {
                        when {
                            useOtherPane && otherPaneUri != null -> otherPaneUri
                            typed != null -> runCatching { pathAnchor.withPath(typed) }.getOrNull()
                            else -> null
                        }
                    } else null
                    if (archiveItem != null) {
                        // 长按文件列表里的压缩包：按三项语义解压该压缩包
                        when (target) {
                            ExtractTarget.OWN_FOLDER -> archiveItem.uri.parent?.let {
                                controller.extractArchiveTo(archiveItem, it, ownFolder = true)
                            } ?: controller.showStatus("无法确定压缩包所在目录")
                            ExtractTarget.HERE -> controller.extractArchiveTo(archiveItem, focused.uri)
                            ExtractTarget.PICK_FOLDER ->
                                if (direct == null) {
                                    controller.startPickArchiveExtract(archiveItem)
                                } else {
                                    // F10：先校验目标目录真实存在（输入框里的路径不再被忽略）
                                    container.scope.launch {
                                        val ok = runCatching { container.locator.find(direct)?.stat(direct)?.isDirectory == true }
                                            .getOrDefault(false)
                                        if (ok) controller.extractArchiveTo(archiveItem, direct)
                                        else controller.showStatus("目标目录不存在或不是文件夹：${direct.displayPath}")
                                    }
                                }
                        }
                    } else {
                        when (target) {
                            ExtractTarget.OWN_FOLDER ->
                                if (archiveParent != null) controller.extractToOwnFolder(focusSide, archiveParent)
                                else controller.showStatus("无法确定压缩包所在目录")
                            ExtractTarget.HERE -> controller.extractTo(focusSide, focused.uri)
                            ExtractTarget.PICK_FOLDER ->
                                if (direct == null) {
                                    // MT 0x7f0c0025：进入「选择当前目录」模式，用户浏览到目标后点底栏的确认按钮
                                    controller.startPickDir(PickDirPurpose.EXTRACT)
                                } else {
                                    container.scope.launch {
                                        val ok = runCatching { container.locator.find(direct)?.stat(direct)?.isDirectory == true }
                                            .getOrDefault(false)
                                        if (ok) controller.extractTo(focusSide, direct)
                                        else controller.showStatus("目标目录不存在或不是文件夹：${direct.displayPath}")
                                    }
                                }
                        }
                    }
                },
            )
        }
    
        // 压缩（复刻 MT 0x7f0c0080「创建压缩文件」：文件名 / 格式 / 压缩级别 / 密码 / 同时加密文件名）
        if (compressFormatPicker) {
            val targets = compressTargets ?: focused.selectedItems
            val targetUris = targets.map { it.uri }
            MtCompressDialog(
                itemCount = targets.size,
                onDismiss = { compressFormatPicker = false; compressTargets = null },
                onConfirm = { toOther, fmt, fileName, level, pwd ->
                    compressFormatPicker = false
                    if (toOther) {
                        controller.compressToOther(focusSide, fmt, fileName, level, pwd, overrideSources = targetUris)
                    } else {
                        controller.compressHere(focusSide, fmt, fileName, level, pwd, overrideSources = targetUris)
                    }
                    compressTargets = null
                },
            )
        }
        // 压缩包口令（第 5 批 🔴1：加密包读侧接线 —— 进入 / 解压 / 完整性测试前的输入框）
        ui.archivePassword?.let { ask ->
            ArchivePasswordDialog(
                archiveName = ask.name,
                wrong = ask.wrong,
                onConfirm = { controller.submitArchivePassword(it) },
                onDismiss = { controller.submitArchivePassword(null) },
            )
        }
        // 压缩包内重命名（完整路径，可改父目录 = 移动）
        archiveRename?.let { item ->
            TextInputDialog(
                title = "重命名（压缩包内）",
                initial = item.name,
                label = "完整路径（可含目录）",
                hint = "MT 语义：重命名的是完整路径，改父目录即为移动",
                onConfirm = { newPath -> controller.renameInsideArchive(focusSide, item, newPath) },
                onDismiss = { archiveRename = null },
            )
        }
        message?.let { (title, body) ->
            MessageDialog(title, body) { message = null }
        }
        // 打开方式（MT：内置打开方式列表 + 长按设为默认 + 管理）
        openWithFor?.let { item ->
            // 组合期不查库（旧实现直接在参数里调 controller.defaultOpenMode → 每次重组查一次 SQLite）
            val openWithDefaultMode by produceState<com.u707t.panelfm.ui.preview.PreviewMode?>(
                initialValue = null,
                item.uri,
            ) {
                value = controller.defaultOpenModeSuspend(item)
            }
            val kind = MimeTypes.kindOf(item.extension)
            OpenWithDialog(
                fileName = item.name,
                mimeType = item.mimeType,
                localFile = item.uri.scheme == "local",
                options = listOf(
                    OpenWithOption(PreviewMode.TEXT, available = kind == MimeTypes.Kind.TEXT || kind == MimeTypes.Kind.CODE || kind == MimeTypes.Kind.OTHER),
                    OpenWithOption(PreviewMode.EDITOR, available = kind != MimeTypes.Kind.IMAGE && kind != MimeTypes.Kind.AUDIO && kind != MimeTypes.Kind.VIDEO),
                    OpenWithOption(PreviewMode.RENDER, available = com.u707t.panelfm.core.common.RenderFormats.isRenderable(item.extension)),
                    OpenWithOption(PreviewMode.SQLITE, available = com.u707t.panelfm.core.common.SqliteFormats.isSqlite(item.extension)),
                    OpenWithOption(PreviewMode.EPUB, available = item.extension.equals("epub", ignoreCase = true)),
                    OpenWithOption(PreviewMode.IMAGE, available = kind == MimeTypes.Kind.IMAGE),
                    OpenWithOption(PreviewMode.MEDIA, available = kind == MimeTypes.Kind.AUDIO || kind == MimeTypes.Kind.VIDEO),
                    OpenWithOption(
                        PreviewMode.ARCHIVE,
                        available = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.ArchiveKind.ofFileName(item.name) != null,
                    ),
                    OpenWithOption(PreviewMode.FONT, available = kind == MimeTypes.Kind.FONT),
                    OpenWithOption(PreviewMode.PDF, available = kind == MimeTypes.Kind.PDF),
                    OpenWithOption(PreviewMode.APK_INFO, available = kind == MimeTypes.Kind.APK),
                    OpenWithOption(PreviewMode.OFFICE, available = kind == MimeTypes.Kind.DOCUMENT),
                    OpenWithOption(PreviewMode.SYSTEM, available = item.uri.scheme == "local"),
                ),
                defaultMode = openWithDefaultMode,
                onPick = { mode ->
                    openWithFor = null
                    if (mode == PreviewMode.SYSTEM) {
                        openWithSystem(container, context, item) { msg -> controller.showStatus(msg) }
                    } else {
                        controller.openWith(item, mode)
                    }
                },
                onPickSystem = { app ->
                    openWithFor = null
                    openWithSystemApp(container, context, item, app) { msg -> controller.showStatus(msg) }
                },
                onSetDefault = { mode ->
                    controller.setDefaultOpenMode(item, mode)
                },
                onManage = { openWithManage = true },
                onDismiss = { openWithFor = null },
            )
        }
        if (openWithManage) {
            // 组合期不查库：进入对话框时异步读一次，增删后再刷新
            var entries by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
            var reloadAt by remember { mutableStateOf(0) }
            LaunchedEffect(reloadAt) { entries = controller.openModesSuspend() }
            OpenWithManageDialog(
                entries = entries,
                onDelete = { ext -> controller.clearOpenMode(ext); reloadAt++ },
                onDismiss = { openWithManage = false },
            )
        }
        // 批量重命名（MT 表达式 + 查找替换；表达式/查找/替换三处都带 `recordKey` 历史）
        batchRenameFor?.let { items ->
            BatchRenameDialog(
                items = items,
                patternHistory = settings.inputHistory[PrefsStore.RecordKeys.RENAME_PATTERN].orEmpty(),
                findHistory = settings.inputHistory[PrefsStore.RecordKeys.RENAME_SEARCH].orEmpty(),
                replaceHistory = settings.inputHistory[PrefsStore.RecordKeys.RENAME_REPLACE].orEmpty(),
                onConfirm = { expression, find, replace, useRegex ->
                    scope.launch {
                        container.prefs.addInputHistory(PrefsStore.RecordKeys.RENAME_PATTERN, expression)
                        if (find.isNotBlank()) container.prefs.addInputHistory(PrefsStore.RecordKeys.RENAME_SEARCH, find)
                        if (replace.isNotBlank()) container.prefs.addInputHistory(PrefsStore.RecordKeys.RENAME_REPLACE, replace)
                        var ok = 0
                        items.forEachIndexed { index, fm ->
                            val newName = BatchRename.newName(expression, fm, index, find, replace, useRegex)
                            if (newName != fm.name && newName.isNotBlank()) {
                                val target = fm.uri.parent?.child(newName)
                                val vfs = container.locator.find(fm.uri)
                                if (target != null && vfs != null) {
                                    // 走 VFS 调度器（旧实现直接在 UI 协程里同步调用网络重命名 → 主线程卡顿）
                                    val done = kotlinx.coroutines.withContext(container.dispatchers.vfs) {
                                        runCatching { vfs.rename(fm.uri, target) }.getOrDefault(false)
                                    }
                                    if (done) ok++
                                }
                            }
                        }
                        controller.showStatus("批量重命名完成：$ok / ${items.size}")
                        controller.clearSelection(focusSide)
                        controller.refreshAll()
                    }
                },
                onDismiss = { batchRenameFor = null },
            )
        }
        renaming?.let { item ->
            TextInputDialog(
                title = "重命名",
                initial = item.name,
                onConfirm = { newName -> controller.rename(item.uri, newName, focusSide) },
                onDismiss = { renaming = null },
            )
        }
        deleting?.let { targets ->
            val first = targets.firstOrNull()
            var bigCount by remember(first?.uri) { mutableStateOf(-1) }
            LaunchedEffect(first?.uri) {
                bigCount = if (first != null && first.isDirectory && first.uri.scheme == "local") controller.countLocalEntries(first.uri) else -1
            }
            // 目标列表由调用方给定：顶栏动作条 = 当前选择集；长按菜单 = 「这一项」（或包含它时=选择集）
            val delCount = targets.size
            val delSources = targets.map { it.uri }
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("删除") },
                text = {
                    Column {
                        Text(
                            if (delCount > 1) "确定删除已选中的 $delCount 项？"
                            else "确定删除「${first?.name ?: ""}」？" + if (first?.isDirectory == true) "（含目录内容）" else ""
                        )
                        if (bigCount > 1000) {
                            Text(
                                "该目录含 $bigCount+ 个文件：可用「极速删除」直接清理（不进回收站，秒级完成）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    Row {
                        if (bigCount > 1000) {
                            TextButton(onClick = {
                                controller.deleteSelected(focusSide, fastDelete = true, overrideSources = delSources)
                                deleting = null
                            }) { Text("极速删除") }
                        }
                        TextButton(onClick = {
                            controller.deleteSelected(focusSide, overrideSources = delSources)
                            deleting = null
                        }) { Text("删除") }
                    }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
            )
        }
        if (creatingFolder) {
            TextInputDialog(
                title = "新建文件夹",
                label = "文件夹名称",
                onConfirm = { name -> controller.createFolder(focusSide, name) },
                onDismiss = { creatingFolder = false },
            )
        }
        if (creatingFile) {
            TextInputDialog(
                title = "新建文件",
                label = "文件名",
                onConfirm = { name -> controller.createFile(focusSide, name) },
                onDismiss = { creatingFile = false },
            )
        }
    
        // ---------------- 排序对话框（复刻 MT：单选项 + 仅应用于此文件夹 / 逆向排序 + 管理/取消/确定）
        if (showSortDialog) {
            MtSortDialog(
                paneLabel = if (focusSide == PaneSide.LEFT) "左窗口" else "右窗口",
                initial = controller.sortSpecFor(focusSide),
                folderRuleExists = controller.hasFolderSortRule(focused.uri),
                onManage = { showSortDialog = false; sortManage = true },
                onConfirm = { spec, folderOnly ->
                    showSortDialog = false
                    controller.applySort(focusSide, spec, folderOnly)
                },
                onDismiss = { showSortDialog = false },
            )
        }
        if (sortManage) {
            MtSortManageDialog(
                rules = controller.folderSortRules(),
                onClear = { controller.clearFolderSorts(); sortManage = false },
                onDismiss = { sortManage = false },
            )
        }
    
        // ---------------- 状态提示（队列：一条条显示，不互相顶掉；错误类停更久 —— 审计 U9）
        val statusQueue by controller.statusQueue.collectAsState()
        statusQueue.firstOrNull()?.let { msg ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Snackbar(
                    modifier = Modifier.padding(bottom = 66.dp),
                    action = { TextButton(onClick = { controller.consumeStatus() }) { Text("知道了") } },
                ) { Text(msg) }
            }
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(controller.statusDurationMs(msg))
                controller.consumeStatus()
            }
        }
    
        // ---------------- 冲突 / 移动确认 / 属性
        ui.renameConflict?.let { conflict ->
            AlertDialog(
                onDismissRequest = { controller.dismissRenameConflict() },
                title = { Text("目标名称已存在") },
                text = { Text("「${conflict.target.name}」已存在，与源文件同名（都不是文件夹）。请选择处理方式：") },
                confirmButton = { TextButton(onClick = { controller.resolveRenameConflict("swap") }) { Text("交换") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = { controller.resolveRenameConflict("delete") }) { Text("删除目标") }
                        TextButton(onClick = { controller.resolveRenameConflict("backup") }) { Text("备份(.bak)") }
                        TextButton(onClick = { controller.dismissRenameConflict() }) { Text("取消") }
                    }
                },
            )
        }
        ui.pendingMove?.let { pending ->
            MoveConfirmDialog(pending, onConfirm = { controller.confirmMove() }, onCancel = { controller.cancelMove() })
        }
        ui.conflict?.let { info ->
            ConflictDialog(
                info = info,
                dialogIconMode = settings.dialogIconMode,
            ) { policy, applyAll -> controller.resolveConflict(policy, applyAll) }
        }
        ui.diff?.let { diff ->
            FolderDiffDialog(
                result = diff,
                onCopyOnlyLeft = { controller.copyDiffOnlyLeft() },
                onCopyNewer = { controller.copyDiffNewer() },
                onDismiss = { controller.dismissDiff() },
            )
        }
        ui.property?.let { item ->
            PropertiesDialog(container, item) {
                controller.dismissProperties()
            }
        }
    }
}

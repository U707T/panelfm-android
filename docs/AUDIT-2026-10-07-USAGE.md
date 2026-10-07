# PanelFM 「实际使用」全量审计（所有功能路径 · 2026-10-07 第二轮）

> **范围**：用户**真正拿在手里会走的每一条路** —— 从启动、双列浏览、选择、文件操作、压缩/解压、
> 传输任务、各类预览（图片/文本/媒体/PDF/字体/APK/Office）、编辑器、网络存储、搜索/过滤/排序、
> 回收站/工具、到设置；重点找「**用起来不对/不像承诺的那样/点了没反应**」的问题。
>
> **基线**：`main` @ `3e1e5ed`（v1.9.2，已发版；CI run 74 ✅ / Release v1.9.2 已发布）。
> 本文所有行号 = **HEAD 基线**（文件:行号可用 `git show 3e1e5ed:<path>` 复核；见 §7 命令）。
>
> **方法**：只读审计（逐条读代码 + 全仓 grep 取证 + 与既有单测/文档对表）；**未跑真机**。
> 未改动任何源码。
>
> ⚠️ **快照声明（重要）**：审计期间（16:35–16:45）工作树正被**另一个会话持续修改**——
> 该会话在实现「长操作反馈（审计 U4）」：`BusyOps.kt`（新增）、`BrowserController`、
> `DualPaneScreen`、`AppContainer`、`FolderDiff`、`ArchiveCompressor` + `BusyReporterTest`。
> 本文**不覆盖**这部分在途工作（见 §4），引用的行号一律以 **HEAD（3e1e5ed）** 为准，
> 不受工作树变动影响。若在途改动推进到 §1 的 F7/F8，请以**最新工作树**复核一次。
>
> 与既有审计的分工（本文不重复）：`AUDIT-2026-10-05-CODE-TRUTH.md`（正确性/死代码）、
> `AUDIT-UX-2026-10-05.md`（基础体验 U1–U20）、`SECURITY-REVIEW-2026-10-06.md`、
> `MT-ALIGNMENT-REVIEW-2026-10-06.md`、`AUDIT-2026-10-07-EXPERIENCE.md`（交互专项 F1–F6）。
> 本文的新问题从 **F7** 起编号。
>
> **修复状态（2026-10-07 深夜 · v1.10.0）**：本文 **F7–F16 已全部修复** ——
> F7/F8（两个「显式目标」P1，含回归测试）、F9 顶栏分享多选、F10 解压对话框死控件（实现路径输入 /
> 另一窗口路径语义）、F11 文案与注释对齐、F12 字体失败态、F13 搜索窗口「收起后不再自动弹回」（⋮ 可重开）、
> F14/F15 不再静默取整个目录、F16 聚焦批。§2 存量项中 U7b/U11/U16/U18/U19 也随本版修复；
> U10/U13/U17 仍未做。完整清单与验证见 `CHANGELOG.md` v1.10.0。

---

## 0. 结论摘要

| 级别 | 条数 | 内容 |
|---|---|---|
| **P1（会做错事，v1.9.1 修复的漏网入口）** | **2** | F7「压缩 → 保存到另一窗口」时**显式目标没生效**，实际压缩的是**选择集/整个目录**；F8 长按菜单的**单窗口「复制 -> / 移动 ->」**（长按项）目标退化为**整个目录** |
| P2（明显别扭 / 死控件 / 卡住） | 4 | F9 顶栏「分享」多选只发一个文件；F10 解压对话框两个**死控件**（输入路径被忽略、勾选框无行为）；F12 字体预览失败时**永远停在「解析字体…」**；F13 搜索结果窗口**关掉会自动弹回来** |
| P2（文案与行为不符） | 1 | F11 设置页手势说明 + PaneView/DualPaneScreen 头注释仍描述 **v1.9.0 已删除**的交互（长按锚点/右滑菜单/跟手扫选） |
| P3（小口径问题） | 3 | F14「添加对面选中项到压缩包」无选择时静默改成整个目录；F15 ⋮「复制到剪贴板」无选择时整个目录入剪贴板；F16 搜索/压缩/批量重命名等对话框仍不自动聚焦（U3 只修了 TextInputDialog 一族） |
| 存量清单状态变化 | 6 项 | U4 在途（§4）；**U3 修正为「部分修」**；U14 已闭环；U12 关闭（Hex 已下线）；U7b/U10/U11/U13/U16/U17/U18/U19 仍未修（§2） |
| 核对过无问题 | 18 组 | 见 §3（防重复劳动） |

> **两个 P1 的共性**：都是「**长按菜单显式目标**」这条链上的**漏接入口** —— v1.9.1 修 F1 时
> 加了 `overrideSources/overrideItems` 参数，但 `compressToOther` **只加了参数、没改函数体**
> （见 F7 证据），单窗口复制/移动干脆没接。这类「显式目标必须端到端贯通」的检查清单值得固化。

---

## 1. 新增问题（证据链）

### F7（P1 · v1.9.1 修复漏接）长按菜单「压缩 → 保存到另一窗口」：显式目标被忽略 → 压错内容

**现象（照实际操作的路径）**：长按某个文件（当前**没有任何多选**）→「压缩」→ 保存位置选
**「另一窗口」** → 确定。结果：压缩的是**当前目录的全部内容**（若之前有选择集，则是**整个选择集**），
不是长按的那一项；包名也随之来自被误取的目标集合（`zipNameFor(sources)`）。

**证据链（HEAD）**：
- 调用方**已经**传了显式目标：`DualPaneScreen.kt:1369`
  `controller.compressToOther(focusSide, fmt, fileName, level, pwd, encNames, overrideSources = targetUris)`；
- 函数**声明了参数**：`BrowserController.kt:581` `overrideSources: List<VfsUri>? = null`（doc 写明「长按菜单按『这一项』压缩时传入」）；
- **但函数体没用它**：`BrowserController.kt:586` `val sources = targetSources(side)`
  —— `targetSources` 在**没有选择**时返回 `pane.items`（**整个目录**，`BrowserController.kt:967-970`）；
- 对照：同一批修复里 `compressHere` 是接对的（`1891` `overrideSources ?: targetSources(side)`）、
  删除（`1044`）、复制到剪贴板（`1809`）、跨窗格复制/移动（`1601/1621`）都接对了；
- 归因（可复核）：`git show 2027b24` 的 diff 显示当时**只给 compressToOther 加了参数行**（hunk @@ -560,6 +560,8 @@），
  **函数体没有改动**；参数由那次提交引入（`git blame` 581 = 2027b24，586 = 更早的 bf1308b9）。
- 触发条件补充：对话框默认是「当前目录」（`Dialogs.kt:595` `toOther=false`），所以**默认路径没事**；
  只有用户选「另一窗口」才踩中。另一种踩法：有选择集时，长按**不在选择集里**的项 → 压缩 → 另一窗口
  → 实际压的是整个选择集。

**影响**：静默产出错误内容的压缩包（可能 GB 级、与用户点击意图完全不同）；用户往往到解压/上传时才发现。
与 F1（v1.9.1 修的「长按删整个目录」）同一形状，属**高危误操作**；不直接丢数据，故定 P1。

**建议修法**（一行）：`val sources = overrideSources ?: targetSources(side)`；顺手把「长按 → 压缩」这条
链路补一条回归测试（思路同 `MenuTargetsTest`：显式目标必须端到端到达 `TransferRequest`）。
⚠️ 在途会话正在改这个函数（U4 进度接入），**建议在其落地后合并**。

### F8（P1）长按菜单「复制 -> / 移动 ->」的**长按**（单窗口操作）目标退化为整个目录

**现象**：长按一个文件（无多选）→ 在动作菜单里**长按**带 ● 的「复制 ->」（菜单顶部提示
「带 ● 的菜单表示可以长按触发单窗口操作」）→ 弹「复制到（本窗格内）」→ 输入目标目录 → 确定。
结果：复制/移动的是**当前目录的全部内容**（有选择集时是**整个选择集**），不是长按的那一项。
「移动」同理，最坏情况是把整个目录内容搬去别处、或报「目标位于源内部，无法操作」。

**证据链（HEAD）**：
- 菜单长按只记录了一个**操作枚举**，没带目标：`DualPaneScreen.kt:1028-1029`
  `"copy_to" -> singleWindowOp = TransferOp.COPY` / `"move_to" -> singleWindowOp = TransferOp.MOVE`；
- 确认时调用没有目标的入口：`DualPaneScreen.kt:1297-1298`
  `controller.copyWithinPane(focusSide, dest)` / `moveWithinPane(...)`；
- 下游用隐式回退取源：`BrowserController.kt:1860-1874` `enqueueWithinPane` →
  `val sources = targetSources(side)` → **没有选择 = 整个目录**（`967-970`）。
- 对照：普通点按的「复制 -> / 移动 ->」走的是 `copyToOther(overrideSources=pickedUris)` /
  `moveToOther(overrideItems=picked)`（`DualPaneScreen.kt:961/963`）——**只有「长按该项」这一条漏了**。

**建议修法**：菜单长按时把**当时的 picked** 一起存进状态（例如 `singleWindowOp: Pair<TransferOp, List<FileMetadata>>?`），
确认时经 `overrideSources/overrideItems` 传给 `startCrossPane`；`enqueueWithinPane` 增加显式目标参数。
同样等 U4 在途改动落地后合并。

### F9（P2 · F4 修复漏接）顶栏动作条「分享」在多选时只发出去第一个文件

**现象**：多选 2+ 个文件 → 顶栏动作条点「分享」→ 系统分享面板里只有**第一个**文件。
（长按菜单里的「分享」已修好，多选会走 `ACTION_SEND_MULTIPLE` 全发出去。）

**证据链（HEAD）**：
- 顶栏传入的是**单文件包装**：`DualPaneScreen.kt:421-423`
  `onShare = { item: FileMetadata -> shareItem(container, context, item) ... }`；
- `TopActionItems` 只取第一个：`DualPaneScreen.kt:1707-1709`
  `MtActionButton(... "分享" ...) { picked.firstOrNull()?.let(onShare) }`；
- `shareItem` = `shareItems(listOf(item))`（`DualPaneScreen.kt:1934-1939`）。
- 对照：长按菜单已是 `shareItems(container, context, picked)`（`DualPaneScreen.kt:983`）。

**建议修法**：`onShare` 改成接收 `List<FileMetadata>`（直接复用 `shareItems(picked)`），
两处入口共用一份实现。

### F10（P2）解压对话框两个死控件：「基于另一窗口路径」与「目标路径」输入框都不生效

**现象**：长按压缩包 →「解压到文件夹…」→ 对话框里有「目标路径」输入框和「基于另一窗口路径」勾选框：
- 在输入框里输入路径 → 确定 → **输入被忽略**，实际进入「选择当前目录」模式（底栏出现「解压到所选目录」）；
- 勾选「基于另一窗口路径（/xxx）」→ **没有任何行为**。

**证据链（HEAD）**：
- `Dialogs.kt:729-730` 两个局部状态：`customPath`（可编辑，755-756）、`useOtherPane`（只被写入、**从未被读取**，766/770）；
- 确认时把 `customPath` 传给调用方：`Dialogs.kt:778`；
- **唯一调用方完全忽略它**：`DualPaneScreen.kt:1330-1365` 的 `onConfirm = { target, customPath -> ... }`
  三个分支里从未引用 `customPath`（`PICK_FOLDER` 一律走 `startPickArchiveExtract` / `startPickDir`）；
  也没有任何分支读 `useOtherPane`（它甚至没被传出来）。

**建议修法**（二选一，推荐前者）：实现输入框语义——`PICK_FOLDER` 时若 `customPath` 非空且与当前目录不同，
解析路径（相对/URI）后直接 `extractArchiveTo(archiveItem, parsed)`；「基于另一窗口路径」勾选 = 用对面窗格
路径预填/直接解压。或者**删掉这两个控件**，只保留 MT 的「选择当前目录」模式，别让用户对着死控件输入。

### F11（P2）设置页手势说明 / 关键注释仍描述 v1.9.0 已删除的交互

**现象**：设置 →「手势（复刻 MT 交互）」下的说明文字写着：
「长按 = 锚点 + 进入多选；**长按第二项 = 连选区间**」「已多选态右滑 ≥48dp = 滑出该项的更多操作」。
而 v1.9.0（用户实机对照 MT 后）已把这些行为**全部删除/推翻**：长按只弹菜单、不改选择不进多选；
右滑菜单不存在；没有「按住一路刷」的跟手扫选。用户照着提示去学，怎么做都不对。

**证据链（HEAD）**：
- 用户可见文案：`SettingsScreen.kt:121-123`（上述三行）；
- 同类过时注释（开发者视角）：`PaneView.kt:91-95`（「继续滑过行间 = 区间跟手」「已多选态右滑 ≥48dp = 呼出更多操作」
  「扫选可以一路扫到屏幕外（边缘自动滚动）」）、`DualPaneScreen.kt:117`（「继续滑过行间 = 连续区间选择」）；
- 现行语义的正确文档在 `docs/SELECTION-MODEL.md`（v1.9.0 重写：长按弹菜单 / 滑动离散选中 / 再滑动连区间）。

**建议修法**：按 `SELECTION-MODEL.md` 重写这三处文案/注释（纯文案批，可随任意改动一起带上）。

### F12（P2）字体预览失败时永远停在「解析字体…」（无错误提示、无兜底）

**现象**：点开一个损坏/不受支持的字形文件 → 页面永远转「解析字体…」，没有失败提示、没有下一步引导。

**证据链（HEAD）**：`FontScreen.kt:75` `}.getOrNull()` —— 加载/解析失败的异常被吞掉；
`FontScreen.kt:77` `typeface = tf`（失败 = 保持 null）；
`FontScreen.kt:93` `if (tf == null) { LoadingState("解析字体…") }` —— **null 只有「加载中」一种解释**。

**建议修法**：加载改为 `Result`，失败时给 `ErrorState("字体解析失败：…")`（或「不是有效的字体文件」+ 引导「打开方式…」）。

### F13（P2）搜索结果窗口「关掉会自动弹回来」

**现象**：搜索进行中点「关闭」（或点结果定位）后，结果窗口会在**下一次中间结果**（约每 20 条）时
自动重新弹出；搜索结束时**一定**会再弹一次（即使你已经关掉）。长搜索期间基本无法忽略它。

**证据链（HEAD）**：
- 关闭只是清状态：`DualPaneScreen.kt:1257` `onDismiss = { searchResults = null }`（`onPick` 同理，1254）；
- 但后台搜索仍在**持续写回**：`1209` `onPartial = { partial -> searchResults = partial }`（`searchTree` 每 20 条回调一次）；
- 结束时无条件写回：`1217-1219` `searching = false; if (outcome != null) { searchResults = outcome.items ... }`；
- 渲染处 `searchResults?.let { ... }`（1229 起）→ 状态非空即重新出对话框。

**建议修法**：关闭/点选后置「本无视」标记（如 `searchDialogSuppressed = true`），在用户**主动**打开结果
（如状态栏提示「搜索已完成（N 条），点此查看」）前不再自动弹；或按 MT 口径把「关闭」定义为「停止并收起」。

### F14（P3）「添加对面选中项到压缩包」：对面没有选中项时**静默**改为整个目录

**证据链（HEAD）**：`BrowserController.kt:1726`
`val sources = other.selectedItems.map { it.uri }.ifEmpty { other.items.map { it.uri } }`。
菜单文案是「添加**对面选中项**到压缩包」——没有选中项时行为与文案不符（会把对面整个目录塞进包）。
**建议**：无选中项时提示「请先在对面窗格选中要添加的项」，或把文案改成「添加对面窗格内容到压缩包」。

### F15（P3）⋮ 菜单「复制到剪贴板」：无选择时把**整个目录**放进剪贴板

**证据链（HEAD）**：`DualPaneScreen.kt:820`→`BrowserController.kt:1808-1817`
（`overrideSources ?: targetSources(side)`；无选择 = 整个目录）。状态栏只报「已复制 N 项」，
后续「粘贴」会照单全收。**建议**：无选择时改为提示「先选中要复制的项」，或明确文案「复制当前目录全部到剪贴板」。

### F16（P3 · U3 的补集）这些输入框仍不自动聚焦 / 不弹键盘

**现状**：`FocusRequester` 全仓只有两处：`Dialogs.kt:169`（TextInputDialog：重命名/新建/跳转/过滤等）
与 `EditorSearchBar`（编辑器查找）。以下高频输入的对话框/页面仍要**先点一下输入框**：
搜索对话框（`SearchDialog.kt` 起始输入框）、压缩对话框「文件名」（`Dialogs.kt:620-626`）、
批量重命名三个框（`BatchRename.kt`）、解压「目标路径」框（`Dialogs.kt:754-760`）、
设置里 UA 编辑（`SettingsScreen.kt`）、已安装应用搜索框（`ToolsScreens.kt`）。
**建议**：给这些输入框补 `focusRequester + keyboardActions(Done)`（模式照 `TextInputDialog` 抄）。
（此前 U3 状态表标「✅ 已修」——本轮核对后修正为只覆盖 TextInputDialog 一族；见 §2。）

---

## 2. 存量问题状态复查（本轮逐条核对）

| 条目 | 当前状态 | 说明 / 证据（HEAD） |
|---|---|---|
| U3 输入框聚焦 | 🟡 **部分修**（口径修正） | TextInputDialog / 编辑器查找已修；搜索 / 压缩 / 批量重命名 / 解压路径 / UA / Apps 搜索**未修** → F16 |
| U4 长操作反馈 | 🟢 **在途（另一会话，未提交）** | BusyOps + busy 状态条接管压缩/完整性/校验/对比/远程包下载 + openArchive 去重；见 §4。**勿重复实现** |
| U5 取消假报错 | ✅ 已修 | `DualPaneScreen.kt:1211-1212` 单独 catch `CancellationException`；ArchiveCompressor 取消也按「取消≠失败」处理（在途） |
| U6 `.bak` 备份 | ✅ 已修 | `EditorScreen.saveText/backupBeforeSave`：序号化不冲突命名 + 成败可见 |
| U7a 回收站清空/彻底删除确认 | ✅ 已修 | `ToolsScreens.kt` confirmPurgeAll / purgeTarget 两处确认框 |
| U7b 导出 APK | ⛔ 仍未修（覆盖已修） | 无进度、`rememberCoroutineScope` 离开页面即取消（`ToolsScreens.kt exportApk`）；静默覆盖已修（uniqueChild） |
| U8 死菜单 | ✅ 已修 | 三个死入口已移除；「关于」为对话框 |
| U9 状态队列 | ✅ 已修（v1.9.2） | `statusQueue`（上限 8）+ `statusDurationMs`（错误 6s） |
| U10 状态保持 | ⛔ 仍未修 | `rememberSaveable` 全仓命中 **0**；导航栈/编辑器草稿在 Activity 重建时仍丢 |
| U11 远程管理 URL 不可复制 | ⛔ 仍未修 | `RemoteScreen` 仍只有 `Text(url)`，无复制按钮/二维码 |
| U12 Hex 预览 | ⚪ 已关闭 | v1.7.0 已下线该功能，条目作废 |
| U13 `.part` 残留 | ⛔ 仍未修 | 只清断点**记录**（`resumeDao.purgeStale` 7 天）；`.part` 文件本身无任何 UI 清理入口 |
| U14 refresh 注释 | ✅ 已闭环 | `refresh()` 注释已改为「保持滚动位置」；附带发现 `forgetScroll` 现为**死函数**（`BrowserController.kt:1571`，全仓 0 调用） |
| U16 播放器 250ms 轮询 | ⛔ 仍未修 | `MediaScreen.kt:252-260` `while(true) { ...; delay(250) }`（4Hz 重组，未改 Player.Listener） |
| U17 退出提示 Toast | ⛔ 仍未修 | `AppRoot.kt:88`（其余全站 Snackbar/状态条） |
| U18 主题三态 | ⛔ 仍未修 | 主页头部按钮只切 亮/暗；回「跟随系统」要进 ⋮ 菜单 |
| U19 Apps「0 个」 | ⛔ 仍未修 | `ToolsScreens.kt` 副标题直接用 `apps.size`（加载前闪「0 个」） |
| U20 回收站还原提示 | ✅ 已修 | 成功/失败/重名 `(1)` 都有提示 |

> 另：`F1/F4/F5/F6`（10-07 体验审计的修复）本轮**复核有效**：
> 长按菜单 6 个动作只剩 F7/F8 两个漏网入口；整包解压先挂载 + 兜底（v1.9.2）按代码链路成立；
> 多选分享的长按入口是好的（顶栏入口见 F9）；docx 预览加固逻辑未回归。

---

## 3. 本轮核对过、确认没问题的（防重复劳动）

| # | 路径 | 怎么核的（要点） | 结论 |
|---|---|---|---|
| 1 | 启动三连权限（所有文件 / 局域网 / 通知）与拒绝后的降级 | `AppRoot.kt:118-196`；无权限时错误文案可执行（`LocalVfs.kt:105/286`） | ✅ |
| 2 | 冷启动恢复：上次双列路径（含网络盘自动重连）、上次打开的文件（24h 内 + 可达） | `BrowserController.init` / `ensureReachable` / `AppRoot.resumeLast`；启动期间禁写防覆盖 | ✅ |
| 3 | 长按动作菜单的**其余**动作（删除/复制/移动/剪贴板/分享/文件对比/属性/二级菜单） | `menuTargets` 端到端接线 + `MenuTargetsTest` 5 例 | ✅（F7/F8 两入口除外） |
| 4 | 删除：本地→回收站 / 网络直删 / 多会话分组 / 极速删除提示(>1000) / 失败文案 | `deleteSelected`；回收站原子索引 + 失败回滚（`TrashService`） | ✅ |
| 5 | 移动二次确认（含跨存储「先复制再删除」说明）、冲突三选 + 应用全部 | `MoveConfirmDialog` / `ConflictDialog` | ✅ |
| 6 | 重命名：非法名拦截、同名冲突（交换/删除目标/备份 .bak） | `rename` / `RenameConflict` 链路 | ✅ |
| 7 | 解压（v1.9.2 修复链路）：先挂载 + kind 校验 + try/catch 全兜底；「单独文件夹」防覆盖 | `extractArchiveTo` / `extractToOwnFolder` | ✅（对话框控件见 F10） |
| 8 | 压缩包内写操作：ZIP 限定（7z/tar 只读有提示）、删/改名/添加后重挂载 | `archiveEditor` / `refreshArchive` | ✅ |
| 9 | 编辑器：保存（编码/权限保留/.bak）、未保存离开三选确认、大文件分段只读、查找替换后台执行/过期丢弃 | `EditorScreen` 全链路 | ✅ |
| 10 | 预览家族：图片（同目录翻页/缩放/双击）、文本（1MB 截断标注/编码识别）、PDF、APK、Office、打开方式网格/默认/管理 | 各屏加载/错误/重连路径；`resolveSession` 统一自动重连 | ✅（字体失败态见 F12） |
| 11 | 分享（长按入口）：多选 `ACTION_SEND_MULTIPLE`、非本地/无目标均有提示 | `shareItems` | ✅（顶栏入口见 F9） |
| 12 | 剪贴板粘贴：源可达性校验、移动语义清空、FAB 显隐 | `pasteFromClipboard` | ✅（无选择拷贝见 F15） |
| 13 | 传输任务：任务条只收进行中/失败；暂停/继续/取消（「正在取消…」）/移除/清空；前台服务通知（点按回 App、startForeground 失败降级） | `TaskRow` / `TasksScreen` / `TransferService` | ✅ |
| 14 | 状态提示队列：8 条上限、错误 6s、UI 逐条消费 | v1.9.2 实现 | ✅ |
| 15 | 会话体系：`?c=` 连接号全入口统一、断开单条连接（双按确认）、空闲回收、压缩包挂载缓存校验（时间戳+大小） | `AppContainer` / `SessionLocator` | ✅（去重为在途改进） |
| 16 | 书签：打开/删除/长按拖动排序；抽屉「后台」只收网络挂载且每挂载一行 | `BookmarksScreen` / `drawerNetworkMounts` | ✅ |
| 17 | 搜索/过滤/排序：目录内过滤语法（`!`/`/`/`!/`）、类型过滤、文件夹专属排序记忆、300 条上限询问 | `matchesSearch` / `MtSearchDialog` / 排序管理 | ✅（结果窗关闭见 F13） |
| 18 | 界面细节：加载遮罩（160ms 防闪/取消/百分比）、滚动位置记忆（按 `loadedUri` 防串目录）、分隔条拖动/双击复位、标签页「只剩一个不许关」 | `PaneView` / `BrowserController` / `DualPaneScreen` | ✅ |

---

## 4. 工作树在途改动（另一会话 · 审计快照 16:35–16:45）——**勿重复实现**

> 以下为**未提交**改动，属「U4 长操作反馈」一个批次（文件与范围按实时 `git status/git diff`）：

| 文件 | 在途内容 |
|---|---|
| `ui/browser/BusyOps.kt`（新增）+ `BusyReporterTest.kt`（新增） | `BusyReporter`（协作式取消 + 10Hz 节流）+ `busyFraction` 纯函数 |
| `ui/browser/BrowserModels.kt` | `BusyOp`（title/progress/detail/cancellable）+ `BrowserUiState.busy` |
| `ui/browser/BrowserController.kt` | `launchBusy/cancelBusy`（同一时间一个长操作；取消即时反馈「正在取消…」）；接入：打开远程压缩包（下载进度）、压缩（两路径）、测试完整性、校验值、目录对比；取消时只清理「本次新建」的半成品 |
| `ui/browser/DualPaneScreen.kt` | busy 状态条 UI（不可消失 + 取消按钮） |
| `ui/browser/FolderDiff.kt` | 对比过程进度/取消上报 |
| `AppContainer.kt` | `openArchive` 单飞去重（`openingArchives`）+ 下载进度回调 |
| `core/vfs-archive/ArchiveCompressor.kt` | 分块进度上报 + 取消即 `abort()`（CancellationException 原样上抛） |

**对本文的影响**：F7/F8 位于 `BrowserController`/`DualPaneScreen` —— 与在途改动**同文件**。
建议：**等在途 U4 提交后**再修 F7/F8，避免冲突；其余问题（F9–F16）与之无耦合，可先行。

---

## 5. 未覆盖 / 需实机确认

- **实机（沙箱无设备）**：手势手感（滑动选中/长按阈值）、播放器手势与动效、字体渲染、Office 渲染正确性、
  PDF 翻页性能、缩略图滚动流畅度、真机深色/浅色观感、折叠屏/平板布局。
- **真实协议**：SFTP/FTP/FTPS/SMB/S3/WebDAV 的网络与错误路径本轮未重跑（前有专项审计）。
- **需设备才能复现的链路**：分享面板行为、系统安装器、FileProvider、通知前台服务、WebView（Office 预览）。

**建议的一分钟实机复核（对应本文 F7–F13）**：
1. 长按一个文件 → 压缩 → 保存到「另一窗口」→ 确认：**压缩包里只有那一个文件**（F7）；
2. 长按一个文件 → **长按**「复制 ->」→ 输入 `/Download` → 确认：**只复制那一个文件**（F8）；
3. 多选 3 个文件 → 顶栏「分享」：分享面板里是 **3 个**（F9）；
4. 长按 zip →「解压到文件夹…」→ 输入 `/sdcard/Documents` → 确认：按输入路径解压（F10）；
5. 打开一个坏字体（如把 .txt 改名 .ttf）→ 应显示**失败提示**（F12）；
6. 对一个较大目录发起搜索（勾选递归）→ 中途点「关闭」→ 不应自动弹回（F13）；
7. 设置 → 手势说明：文字与实际操作（长按只弹菜单）一致（F11）。

---

## 6. 建议处置顺序

1. **批 1（P1，与 F1 同族）**：F7 + F8 的「显式目标端到端」修复（等 U4 在途提交后合并；
   各配一条接线级回归测试）。
2. **批 2（P2 功能）**：F9 分享入口统一 → F10 解压对话框（实现或删除死控件）→ F12 字体错误态 →
   F13 搜索窗口抑制/停止语义。
3. **批 3（文案）**：F11 设置说明 + 两处头注释（随手带上，零风险）。
4. **批 4（P3）**：F14/F15 语义明确化、F16 输入框聚焦（照 `TextInputDialog` 模式抄）。
5. **存量**：U7b/U10/U11/U13/U16/U17/U18/U19 按既有优先级另行排期（U4 已在途，落地后需实机验收取消路径）。

---

## 7. 复核命令（复制即用，按 HEAD 基线）

```bash
cd /workspace/panelfm-android
H=3e1e5ed

# F7 压缩：参数声明了但函数体没用 overrideSources
git show $H:app/src/main/kotlin/com/u707t/panelfm/ui/browser/BrowserController.kt | sed -n '571,600p'
git show 2027b24 -- app/src/main/kotlin/com/u707t/panelfm/ui/browser/BrowserController.kt | sed -n '38,50p'

# F8 单窗口复制/移动：单窗口入口没带目标
git show $H:app/src/main/kotlin/com/u707t/panelfm/ui/browser/DualPaneScreen.kt | sed -n '1024,1032p;1286,1302p'
git show $H:app/src/main/kotlin/com/u707t/panelfm/ui/browser/BrowserController.kt | sed -n '1860,1876p'

# F9 顶栏分享只取第一个
git show $H:app/src/main/kotlin/com/u707t/panelfm/ui/browser/DualPaneScreen.kt | sed -n '420,424p;1705,1711p'

# F10 解压对话框死控件
git show $H:app/src/main/kotlin/com/u707t/panelfm/ui/browser/Dialogs.kt | sed -n '726,780p'
git show $H:app/src/main/kotlin/com/u707t/panelfm/ui/browser/DualPaneScreen.kt | sed -n '1325,1366p'

# F11 文案
git show $H:app/src/main/kotlin/com/u707t/panelfm/ui/settings/SettingsScreen.kt | sed -n '118,126p'
git show $H:app/src/main/kotlin/com/u707t/panelfm/ui/browser/PaneView.kt | sed -n '88,97p'

# F12 字体失败永远 Loading
git show $H:app/src/main/kotlin/com/u707t/panelfm/ui/preview/FontScreen.kt | sed -n '60,95p'

# F13 搜索窗口自动弹回
git show $H:app/src/main/kotlin/com/u707t/panelfm/ui/browser/DualPaneScreen.kt | sed -n '1205,1260p'

# F14/F15 隐式回退
git show $H:app/src/main/kotlin/com/u707t/panelfm/ui/browser/BrowserController.kt | sed -n '1723,1726p;1806,1818p'

# 存量核对
git show $H:app/src/main/kotlin/com/u707t/panelfm/ui/AppRoot.kt | sed -n '80,96p'                    # U17
git show $H:app/src/main/kotlin/com/u707t/panelfm/ui/tools/ToolsScreens.kt | sed -n '225,245p'      # U11/U19
git show $H:app/src/main/kotlin/com/u707t/panelfm/ui/preview/MediaScreen.kt | sed -n '250,262p'      # U16
grep -rn "rememberSaveable" app/src/main/kotlin | wc -l                                            # U10 → 0
```

---

## 8. 附：本轮验证故事

| 项 | 结果 |
|---|---|
| 基线 CI | GitHub Actions run **74 @ `3e1e5ed` = success**（含 `testDebugUnitTest` 全量）；Release v1.9.2 已发布 |
| 本轮本地测试 | **未跑**——工作树被并发会话持续修改（任何构建结果都不代表黑白任一状态）；本文证据全部为只读取证（文件:行号可复核） |
| 并发会话 | U4 在途（§4），与本文 F7/F8 同文件，修复需协调 |

---

*审计人：AI（只读审计，未改动任何源码；仅新增本文档，并在 `AUDIT-2026-10-07-EXPERIENCE.md` /
`AUDIT-UX-2026-10-05.md` 的相应位置加了指向本文的复核注记。工作树中的源码改动来自另一并发会话的在途 U4 实现。）*

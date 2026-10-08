# PanelFM 全量重审（2026-10-08 起 · 单一总文档）

> **本文件是本轮「全量重审」的唯一记录**：旧审查记录已于 2026-10-08 全部删除（保留 3 份设计说明：
> `EDITOR-ENGINE.md` / `OFFICE-PREVIEW.md` / `SELECTION-MODEL.md`），本文件按批次逐段追加。
>
> 基线：`main` @ `c69a4dd`（v1.10.1）。
> 方法：只读代码 + 全仓 grep 取证（所有结论给 文件:行号）；未跑真机。
> 修复：**第 1–8 批已修复收口**（见各批末尾「修复记录」）；其余批次待审计后继续。

---

## §0 模块分诊表（2026-10-08）

| 模块 | 范围（路径 / 规模） | 等级 | 依据（证据） | 处理 |
|---|---|---|---|---|
| **app/ui/browser** 文件管理主链路 | `ui/browser/` · 15 文件 / 8,126 行 | 🔴 | `BrowserController.kt` 2,289 行单类（约 130 函数）+ `DualPaneScreen.kt` 2,277 行（主 Composable ≈1,650 行） | 重点查（本批 ↓） |
| **app/ui/preview** 预览体系 | `ui/preview/` · 7 文件 / 3,845 行 | 🔴 | `MediaScreen.kt` 1,886 行（主 Composable ≈950 行） | 重点查 |
| **app/ui/editor** 文本编辑器 | `ui/editor/` · 3 文件 / 1,443 行 | 🔴 | `EditorScreen.kt` 1,063 行，主 Composable ≈675 行 | 重点查 |
| **core/transfer** 传输引擎 | `core/transfer/` · 6 文件 / 1,376 行（+测试 1,207） | 🟡 | `TransferTask.kt` 812 行；数据高危域 | 常规检查 |
| **core/vfs-local** 本地文件操作 | `core/vfs-local/` · 2 文件 / 459 行 · 零测试 | 🟡 | 删除/复制/移动本地核心路径无回归测试 | 常规检查 |
| **core/vfs-archive** 压缩包 | `core/vfs-archive/` · 6 文件 / 1,546 行（+测试 1,106） | 🟡 | ZipEditor / 压缩 / 解压 / 加密 | 常规检查 |
| **core/vfs 远程协议** | ftp/sftp/smb/webdav/s3 · 15 文件 / 3,767 行 | 🟡 | ftp 571 行、smb 576 行零测试 | 常规检查 |
| **app/ui/connections** | `ui/connections/` · 3 文件 / 922 行 | 🟡 | `ConnectionEditScreen.kt` 710 行 + 深缩进 | 常规检查 |
| **app 壳层** | AppContainer/AppRoot/service/tools · 10 文件 / ≈1,500 行 | 🟡 | `RemoteHttpServer.kt` 242 行对外 HTTP 面 | 常规检查 |
| **app/ui 周边** | home/settings/tasks/bookmarks/tools · 6 文件 / 2,047 行 | 🟡 | 小页面集合 | 常规检查 |
| **core/data** | `core/data/` · 8 文件 / 928 行 | 🟡 | `PrefsStore.kt` 347 行；测试仅 28 行 | 常规检查 |
| **core/common** | `core/common/` · 15 文件 / 1,170 行（+测试 889） | 🟡 | 小工具集、被全项目共用 | 常规检查 |
| **core/ui** | `core/ui/` · 9 文件 / 2,784 行（+测试 308） | 🟢 | 构件库；`MtRowGesture` 质量样板级 | 抽检 |
| **core/vfs-api + core/model** | 16 文件 / 778 行（+测试 199） | 🟢 | 接口与数据类为主 | 抽检 |
| **脚手架** | CI / tools / third_party / gradle | 🟢 | 非业务代码 | 不审 |

批次顺序：browser → preview → editor → transfer → vfs-local → vfs-archive → vfs 远程 → connections → 壳层 → 周边页 → data → common → 收尾抽检。

---

## §1 模块审查：app/ui/browser（2026-10-08 · 第 1 批）

> 结论一句话：功能收口做得细（目标语义/手势/滚动记忆都有测试与注释），但**两座 2,000+ 行大山**
> 与**新引入的并发写风险**是地基问题；另有 1 个确定性"点了没反应"的 bug（交换文件名）。
> 范围：`app/src/main/kotlin/com/u707t/panelfm/ui/browser/`（15 文件 / 8,126 行）
> + `app/src/test/.../ui/browser/`（13 文件 / 60+ 用例）　分诊等级：🔴

### 🔴 阻断性问题（必须修）

#### 1. 两座 2,000+ 行大山：`BrowserController.kt`（2,289 行）与 `DualPaneScreen.kt`（2,277 行）

- 位置：`BrowserController.kt`（单类约 130 个成员）；`DualPaneScreen.kt:124-1777`（**单个 Composable ≈1,650 行**）
- 问题：Controller 一个类背 10+ 种职责（启动持久化 / 加载 / 导航 / 标签 / 选择 / 书签 / 排序 /
  文件操作 / 压缩包 / 剪贴板 / 目录对比 / 任务 / 搜索 / 滚动记忆 / 权限 / 校验值）；主屏单函数
  1,650 行，20 多个弹窗编排与局部 `fun` 全挤在一处。任何修改都要在这两个文件里"考古"。
- 为什么：`closeTab` 这种 8 行的纯公式都写错了（见 §🟡4）——大文件里的细节错误没人能 review 住；
  每次加功能的认知成本在复利。
- 修复（拆分方案）：
  - `BrowserController.kt` → 6 个文件（各 300–500 行）：
    1. `BrowserStore`：state 读写 / update / pane / focus / 小 setter；
    2. `BrowserLoading`：load / cancelLoad / refresh / 代次判定 / 滚动记忆登记 / searchTree；
    3. `BrowserNavigation`：open / back / forward / up / 标签页 / reveal / sync / swapPanes / 书签 / 排序 / 首页；
    4. `BrowserFileOps`：delete / rename(+冲突) / 新建 / swapSelectedNames / changePermissions / checksum / 统计；
    5. `BrowserArchiveOps`：openArchiveInPane / extract* / testArchive / 包内编辑 / compress*；
    6. `BrowserTransfers`：跨窗格 / 剪贴板 / 目录对比 / pendingMove / 任务与 busy 桥。
  - `DualPaneScreen.kt` → 主文件只留骨架（目标 ≤500 行）：`PaneLayout`（双窗格+分隔条 ~150 行）、
    `BrowserTopBar`、`BrowserBottomBar`、`BrowserMenuSheets`（⋮ 两级菜单 + 动作表构建）、
    `BrowserDialogHost`（把 20 多个 `if (x) Dialog()` 收成一个编排组件 + 一个 DialogState）。

#### 2. 「交换文件名」是静默空操作（本地）

- 位置：`BrowserController.kt:1061-1104`（入口 `DualPaneScreen.kt:1157`，选中 2 项时可用）
- 问题：三步交换的 rename 目标写反：`targetA = a.parent/b.name`、`targetB = b.parent/a.name`，
  代码却是 `rename(b → targetA)`——同目录下 targetA 就是 b 自己的路径，`LocalVfs.rename`
  对同路径直接 `return true`（`LocalVfs.kt:189`）；随后 `rename(tmp → targetB)` 把 a 原样放回。
  **整个操作什么都没发生**。远程实现（目标已存在即 Conflict）则必然报「交换失败」。
- 为什么：菜单上明摆着「工具 → 交换文件名」，点了没反应，会被当成玄学。写错的证据：同一文件里
  `resolveRenameConflict` 的 "swap" 分支（`BrowserController.kt:1185-1204`）三步是正确的。
- 修复：两处参数对调（`rename(b.uri, targetB)`、`rename(tmp, targetA)`），或复用 resolveRenameConflict
  的三步结构；补一个「两步交换后两文件内容互换」的假 VFS 单测（现零覆盖）。顺带统一入口口径：
  工具菜单当前作用于"选择集"而非长按项，与 `menuTargets` 哲学不一致。

#### 3. 控制器状态是"非原子读-改-写 + 多线程写"

- 位置：`BrowserController.kt:232-234`（`update`）、`:58-66`（`loadJobs` 等普通可变结构）
- 问题：`container.scope = SupervisorJob + Dispatchers.IO`（`AppContainer.kt:54`，多线程池），
  而 `update()` 是 `_state.value = transform(_state.value)`——非 CAS。加载协程（IO）、任务观察（IO）、
  UI 回调（主线程）会**并发**进入这个读改写；`loadJobs`、`clipboard`、`busyJob`、`scrollSavers`
  同样被多线程读写。
- 为什么：典型丢更新——列表加载完成写 items 的同时用户改选择，双方各按旧快照回写，先写的被吞；
  症状是偶发的"选中丢了 / 状态条错乱"，会被长期当玄学；普通 HashMap 并发改还有损坏风险。
- 修复：① `update()` 改 `MutableStateFlow.update {}`（CAS，一行）；② 定线程纪律：控制器写路径
  统一收敛到 `dispatchers.main`（异步完成回到主线程再写），把 `loadJobs` / `clipboard` / `busyJob` /
  `scrollSavers` 一并纳入；③ 类 KDoc 写明「唯一写线程」约束。

### 🟡 建议修复（应该修）

#### 4. 关闭标签页后可能跳到错误的标签

- 位置：`BrowserController.kt:741-748`
- 问题：`newActive = min(activeTab, newLastIndex)` 后又无条件 `-1`（当 `index <= activeTab`），双重折算。
  实测：[A,B,C] 当前=C 关 C → 落在 A（应落 B）；[A,B,C] 当前=C 关 A → 落在 B（应保持看 C）。
- 为什么：多标签是常用功能，"关一个标签看了一半的视图被换走"每点一次都会发生。
- 修复：`val newActive = (if (index < activeTab) activeTab - 1 else activeTab).coerceIn(0, tabs.lastIndex)`；
  把该公式捞出为纯函数并补单测。

#### 5. 「仅应用于此文件夹」的排序泄漏到其它目录

- 位置：`BrowserController.kt:393-395`（load 回写 `pane.sort`），配合 `:969-982`（applySort）
- 问题：load 时若当前目录有专属规则，会把 `pane.sort` 改写成该规则；离开到无规则目录时，
  用的是上一个目录的规则（而非全局默认排序），排序对话框初值也跟着错。
- 为什么：功能名承诺"仅应用于此文件夹"，实际是"粘到下一次改动"。用户会看到别的目录排序莫名其妙变了。
- 修复：不要回写 `pane.sort`；新增 `effectiveSort(side)`（rule ?: 全局默认）仅供 load 与对话框初值使用；
  `pane.sort` 只在用户显式改排序时更新。

#### 6. 目录对比在会话不可用时静默给出错误结果

- 位置：`FolderDiff.kt:61-65`
- 问题：`locator.find(...)?.list(...).orEmpty()`——一侧会话不可用（断开/未挂载）按"空目录"处理，
  结果整片标成「仅左侧/仅右侧」，两个操作按钮还会据此真的发任务。
- 为什么：看起来完整、实际错误的对比结论比直接报错更有害。
- 修复：find 为 null 时抛 `VfsException.Unsupported("一侧目录不可用")`，交给 `launchBusy` 统一提示。

#### 7. 启动首页两条实现互相竞争；网络首页不重连

- 位置：`AppRoot.kt:140-142` 调 `openHomeIfConfigured`（`BrowserController.kt:953-957`）
  vs `BrowserController.kt:112-160` 的启动恢复（startAtHome 分支，含 `ensureReachable` 重连）
- 问题：两处都在"冷启动打开首页"：BC 恢复流（两窗格 + 自动重连 + 时序保护）与 AppRoot 的
  LaunchedEffect（只开左窗格、要求会话已存在、无重连、与 settings 异步加载存在时序竞争）。
  记忆路径开启时，左窗格可能被 AppRoot 这条抢先打开首页；网络首页在这条路径上静默失效。
- 为什么：启动行为不确定（有时回到上次路径、有时跳首页）。
- 修复：收敛为一处——删掉 AppRoot 的调用，启动路径统一由 BC 恢复流按 `startAtHome` / `rememberLastPath` 处理。

### 🔵 可选优化（可以修）

#### 8. `BrowserUiState.status` 是死通道：没有任何读者

- 位置：字段 `BrowserModels.kt:154`；直写点 `BrowserController.kt:658 / 1256 / 1316 / 1929 / 1963 / 2162`
- 问题：全仓无读者；6 处直接 `copy(status=...)` 写的文案用户永远看不到（幸好这些流程有 busy 条 /
  任务条 / 高亮兜底）。
- 修复：删除字段与所有直写（推荐），或统一改走 `showStatus()` 进入状态队列。

#### 9. 压缩入口两段近重复实现

- 位置：`BrowserController.kt:663-684`（compressToOther）与 `:1994-2016`（compressHere）
- 问题：约 30 行的"existedBefore + 取消清理 + 进度映射 + 完成刷新"模板两份拷贝（F7 修复要两边各来一次）。
- 修复：抽 `private suspend fun compressInto(dest, name, format, level, password, encryptNames)`，两入口只算 dest 与文案。

#### 10. 死代码

- 位置：`BrowserController.kt:593` `openModes()`（无调用点；Compose 走 `openModesSuspend`）
- 修复：删除。

#### 11. 连续冲突时对话框选择可能残留

- 位置：`Dialogs.kt:97-98`
- 问题：`remember { policy/applyAll }` 未按冲突标识 key；两个冲突在一帧内先后到达（无组合间隙）时，
  第二个对话框会保留第一个的选择，而非默认「复制并替换」。
- 修复：`key(info.sourceName to info.destName) { ConflictDialog(...) }` 或 `remember(冲突标识)`。

#### 12. 压缩对话框切格式后密码残留 → 必失败一次

- 位置：`Dialogs.kt:647-649`（切格式只清 encryptNames）+ `ArchiveCompressor.kt:84-87`（带密码 + tar 系列 → throw）
- 问题：先填密码再切到 tar，点确定必然报「tar 不支持加密」；且此时密码框已禁用，用户看不出原因。
- 修复：格式切换时 `password = ""`；或确定前校验并禁用确定按钮。

#### 13. 跨协议输入路径跳转可能带错连接号

- 位置：`DualPaneScreen.kt:1178-1180` + `BrowserController.kt:488-493`
- 问题：在 A 协议窗格输入 B 协议完整 URI（如 `s3://…`）时，A 的连接号被盖到 URI 上；
  `SessionLocator.find` 先按连接号命中 A 的会话 → 用错 VFS 加载或报错。
- 修复：仅当目标与当前 tab 同协议时才沿用 `tab.connectionId`；跨协议改
  `connectionByAuthority(uri.scheme, uri.authority)` 反查。

#### 14. 删除失败路径不刷新列表；混合删除文案漏报远程数

- 位置：`BrowserController.kt:1145-1147`
- 问题：删除抛错时不 `load(side)`（部分已删的项仍显示）；"本地进回收站 + 远程直删"混合时只报回收站数。
- 修复：catch 补 `load(side)`；文案改「已删除 X 项（其中 Y 项移入回收站）」。

#### 15. 重命名撞到同名文件夹时错误文案误导

- 位置：`BrowserController.kt:1161-1167`
- 问题：冲突对话框只处理"目标不是文件夹"的情形；目标是目录时直接尝试 rename → 失败提示
  「服务器拒绝重命名（可能需要服务端复制）」，与真实原因不符。
- 修复：`exists != null`（无论是否目录）都先给冲突提示；或至少把文案改为「目标已存在（同名文件夹）」。

### 🟢 做得好的地方

- **手势层**：`core/ui/MtRowGesture`（153 行状态机 + 155 行单测）与 `PaneGestures.kt` 的"判定与派发分离"；
  「点击连选 / 滑动连选两个锚点分开」连历史教训都写进了 `PaneState` 注释与单测。
- **滚动位置记忆**（`PaneView.kt:120-292` + `ScrollMemory`）：`loadedUri` 内容归属、`currentKey` 复核、
  先恢复再观察——"进子目录返回停在原地"的每个时序坑都有处理与注释。
- **长按目标语义**（`BrowserModels.kt:234-268` + 11 例单测）：把"绝不退化成整个目录"做成纯函数 + 断言，
  这是对 F7/F8/F14/F15 系列事故的正确收口方式。
- **搜索**（`BrowserController.kt:1420-1532`）：取消 / 上限 / 二次确认 / 增量回传齐全；内容搜索先整体读字节
  再按编码解码；对 `runCatching` 吞取消的陷阱有专门处理（`DualPaneScreen.kt:1269-1302`）。
- **长操作体系**（`BusyOps.kt` + `launchBusy`）：协作式取消 + 节流上报 +「取消只清本次新建的半成品，绝不碰用户原有文件」。
- **压缩包取消清理 / refreshArchive 先弃后挂**：都是踩过坑之后的正确写法。

### 安全（轻量两项抽查）

- 无硬编码密钥/密码；用户输入的文件名有 `isValidChildName`（拒绝 `/`、`\`、`..`、NUL）把关；
  路径输入走 `VfsUri.parse` / `withPath`。未见未校验外部输入直接进命令/路径。无越界发现。

### 下一批建议

- 下一模块：**app/ui/preview**（🔴）——预计重点：`MediaScreen.kt` 1,886 行 / 主函数 ≈950 行的拆分；
  播放列表索引与手势边界；Media3 生命周期。

### 修复记录（第 1 批 · 2026-10-08）

| # | 状态 | 说明 |
|---|------|------|
| 🔴1 | ✅ | Controller 2,329 行 → 6 文件（状态核心 434 / Nav 447 / Select 494 / FileOps 414 / Archive 431 / Transfers 323）；DualPaneScreen 2,277 行 → 463 行主屏 + 8 文件（BrowserDialogsState + TopBar / BottomBar / MenuSheets / DialogHost / SortDialogs / ActionBars / FileActions） |
| 🔴2 | ✅ | 交换文件名：rename 目标对调（本地静默空操作 / 远程必失败 → 修复） |
| 🔴3 | ✅ | update() 改 CAS；loadJobs/scrollSavers → 并发集合；scrollMemory/busyJob/clipboard volatile；滚动记忆读写加锁；线程纪律写入类 KDoc |
| 🟡4 | ✅ | closeTab 新公式（`closeTabNewActive` 纯函数）+ CloseTabTest 4 例 |
| 🟡5 | ✅ | 排序规则不再回写 `pane.sort`；新增 `sortSpecFor`；规则变化由 settings 观察补一次重载 |
| 🟡6 | ✅ | 目录对比：会话不可用改为明确报错（不再静默按空目录给错结论） |
| 🟡7 | ✅ | 删除 AppRoot 竞态调用与 `openHomeIfConfigured`；启动路径统一走恢复流 |
| 🔵8 | ✅ | 删除死状态 `status`；6 处直写改走状态队列（文案随之可见） |
| 🔵9 | ✅ | 压缩两入口抽 `compressInto` 去重 |
| 🔵10 | ✅ | 删除死代码 `openModes()` |
| 🔵11 | ✅ | 冲突对话框按「冲突对」重置选择 |
| 🔵12 | ✅ | 压缩切格式清密码残留 |
| 🔵13 | ✅ | 跨协议跳转连接号按目标 URI 反查 |
| 🔵14 | ✅ | 删除失败路径补刷新 + 混合文案补远程数 |
| 🔵15 | ✅ | 重命名撞同名文件夹给明确提示 |

> 组织说明：两处大文件拆分均为「文件级职责拆分」（Controller 用 extension、主屏用状态 holder +
> 组件），**调用点零行为改动**；`compileDebugKotlin` / `testDebugUnitTest` / `assembleDebug` 三连绿。
> 修复提交：`bce361f`（小件）→ `e91d144`（Controller）→ `939e8b2`（DualPaneScreen）。

---

## §2 模块审查：app/ui/preview（2026-10-08 · 第 2 批）

> 结论一句话：错误可执行化、手势历史修复、数据源事件成对等「细节收口」延续了高水位；但
> **`MediaScreen.kt` 是又一座 1,886 行大山**（主 Composable 953 行 / 31 处状态 / 4 类职责混杂），
> 且「播放失败后的恢复路径」有一组确定性错乱（索引不回滚 / 重试错曲目 / 连点竞态）；另有
> PDF 渲染器释放竞态一处（静态推断）。其余 6 个文件未发现阻断问题。
> 范围：`app/src/main/kotlin/com/u707t/panelfm/ui/preview/`（7 文件 / 3,845 行）
> + `app/src/test/.../ui/preview/`（1 文件 / 192 行 / 16 用例）　分诊等级：🔴（维持）
> 方法：7 文件全部通读 + 依赖交叉验证（`MtSpec/MtGesture`、`AppContainer.resolveSession`、`checksumNow`、
> 引用面 grep）；手势行为对照工作区参照实现 IRIS（`/workspace/iris`）。未跑真机。

### 🔴 阻断性问题（必须修）

#### 1. `MediaScreen.kt` 1,886 行大山：953 行单函数 + 31 处状态 + 4 类职责混杂

- 位置：`MediaScreen.kt`（1,886 行）；主 Composable `:113-1065`（**953 行**）；`mutableStateOf` 31 处
  （其中主函数内 29 处，`:118-172` 一段就 26 处）；文件后半 ~400 行是同区无关的绘制与控件辅助
  （`IconButton`、8 个图标绘制、`PlayerSlider`、`LevelPanel`，`:1071-1560` 段）；文件尾还附
  ~150 行 Media3 数据源 `VfsDataSourceFactory`/`VfsDataSource`（`:1761-1886`，纯基础设施）。
- 问题：一个函数同时装：播放器生命周期 / 播放列表与随机 / 两套手势识别器 / 顶底控制层 /
  侧滑播放列表 / 错误恢复；状态、`remember` 闭包、`LaunchedEffect` key、`pointerInput` 闭包四层交织。
- 为什么：本批 3 个 🟡 全部落在这个函数的交叉地带（失败恢复 / 拖动基准 / 连点竞态）——与
  browser 的教训同构：大函数里的细节错误 review 不住；且 `VfsDataSource` 埋在 UI 文件里，
  未来任何播放场景复用都要从「播放器页面」里挖代码。
- 修复（按依赖方向拆 5 个文件）：
  1. `VfsDataSource.kt`：`VfsDataSourceFactory` / `VfsDataSource` / `mediaUriString` / `vfsUriFromMediaUri` /
     `percentEncode/Decode`——已核查只被 AppContainer 与测试引用（引用面 grep），可移 `app/media/` 或 core；
  2. `MediaDraw.kt`：8 个 `DrawScope` 图标 + `LevelPanel` + `LevelIcons`；
  3. `MediaPlaylist.kt`：列表加载 / `shuffledFrom` / `toggleShuffle`；
  4. `MediaGestures.kt`：tap/drag 两个识别器 + `isInsideControls` + 长按倍速联动；
  5. `MediaScreen.kt` 主文件收敛到 ≤450 行（播放器效果 + 骨架装配）；29 个 `mutableStateOf` 中
     hud / volumeRatio / brightnessRatio / seekPreview 等 ≥8 个可并入一个 `MediaUiState` 分组。

### 🟡 建议修复（应该修）

#### 2. 播放失败后的恢复路径错乱：索引不回滚 + 重试永远试「第一首」

- 位置：`MediaScreen.kt:368-378`（`playAt`）、`:1027-1060`（错误区）
- 问题（同一条链上的两处）：
  a. `playAt` 先改 `playlistIndex` 再异步 preflight；失败只设 `error`、**不回滚** →
     「标题（`displayTitle` 取 `playlist[playlistIndex]`）与列表高亮指向失败曲目，实际播放器仍停在旧曲目」；
  b. 「重试」（`:1032-1042`）与「用其他应用打开」（`:1044-1059`）用的是**入参 `uri`（打开页面时的第一首）**
     ——在列表里点第 5 首失败后点「重试」，开始播第 1 首，而列表仍高亮第 5 首。
- 为什么：错误区是失败后唯一出口；从这里把用户带进更乱的状态，会让「播放失败」看起来像玄学
  （与 browser 批「关标签落错」同级的确定性错乱）。
- 修复：`playAt` 失败回滚索引（或改为待定索引直到 `setMediaItem` 成功）；错误区按钮改用
  「失败曲目」（新增 `failedUri` 状态）而不是入参；重试成功后同步索引。

#### 3. `playAt` 连点竞态：preflight 完成后不校验「还是不是我」

- 位置：`MediaScreen.kt:370-377`
- 问题：每次 `playAt` 起一个 `scope.launch`（`preflight → setMediaItem → prepare`），互不排序；
  快速连点「下一集」时**后发先至**，最后完成的那次把播放器切回上一首，而 `playlistIndex` 已是新值
  ——「点快了就播错歌」。
- 为什么：preflight 要走会话解析 + stat + 读 1 字节，远程上 100ms+ 很常见，连点间隔足够形成乱序；
  远程播放是本页主打场景。
- 修复：意图令牌（`playIntent++`，完成时比对）或对播放切换加 `Mutex`；与 #2 的「回滚」一起做（同一一致性面）。

#### 4. 横滑进度的基准漂移 + 自动隐藏落定超调

- 位置：`MediaScreen.kt:477-556`（drag 循环；基准行 `:523`）；自动隐藏落定 `:312-320`
- 问题（同一根源两个症状）：
  a. **基准漂移**：目标按 `player.currentPosition + 累计位移` 计算，而拖动期间播放未暂停、
     `currentPosition` 持续推进 → **拖动时长被重复计入**。慢拖 10 秒，落点比手指相对位移多 ~10 秒。
     （对照 IRIS `use_gesture.dart:242`：按下时快照 `startSeekPosition`；`:275` 累计位移 ÷3px/s——基准固定。）
  b. **落定超调**：自动隐藏的「未落定拖动落定」（`:316-319`）不计入手势拖动中（key 里只有滑块
     `sliderDragging`）——若 5s 计时点落在横滑拖动中，播放位置被落定到中途值，拖动继续却带着
     「全部累计位移」→ 松手大幅过头。
- 为什么：a 慢拖时可见（HUD 目标自涨）；b 概率低但一旦发生落点大偏，且两者都藏在本函数的手势闭包里。
- 修复：拖动开始记录 `startPosition` 快照，全程 `target = startPosition + delta`（照 IRIS）；
  自动隐藏的落定只在「无进行中手势」时执行（把「手势拖动中」与 `sliderDragging` 并成同一条件）。

#### 5. `PdfRenderer` 释放与在飞渲染的竞态（静态推断）

- 位置：`PdfScreen.kt:97-100`（`onDispose` → `renderer?.close()`）vs `:140-157`
  （`lock.withLock { openPage/render }`，锁定义 `:93`）
- 问题：渲染用 `Mutex` 串行化，但 **close 不过这把锁**。退出页面：渲染协程取消（IO 块内无挂起点、
  继续跑完），`onDispose` 同时 `close()` → native 层 close/render 并发；`PdfRenderer` 文档明言非线程安全。
- 为什么：偶发「退出 PDF 时闪退/异常」，且被 `runCatching` 吞掉（或 native 层直接崩）。
  未真机复现，但代码路径成立、修复成本极低。
- 修复：`onDispose` 里 `runBlocking { lock.withLock { renderer?.close(); descriptor?.close() } }`
  （close 很快，主线程可接受）。

### 🔵 可选优化（可以修）

- **6. TextPreview 错误态可能永远转圈**（`PreviewScreen.kt:594`）：`error = e.message`（null → 落回
  `LoadingState`）。ImagePage 同场景已有 `?: "解码失败"` 兜底（`:393`）、FontScreen 有（`:83`）——此处漏了，补一行。
- **7. 远程 APK 三倍下载**（`ApkInfoScreen.kt:94-95`）：`materializeApk` 已落缓存，随后 `checksumNow(item.uri)`
  两次串行**又从源头读整包**。远程 APK = 下载 3 次。修复：校验值直接对已缓存文件算。
- **8. 播放器手势无边缘死区**（`MediaScreen.kt:477` 起）：IRIS 有 48px 边缘避让（`use_gesture.dart:229-233`）；
  本仓贴边横滑会被系统返回手势吃掉（「边缘滑不动」）。建议照补。
- **9. 吞取消的残留**：`MediaScreen.kt:384` `runCatching { preflight(uri) }.getOrNull()` 把内层刚 rethrow
  的取消又吞了（退出后继续 setMediaItem / 列目录、对已释放 player 操作）；`PreviewScreen.kt` 多处
  `catch (e: Exception)`（`:393`、`:594` 等）同病。危害低，但项目已在别处做对过——统一成
  `CancellationException` rethrow 或 sealed 结果。
- **10. 预览「上次打开」记录 save/clear 竞态**（`PreviewScreen.kt:89-96`）：`save` 在主线程、
  `clear` 走 `container.scope.launch`（IO 无序）——快速「返回 → 打开新文件」时 clear 可能晚于新 save，
  把新记录清掉。修复：clear 与 save 同作用域，或带序号校验。
- **11. 小清理**：① `PreviewScreen.kt:255` 的 `PreviewMode.PDF` 分支不可达（`:149-151` 已 return）；
  ② `PreviewScreen.kt:223` 的 `uri.scheme != "local"` 分支不可达（`:217` 已过滤）；
  ③ `MediaScreen.kt:356-357` `?:` 右侧与左侧等价（死代码）；④ `MediaScreen.kt:865`
  `playlist.size > 1 || playlist.isNotEmpty()` ≡ `isNotEmpty()`。
- **12. 测试覆盖缺口**：preview 3,845 行只剩 1 个纯函数测试（16 例）；`VfsDataSource` 的「事件成对」
  状态机（F19 修法）、`shuffledFrom/toggleShuffle`、`sampleToFit`、`clock` 都可提纯加测——这些正是
  「改一次错一次」的高风险点。

### 🟢 做得好的地方

- **播放地址语义锁死**（`MediaUriTest.kt` 16 例）：path 须含真实文件名（`inferContentType` 事故）、
  `%20` vs `+`、mimeType 表、双层分派——把「视频无法播放」钉进回归。
- **数据源事件成对**（`MediaScreen.kt:1786 起`）：`started` 标志保证 `transferInitializing → Started → Ended`
  严格成对（F19 修法）；standard scheme 委托 `DefaultDataSource`（本地 `file://` 直读）的取舍有完整注释。
- **手势层的历史修复全部带注释且正确**：点按/拖动双识别器、控制层避让（含「隐藏后残留高度会点不醒」
  的注释）、自动隐藏 token 化、长按与拖动取消联动；`rememberUpdatedState`/委托读取的用法逐个验证过。
- **PlayerSlider**（`:1294 起`）：tap/drag 双检测器的 `down.consume()` 陷阱、点按轨道「开始/seek/结束」
  三步——每条注释对应一个踩过的坑。
- **图片链路**（`decodeSampled`/`openVfsStream`）：两遍流式采样不整包进堆；`skip()` 独立缓冲区
  （注释记录了「复用 buf 读脏数据」事故）；`available()` 不退化。
- **错误可执行化**：`describePlaybackError` 覆盖 30+ 错误码；FontScreen F12 注记、Office/Pdf 的大小
  上限提示延续同一约定。
- **应用内亮度纯遮罩**：不改系统/窗口亮度、退出无残留（`:258-260` 注释）——对全局副作用的正确回避。
- **Office WebView 收口**（`OfficeScreen.kt:153-234`）：同源拦截 + 目录穿越拒绝 + 无 JS 桥 + 只读资产。

### 安全（轻量两项抽查）

- 无硬编码密钥/密码；WebView 面（Office）已按设计文档加固；字体/PDF/APK 缓存文件名以 URI hash 前缀
  构造，无路径注入面。未见越界发现。

### 下一批建议

- 下一模块：**app/ui/editor**（🔴）——预计重点：`EditorScreen.kt` 1,063 行（主 Composable ≈675 行）拆分；
  读写/编码/大文件分页/查找替换的状态纠葛；与 `EDITOR-ENGINE.md` 设计文档的一致性核对。

### 修复记录（第 2 批 · 2026-10-08）

| # | 状态 | 说明 |
|---|------|------|
| 🔴1 | ✅ | `MediaScreen.kt` 1,886 行 → 6 文件（主 655 / DataSource 373 / Draw 443 / Chrome 468 / Playlist 214 / Gestures 250）；主 Composable 953→562 行；8 个瞬时状态并入 `MediaUiState` 分组 |
| 🟡2 | ✅ | 播放失败恢复：`play()` 失败回滚索引 + `failedUri` 记录；「重试 / 外部打开」作用于**失败曲目**（不再恒试第一首） |
| 🟡3 | ✅ | 连点竞态：意图令牌（过期结果整体丢弃）；回滚与令牌统一收敛在 `play()` 一个入口 |
| 🟡4 | ✅ | 横滑进度：改「按下位置快照」为基准（照 IRIS）；拖动中禁止自动隐藏/落定（`gestureSeeking` 并入同一条件） |
| 🟡5 | ✅ | `PdfRenderer` 释放持同一把锁（`runBlocking + withLock`），消除 close × 在飞渲染并发 |
| 🔵6 | ✅ | TextPreview 错误兜底文案（message 为 null 不再停在「加载中」） |
| 🔵7 | ✅ | APK 校验改读**已落地文件**（一次读两摘要；远程包下载 3 次 → 1 次） |
| 🔵8 | ✅ | 播放器手势补 48dp 边缘避让（照 IRIS `edgeDeadZone`，避开系统返回手势区） |
| 🔵9 | ✅ | 取消穿透：`preflightMedia` 直调（不再 runCatching 吞取消）+ PreviewScreen 4 处 catch 先 rethrow |
| 🔵10 | ✅ | `clearLastOpenedPreview(expectedUri)`：只清仍匹配的记录（save/clear 异序不再互相误伤） |
| 🔵11 | ✅ | 小清理 ×4：PDF 死分支 / 外部应用不可达分支 / `toggleShuffle` 冗余 / `enabled` 等价式 |
| 🔵12 | ✅ | 新增 `MediaPlaylistTest`（6 例）+ `MediaUtilsTest`（7 例）；`computeRemaining` 提纯入测 |

> 组织说明：拆分为「主屏骨架 + 数据源 / 绘制 / 控件 / 播放列表 / 手势」六个文件；手势层以
> `MediaGestureHooks`（读/写回调集合）解除与页面状态的隐式闭包耦合，页面行为逐段等价移植。
> 主文件 655 行（审查目标 ≤450：剩余为状态 + 效果 + 装配，进一步压缩收益递减，未强推）。
> 验证：本地 `compileDebugKotlin` + `testDebugUnitTest` 全绿（+13 用例）；CI run #78 全绿
> （test / android / release 三 job）。修复提交：`3479639`。

---

## §3 模块审查：app/ui/editor（2026-10-08 · 第 3 批）

> 结论一句话：sora-editor 的**适配层是本项目与库契约吃得最透的一处**（实例不进 state / 版本号灌文本 /
> 查找三件套——逐条按库字节码核验成立）；但 `EditorScreen.kt` 是又一座 1,064 行大山（主 Composable 664 行
> + 40 个状态），且**保存编码不闭环**（BOM 丢、UTF-16 存后自己读不回、Latin-1 静默转码）、
> 大文件分页存在**页间重复展示**（最多 64 KB/页）。
> 范围：`ui/editor/`（3 文件 / 1,444 行）+ 测试（1 文件 / 77 行）　分诊等级：🔴（维持）
> 基线：`e841697`（第 1 批修复与修复记录均已入档；第 2 批修复进行中，未触及本批文件）。
> 方法：3 文件全文通读 + `EDITOR-ENGINE.md` 逐条对照 + **反汇编 sora-editor 0.24.6** 核验库交互
> （事件 action 码 / 搜索线程 / `replaceAll` 回调线程 / 正则预编译）＋ 与 mt-analysis 菜单语义对照。

### 🔴 阻断性问题（必须修）

#### 1. `EditorScreen.kt` 1,064 行大山：主 Composable 664 行 + 40 个状态 + 文件尾 195 行纯 IO

- 位置：`EditorScreen.kt:83-746`（主 Composable，**664 行**）；`by remember` 状态 40 处（`:92-138`）；
  `:870-1064` 尾段 195 行是 `loadPage` / `readAtMost` / `saveText` / `backupBeforeSave` 等
  **与 Compose 无关的 IO 基础设施**。
- 问题：本批 4 个 🟡 全部落在这个函数及其尾部基础设施的交叉地带（编码表 / 分页边界 / 会话重连 / 剪贴板），
  与 browser、preview 的大文件教训同构；`EDITOR-ENGINE.md` 自称「页面壳」，实际壳里背着读写引擎。
- 修复（拆 4 文件，主文件收敛 ≤450 行；沿用第 1 批 `BrowserController` 的拆分先例）：
  1. `EditorFileIo.kt`（~200 行）：`MAX_EDIT_SIZE` / `PAGE_*` / `HL_MAX_CHARS` 常量、`PageSlice`、
     `loadPage` / `indexOfByte` / `decodeWith` / `readAtMost` / `saveText` / `backupBeforeSave`；
     编码表顺手收进 `TextEncodings`（与 🟡2 同一动作）；
  2. `EditorFindController.kt`（~190 行）：`lastQuery` / `pendingAfterSearch` / `searchResultsReady` /
     `suppressSearchStatus` 四状态 + `notFoundMessage` / `currentSpec` / `startSearch` /
     `refreshSearchStatus` / `jump` / `replaceCurrent` / `replaceAll` / `closeFind` 一组方法；
  3. `EditorMenu.kt`（~100 行）：`EditorMenu` + `LanguageOption` + `PageButton`；
  4. `EditorDialogs.kt`（~100 行）：语法 / 转到指定行 / 未保存三个 `AlertDialog`。
  状态重组后主文件剩 ~15 个状态（分页组 5 个可并成 `PageUiState`）。

### 🟡 建议修复（应该修）

#### 2. 保存编码不闭环：BOM 不写回；UTF-16 存完自己都读不回；Latin-1 静默转 UTF-8 且状态栏报旧编码

- 位置：`EditorScreen.kt:994-1000`（写侧映射）；`:913-915` + `:928-935`（读侧 `decodeWith`）；
  `TextEncodings.kt:27-43`（识别侧，`"UTF-8 (BOM)"` / `"UTF-16LE/BE"` / `"ISO-8859-1"`）。
- 问题：识别侧能区分 5 种编码，写侧只忠实 3 种——
  ① `"UTF-8 (BOM)"` 走 `startsWith("UTF-8")` → 写**无 BOM** 的 UTF-8，BOM 丢；
  ② UTF-16 同病且更重：这类文件**只能靠 BOM 被识别出来**（`TextEncodings` 的判定顺序），保存后 BOM 没了
     → 重开走启发式（UTF-8/GBK）→ ASCII 内容显示成「字符夹 NUL」、中文直接乱码；
  ③ `ISO-8859-1` 落到 `else -> UTF-8`：字节被换成 UTF-8，状态栏却仍显示「已保存（ISO-8859-1…）」。
  与 `CHANGELOG.md:383` 自己承诺的「同编码写回」不符。
- 为什么：BOM 文件常是给 Windows 工具（`.bat` / `.ps1` / 旧配置）吃的；UTF-16 路径是「保存 → 本应用
  重开乱码」的静默事故面。同一张编码表散在读 / 写 / 识别三处，必然漂移（本次就是漂移现场）。
- 修复：把编解码收成 `TextEncodings` 单一表（`encode(text, charset)` + `decode(bytes, charset)`）；
  BOM 分支重建 BOM；ISO-8859-1 真编码（无法表示的字符明确回退 UTF-8 并在状态栏注明已转码）；
  补 round-trip 单测 `decode(encode(x, c), c) == x`，覆盖 5 种 charset。

#### 3. 大文件分页显示重叠：每页尾部多带冗余且断在半行，与下一页重复

- 位置：`EditorScreen.kt:894-916`（`loadPage`；`PAGE_SLACK` 见 `:875-876`）。
- 问题：页头对 `page>0` 做了行对齐（`skip`），**页尾从不裁剪**：显示区间是 `[start+skip, start+off)`，
  而 `off` 包含 `PAGE_SLACK`——于是「段 1 = 0 B – 576 KB」「段 2 = 512 KB+ε – …」，相邻页**重复展示
  最多 64 KB 内容**（约一页的 11%，且页尾常断在半行）。注释与 `EDITOR-ENGINE.md` §2.6 都写
  「每段 512 KB / 冗余是『对齐行边界用』」——对齐只做了一半。
- 为什么：分段浏览就是给读大文件用的；每翻一页都要重读上一页尾部，范围标注（0–576 / 512–…）也自相矛盾。
- 修复：页尾与下一页页头用同一行边界：`page < last` 时在 `[end, readEnd)` 内找第一个 `\n`、显示到 `nl+1`
  （找不到才用 `off`）；`indexOfByte` 加 `from` 参数；保持不变式
  `displayEnd(pageN) == displayStart(pageN+1)`，`goPage` 传入 `pageCount`。给不变式补纯函数单测。

#### 4. `goPage` / `saveText` 不走 `resolveSession`：断线后翻页、保存直接失败（其余入口都会自动重连）

- 位置：`EditorScreen.kt:235`（`goPage`）、`:993`（`saveText`）；对照 `:191`（初次加载已用 `resolveSession`）。
- 问题：两处都是裸 `container.locator.find(uri)`——`find` 只查注册表，**不会触发重连**；
  `AppContainer.kt:248` 的 `resolveSession` 才是「找不到就 `openConnection` 自动重连一次」。
  会话断开（网络切换 / 服务端重启 / 租约回收）后：翻页报「读取分段失败：会话不可用」、保存报
  「保存失败：会话不可用」；同样的会话状态在初次打开时却能自动恢复。全项目约定（preview 全模块、
  AppRoot）都是 `resolveSession`——这是本项目为消灭「请重新打开该存储」专门做的基础设施。
- 为什么：保存是编辑器的最后一道承诺，失败原因还是应用自己有能力修复的一种；用户得先退出、重开文件
  （或手动重连存储）才能保存，改动只能靠屏幕上的草稿。
- 修复：两处改 `container.resolveSession(uri) ?: throw IllegalStateException("会话不可用")`（两行）；
  顺带 `goPage` 的错误文案补 `e.message ?: "会话不可用"` 兜底（现在会打出 "null"）。

#### 5. 「剪切行 / 复制行」与剪贴板脱节：剪切不写剪贴板；复制行 ≡ 重复行

- 位置：`EditorScreen.kt:808-812`（菜单接线）；`LineOps.kt:54-59`（`cutLine` 返回的第二值是载荷）。
- 问题：`剪切行` 取 `cutLine(...).first`，被剪内容**直接丢弃**（剪贴板不动）；`复制行` 与 `重复行` 都调
  `duplicateLine`——两个菜单项完全同款。MT 侧这三个是三个动作：Copy line（`0x7f1103e7`）/ Cut line
  （`0x7f1103e9`）/ Duplicate line（`0x7f1103f2`）（`mt-analysis/v2/resources_dump.txt:23975/23999/24107`）。
- 为什么：「剪切 / 复制行」是用户把内容搬去别处编辑的入口；现在剪切后去粘贴得到**旧剪贴板内容**，
  「复制行」则把正文改脏（和「重复行」一样），多按几次就多几行——用户会以为菜单坏了。
  `LineOpsTest` 专门为「剪切行返回被剪内容」写了断言，说明该载荷本来就是给剪贴板预留的。
- 修复：`EditorScreen` 里取 `LocalClipboardManager.current`（browser 全量在用，先例充足）：
  `剪切行` → 取 `(rest, cut)`，`clipboard.setText(AnnotatedString(cut))` 后再替换正文；`复制行` →
  不改正文，直接复制当前行文本（可加 `LineOps.lineAt`，或复用 `cutLine(t,i).second`）；`重复行` 不动。
  真机复核一次剪贴板落地。

### 🔵 可选优化（可以修）

#### 6. 只读模式（大文件分段）下，行操作 / 代码整理菜单整组可点但静默无操作

- 位置：`EditorScreen.kt:808-839`（两组 12 项只有 `canFormat` / `canToggleComment` 两处 `enabled`，
  未接 `readOnly`）；`:385-386`（`applyWholeTextOp` 的 `if (readOnly) return` 静默返回）。
- 修复：`EditorMenu` 把 `readOnly` 也用于行操作 / 压缩 / 格式化项（`enabled = !readOnly`，与保存 /
  撤销 / 重做同款）；或点击时给 `status = "只读模式（大文件分段）不支持行操作"`。

#### 7. CSS 的「切换注释」写 `//`——纯 CSS 里是非法注释

- 位置：`EditorScreen.kt:865`（`source.css -> "//"`）；`EditorLanguages.kt:68`（css/scss/less 同 scope）。
- 问题：`//` 只在 scss/less 合法；对 `.css` 使用会把行注释写成语法错误（设计文档 §4 注明未用库的注释规则）。
- 修复：最小改法——`commentPrefixOf` 增加扩展名维度：`css` 返回 null（置灰 + 提示「当前语言不支持」），
  `scss` / `less` 保持 `//`；若要真支持 `/* */` 需给 `LineOps.toggleComment` 加成对前缀（可延后）。

#### 8. 「格式化代码」：支持清单两处维护；失败提示与「不支持的语言」混淆

- 位置：`EditorScreen.kt:439`（`canFormat` 硬编码 JSON/XML）、`:472-475`（失败一律提示「暂不支持该语言」）；
  `CodeFormatter.kt:16`（`supports()` 已有同一判定）。
- 问题：JSON 内容有语法错误时 `format()` 返回 null → 提示「暂不支持该语言的格式化」——但 JSON 明明
  被支持，用户会去查「语法」设置；支持清单散两处，以后加语言必漂移。
- 修复：`canFormat` 改用 `CodeFormatter.supports(...)`；`null` 分支区分「语法不支持」（按钮已置灰，
  理论上到不了）与「格式化失败：内容不是有效的 JSON/XML」。

#### 9. UTF-16 大文件（>2 MB）分页：首字符错位 + 伪换行

- 位置：`EditorScreen.kt:908-911`（单字节找 `0x0A`、`skip = nl + 1`）、`:928-935`（按 2 字节码元解码）。
- 问题：UTF-16 中 `0x0A` 可能只是某码元的半个字节；真换行 `0A 00`（LE）也只会跳 1 字节 → 解码从奇
  偏移开始，每页首字符乱码。UTF-8 / GBK 不受影响（0x0A 不会出现在多字节序列内部）。
- 修复：charset 为 UTF-16LE/BE 时按双字节（`0A 00` / `00 0A`）搜换行并把 skip 对齐到 2 的倍数；
  或对 UTF-16 直接放弃行对齐（宁可见页首断行也不乱码）。

#### 10. 无扩展名文件的「语法」记忆静默丢失

- 位置：`PrefsStore.kt:204-209`（`idx <= 0` 把 `"|scope"` 整条丢弃）。
- 问题：Makefile / Dockerfile 等（`currentExt == ""`）手动选语法后存的是 `"|source.shell"`，读回时
  `idx == 0` 被判无效 → 下次打开回到「自动」。功能承诺「按扩展名记忆」，对这类文件静默失效。
- 修复：判定放宽为 `idx >= 0`（或空扩展名用哨兵键如 `"(noext)"` 存储）；补一条 round-trip 单测。

#### 11. 「保存并返回」路径不刷新浏览器列表

- 位置：`EditorScreen.kt:725-733`（confirmDiscard 内）对照 `:407-416`（`save()` 成功后会 `refreshAll()`）。
- 问题：同一文件两条保存路径行为不一致——正常保存会刷新目录（大小 / 时间戳），「保存并返回」不会。
- 修复：把 `save()` 的成功回调抽成共用（`dirty = false; container.browser.refreshAll()`），两条路径共用。

#### 12. 测试缺口（纯 JVM 可覆盖）

- 位置：`EditorLanguagesTest.kt`（77 行，仅覆盖后缀映射 / 标签 / 菜单清单）。
- 修复：编码往返（随 🟡2 的 `encode` 一起加进 `TextEncodingsTest`）；分页边界（把 `loadPage` 的
  「页头 / 页尾对齐」抽成纯函数后测不变式，随 🟡3）；`PrefsStore` 覆盖解析 round-trip（随 🔵10）。

### 🟢 做得好的地方

- **sora 适配层是本项目与库契约吃得最透的一处**：实例不进 Compose state（`editorHolder` 数组）、
  版本号灌文本防「清撤销栈 + 跳滚动」、`update` 里做同步——**逐条用 0.24.6 字节码核验：注释描述全部
  成立**（`setText` 事件 action=1 → 不脏；插入 / 删除 action=2/3 → 脏；整文 `replace()` 经监听回调
  同样置脏）。
- **查找链路三件套与库行为严丝合缝**：延帧读结果（`SearchRunnable` 完成 → `postInLifecycle` →
  `dispatchEvent`）、`lastQuery` 快照防重搜、`suppressSearchStatus` 防覆盖；`search()` 对正则类型
  **同步预编译**，`catch (PatternSyntaxException)` 因而能生效（核过 `TYPE_REGULAR_EXPRESSION == 3`
  与调用点）；`replaceAll` 的用户回调经 `postInLifecycle` 回主线程——回调里写 Compose 状态是安全的。
- **编码识别的线程安全修复**（`strictUtf8Decoder()` 每次新建 + `TextEncodingsConcurrencyTest`）：
  「共享 `CharsetDecoder` 并发误判」类事故的干净收口。
- **行操作 CRLF 归一 + `LineOpsTest` 16 例**（含「剪切行返回被剪内容」这种为 UI 预留载荷的断言）。
- **`.bak` 备份收口**（复制到不冲突名 + 服务端复制优先 + 成败都进状态栏）：v1.9 事故的完整延续。
- **错误可执行化**：保存失败 / 备份失败 / 正则错误 / 行号越界 /「没有可操作的内容」全有具体文案。
- **语法注册表全量对齐**：`languages.json` 12 条 scopeName ↔ `scopeOf()` / `LABELS` / `ORDER` /
  语法文件存在性——逐条核过，无一漏挂。

### 安全（轻量两项抽查）

- 无硬编码密钥/密码；编辑器面唯一的外部输入是用户自己的编辑内容，无命令 / 路径拼接面；
  备份文件名经 `uniqueChild` 生成、无注入面。未见越界发现。

### 下一批建议

- 下一模块：**core/transfer**（🟡）——`TransferTask.kt`（812 行）的断点续传状态机 / 取消语义 /
  与 `vfs-local`（零测试的本地删 / 复制 / 移动）的联合核对。
- 备注：本批（editor）**已全量修复**（🔴1–🔵12，见下方「修复记录」）；第 2 批（preview）已修复（`3479639`）。

### 修复记录（第 3 批 · 2026-10-08）

| # | 状态 | 说明 |
|---|------|------|
| 🔴1 | ✅ | `EditorScreen.kt` 1,064 行 → **512 行壳** + 5 个新文件（`EditorFileIo` 237 / `EditorFindController` 213 / `EditorChrome` 129 / `EditorMenu` 157 / `EditorDialogs` 130）；查找 11 态进 controller、菜单展开态内聚顶栏（点击项自动收起） |
| 🟡2 | ✅ | 保存编码闭环：`TextEncodings.encode/decodeWith` 单表化——UTF-8 BOM / UTF-16 重建 BOM（不再丢、不再存后读乱码）、GBK / Latin-1 往返校验失败回退 UTF-8 并注明；round-trip 单测 7 例 |
| 🟡3 | ✅ | 分页分区不变式：页尾与下一页页头同窗口对齐（不重叠 / 不漏内容 / 空末页合法）；`EditorFileIoTest` 4 例（普通 / CRLF / 超长行 / UTF-16） |
| 🟡4 | ✅ | `goPage` / `saveText` 改走 `resolveSession`（断线自动重连）+ 错误文案 `?: "会话不可用"` 兜底 |
| 🟡5 | ✅ | 「复制行」→ 只写剪贴板；「剪切行」→ 剪贴板 + 删除；「重复行」保持原地复制（对齐 MT Copy / Cut / Duplicate line） |
| 🔵6 | ✅ | 只读（分段浏览）下禁用全部改正文项；「复制行」保持可用 |
| 🔵7 | ✅ | 「切换注释」按扩展名区分：纯 css 置灰、scss / less 为 `//`（+单测） |
| 🔵8 | ✅ | `canFormat` 改走 `CodeFormatter.supports`；失败提示改为「内容不是有效的 JSON/XML」 |
| 🔵9 | ✅ | UTF-16 分页按 2 字节码元识别换行并对齐（随 🟡3 落地，测试覆盖） |
| 🔵10 | ✅ | 空扩展名（无后缀文件）语法覆盖可存回（`parseLangOverride` + 4 例单测） |
| 🔵11 | ✅ | 「保存并返回」与正常保存共用 `saveAnd(onSaved)`（两条路径都刷新目录） |
| 🔵12 | ✅ | 新增测试 16 例：编码往返 7 / 分页不变式 4 / 覆盖解析 4 / 注释前缀 1 |

> 组织说明：拆分是**文件级职责拆分**（顶栏 / 分页条 / 菜单 / 对话框 / 查找控制器 / 文件 IO 单文件化），
> 除 🔴1–🔵12 外行为零改动。验证：`compileDebugKotlin` / `testDebugUnitTest` / `assembleDebug`
> 三连绿——app 138 例 + core:common 85 例 + core:data 6 例全通过（含本批新增 16 例）。

---

## §4 模块审查：core/transfer + core/vfs-local（2026-10-08 · 第 4 批 · 联合核对）

> 结论一句话：这是全仓**质量水位最高**的核心里——断点续传的每一处坑（按 source→dest 而非任务 id、
> `.part` 长度校验、mtime 校验、节流+收尾补记）都有出处注释与回归；取消/暂停闸门与「静默失败删除」
> 变异测试补丁都是事故后真修。本轮**未发现阻断性问题**，三条 🟡 集中在「覆盖语义的两个风险窗口」
> 与「暂停占用并发位」。
> 范围：`core/transfer/`（6 文件 / 1,376 行 + 测试 7 文件 / 1,207 行 / **34 用例**）+
> `core/vfs-local/`（2 文件 / 459 行 · 零测试）　分诊等级：🟡（维持）
> 方法：6+2 文件全文通读 + 关键依赖交叉验证（`ResumeDao` / `VfsStreams` 契约 / `Throttle` /
> 引擎接线 / `PanelDb` schema）+ 34 用例逐名核对。未跑真机。

### 🔴 阻断性问题（必须修）

无。审查前最可疑的三处——快路径降级、取消语义、断点续传闭环——均有实现与用例双重兜底
（见 🟢 与代码内历史注释）。

### 🟡 建议修复（应该修）

#### 1. 「文件覆盖同名文件夹」= 整目录删除；对话框文案却说「递归合并/覆盖」且默认选中「替换」

- 位置：`TransferTask.kt:399-406`（快路径 OVERWRITE 分支）/ `:553-557`（慢路径 `resolveDest`）/
  `:526-538`（`deleteForOverwrite`，含删后二次 stat 确认）；UI 共用 `ui/browser/Dialogs.kt:99`
  （默认 `OVERWRITE`）、`:116`（文案按「目标是文件夹」一律说「递归合并/覆盖」）。
- 问题：源是文件、目标是文件夹时「合并」在语义上不存在；实际行为 = **递归删除整个文件夹**后写入文件
  （本地走 `LocalVfs.delete → deleteRecursively`）。对话框此时显示的却是「目标是一个文件夹，替换将
  递归合并/覆盖。」，且单选默认就是「替换」——随手点「确定」= 一个目录的全部内容没了。
  34 个用例里只有「目录覆盖目录走合并」（`TransferRegressionTest:307`），**没有**「文件覆盖目录」的
  语义断言。
- 为什么：本批最贵的一次误触（丢一整个目录），文案却把破坏性动作写成「合并」。
- 修复：`ConflictDialog` 文案按 (源类型, 目标类型) 四组合分派——文件→文件夹明确写「将删除该文件夹
  及其全部内容，然后写入文件」；「目标是文件夹」的冲突不预选「替换」（默认改为「跳过」或未选中）。
  补一条 file→dir OVERWRITE 的回归测试。

#### 2. 覆盖策略「先删旧文件、再传输」：失败 / 取消后旧版本已不可恢复

- 位置：`TransferTask.kt:554-556`（`resolveDest` 在写入前先 `deleteForOverwrite`）、`:526-538`；
  对照慢路径本已具备「写 `.part` → commit 替换」的基础设施——`LocalVfs.kt:379-401` 的
  `ATOMIC_MOVE + REPLACE_EXISTING` 本就支持原子替换已存在的目标。
- 问题：可续传目标（本地 / SFTP / SMB）完全可以在**提交那一刻**再替换目标；当前顺序是
  「先删旧文件 → 传输 → commit」。传输中途失败 / 取消 / 校验不过：旧文件已没了（只剩新文件的
  `.part`），若续传记录再失效（源被替换、7 天过期清理、用户清空），旧内容永久丢失。
- 为什么：覆盖大文件 + 网络抖动是常态；用户预期「替换失败 = 旧文件还在」，现在变成
  「替换失败 = 旧文件没了」。`deleteForOverwrite` 的注释只论证了「目录 vs 文件异型冲突」，对
  「文件覆盖文件」（最常见）没有预删的必要性论证。
- 修复：慢路径文件项在目标可续传时**不预删**，替换交给协议侧 commit（本地已支持，SFTP/SMB 对齐后
  同做）；预删只保留给「快路径 rename 需要腾位」与「目标不可续传」两类；补「覆盖失败后旧文件仍在」
  的回归测试。（若产品语义坚持「替换=立即清旧」，至少把这一条写进对话框文案。）

#### 3. 暂停中的任务占用并发位：默认并发 2 时，暂停两个任务 → 后续入队任务永远「排队中」

- 位置：`TransferEngine.kt:83-98`（worker 先占并发槽再 `task.run()`；槽位在 run 返回后的 finally
  才释放）+ `TransferTask.pause()`（挂起点在 `run()` 内部，暂停期间不会返回）。
- 问题：`maxConcurrent = 2`（默认）下暂停两个运行中任务，再入队第三个 → 第三个永远停在
  「排队中」，直到恢复 / 取消暂停的任务。「等待冲突」占槽同理（排在用户回答之前）。
- 为什么：用户视角就是「队列卡死」；与注释「任务之间由引擎控制并发」的意图（暂停 ≠ 占传输位）不符。
- 修复（两档）：轻量——把占用语义显式化（任务行显示「已暂停（占用传输位）」+ 引擎注释写明）；
  彻底——`pause()` 让任务在下一个 checkpoint **退出 `run()` 并释放槽位**，`resume()` 重新入队、
  走断点续传接上（本地/SFTP/SMB 天然支持；FTP/WebDAV 等不可续传目标退化为重传，需按目标能力
  选择策略）。建议先做轻量版，彻底版留档。

### 🔵 可选优化（可以修）

#### 4. 每个文件都做一次断点查询；`resume_entry` 无 (source, dest) 索引

- 位置：`TransferTask.kt:687-701`（每文件 `resumeStore.findFor`）；`ResumeDao.kt:27-35`（查询）；
  `PanelDb.kt:67-78`（建表：`PRIMARY KEY(task_id, item_index)`，**(source, dest) 无索引**）。
- 问题：全新传输（绝大多数文件无断点）也要逐个查库；无索引 = 每次全表扫描。表由 7 天 purge 与
  `clearFor` 兜底（有界），但大目录批量复制下是纯浪费。
- 修复：补 `CREATE INDEX resume_entry_source_dest ON resume_entry(source, dest)`（走一次 DB 迁移）；
  或批量任务先做一次「本批是否有任何断点」查询再逐文件找。

#### 5. vfs-local 零测试：建议把 `android.system.Os` / `StatFs` 收进可注入的小接口

- 位置：`LocalVfs.kt` 全文件（`Os.stat/lstat/chmod` 直接静态调用 → JVM 单测跑不动，这是全仓目前
  唯一零自动化覆盖的数据路径）。
- 问题：本地删除 / 复制 / 移动是**用户数据第一现场**，四条历史事故路径全在这里却无回归网：
  符号链接删除保护（`:176-184`）、commit 原子替换（`:379-401`）、续传截断尾巴（`:347-356`）、
  跨卷 rename 退化（`:194-209`）。
- 修复：把 `Os`/`StatFs` 包成 `LocalOs` 接口（默认实现走 android.system），JVM 测试注入 fake +
  临时目录即可覆盖上述四条路径；比引入 Robolectric 更轻。

#### 6. `LocalReader.readFullyAt` 会移动共享文件指针

- 位置：`LocalVfs.kt:315-325`（`raf.seek(position)` 后读取，不回原位）；契约见 `VfsStreams.kt:45`。
- 问题：与顺序 `read()` 混用会把后续读取位置带偏；目前调用方未混用，但契约未注明「不得混用」。
- 修复：改用 `FileChannel.read(buffer, position)`（不改指针），或在校验 / 注释里写明契约。

#### 7. 死字段 `wholeDirectory`

- 位置：`TransferModels.kt:17`（字段）、`:154`（`describe()` 的「当前目录」分支）；
  全仓唯一赋值 `BrowserControllerTransfers.kt:259` 为 `false`。
- 问题：`describe()` 的「当前目录」标题分支不可达（恒走「N 项」）。
- 修复：删除字段与分支，或把「整目录操作」入口真正接上它。

### 🟢 做得好的地方

- **闸门设计**（`TransferGate.kt:38-49`）：取消优先于暂停、暂停循环内双重取消检查、退出后再查一次
  ——「暂停中取消永远挂起」在代码与用例（`暂停中取消不会永远挂在暂停等待里`）双向钉死。
- **断点续传闭环**：按 (source→dest) 而非任务 id（`ResumeStore.kt:19-27` 注释即历史事故）；
  `.part` 长度不足拒绝续写（`TransferTask.kt:692-700`）、mtime validator 防「源被替换误续」、
  1s 节流 + 失败/取消时 NonCancellable 补记最终偏移（`:759-777`）。
- **快路径降级语义**（`:115-196`）：`serverSideCopy`/`rename` 返回 false、目录合并场景全部降级慢路径，
  且降级后子树重映射（`rebaseDestination`）避免 KEEP_BOTH 二次改名；两条降级用例覆盖。
- **删除保险**：`deleteForOverwrite` 删后二次 stat 确认（`:526-538`）+ FakeVfs 的
  `silentlyIgnoreDelete` 钩子（测试注释写明：旧测试只覆盖抛异常分支、变异测试全绿后补另一条）。
- **目录语义**：目录覆盖目录走合并而非「先删再抄」（用例 `:307`）；SKIP / KEEP_BOTH 整棵子树
  阻断 / 重映射；MOVE 收尾只删空目录、跳过子树不删（`:300-311`）。
- **引擎收场**：完成 / 已取消 8 秒保留后自动收走、失败不自动收走（三条用例）；并发下调后超编
  worker 完成手头任务即退出（用例 `并发下调后…`）。
- **ResumeDao 坏行容忍**（`ResumeDao.kt:47-70`）：「断电留下的半截记录不能打崩续传链路」——事故收口。
- **vfs-local 三处安全细节**：符号链接删除不跟随（`:176-184`）、commit ATOMIC_MOVE 优先且回退路径
  有保护（`:379-401`）、续传打开时截断残留尾巴（`:347-356`）。

### 安全（轻量两项抽查）

- 无硬编码密钥/密码；vfs-local 的路径均来自用户自身设备的浏览操作（文件管理器语义），`LocalWriter`
  临时文件名固定模式、无外部拼接注入面。未见越界发现。

### 下一批建议

- 下一模块：**core/vfs-archive**（🟡）——`ZipEditor` 整包重写 / 加密 zip 读写 / 压缩解压与
  `ArchiveVfs` 的取消语义（与 transfer 的 `.part` / commit 语义衔接处重点看）。

> 备注：审查行号为 `e841697` 基线；修复落地于第 3 批收口之后（`5e922fc`），与并行批次
>（preview / editor）文件零交叉。

### 修复记录（第 4 批 · 2026-10-08）

| # | 状态 | 说明 |
|---|------|------|
| 🟡1 | ✅ | 冲突对话框按「源 × 目标」四象限分派文案；文件→文件夹明确「替换将删除该文件夹及其全部内容，然后写入文件」、选项标「（将删除文件夹）」且**默认改「跳过」**（目录→目录 = 合并，保持默认「替换」）。文案 / 默认项收进 `ConflictInfo.explanationText()/defaultPolicy()`（可单测）；新增 `ConflictDialogModelTest` 2 例 + file→dir 覆盖语义回归 1 例 |
| 🟡2 | ✅ | 慢路径覆盖**不再预删可续传目标**（本地 / SFTP / SMB 的 commit 均已原子替换：ATOMIC_MOVE+REPLACE / `CopyMode.Overwrite` / `rename(replace)`）；预删只保留「快路径腾位 / 目标类型冲突 / 不可续传目标」三类并写入 `deleteForOverwrite` 注释。「失败 / 取消后旧文件仍在」回归 2 例 + **变异验证**（临时还原旧行为 → 恰好这 2 例失败） |
| 🟡3 | ✅（轻量） | 占用语义显式化：任务行文案「已暂停（占用传输位）」+ `pause()` / 引擎 worker 双重注释；`TaskStateMachineTest` 新增 1 例把「暂停占位 → 后续等待 → 恢复接上」钉死（彻底修法落地时改此断言）。彻底版（暂停即释放槽位、resume 重新入队接续传）保持留档 |
| 🔵4 | ✅ | `PanelDb` v3→v4 迁移补 `idx_resume_source_dest(source, dest)`（`IF NOT EXISTS` 幂等，新旧库都覆盖） |
| 🔵5 | ✅ | vfs-local 首套回归 `LocalVfsSafetyTest` 6 例（符号链接删除保护 / 悬空链接 / commit 原子替换 / 续传截断尾巴 / rename 同卷+冲突 / 跨卷退化 `copyAndDelete`）；`rename` 退化段抽成 `internal` 供直接命中；模块补 `testOptions` + junit |
| 🔵6 | ✅ | `readFullyAt` 改 `FileChannel.read(buffer, position)` 位置读（不动共享文件指针）；`VfsStreams.readFullyAt` 补契约注释 |
| 🔵7 | ✅ | 删除死字段 `wholeDirectory` 与 `describe()` 不可达分支（含唯一赋值点） |

> 组织说明：`FakeVfs` 写入改为「落 `.part`、commit 才替换正式名」的忠实模型（旧替身直接写目标名，
> 会掩盖覆盖语义类差异）；验证口径：`compileDebugKotlin`（transfer / vfs-local / data / app）+
> `testDebugUnitTest`（transfer **41 例** / vfs-local **6 例** / app / data）全绿；🟡2 另做变异验证。
> 修复提交：`16f14ce`。`vfs-local` 的 `Os/StatFs` 注入脸面未做 —— 上述 6 例走的路径不依赖 android
> 静态调用；若要进一步覆盖 `stat` / 权限字段解析，再补 `LocalOs` 脸面。

---

## §5 模块审查：core/vfs-archive（2026-10-08 · 第 5 批）

> **结论一句话**：模块内部是高分区域——被 C1–C3 事故锤出来的自研解密读侧、三重路径穿越防御、U4 取消语义、34 用例（含 Python 外部交叉验证）本批真跑全绿；但**加密包的「闭环」只活在测试里**：装配层从未把口令传进 `ArchiveVfs`、UI 无输入入口，用户在应用内依旧「能建不能读」（🔴1）；另有 3 条 🟡（加密 STORED 的 seek 静默乱码 / 隐藏目录过滤失效 / 「同时加密文件名」是无效开关）。
> 范围：`core/vfs-archive/` 6 文件 / 1,546 行 + 测试 5 文件 / 1,106 行 / **34 用例**（本批 `--rerun-tasks` 重跑，`BUILD SUCCESSFUL`）　等级：🟡（维持——模块实现本身质量高，🔴1 是「模块 ↔ 装配」的断线）
> 方法：6 源文件 + 5 测试文件全文；34 用例逐条核对；交叉核对装配层（`AppContainer` / `BrowserControllerArchive` / `Dialogs`）与 `LocalVfs` 过滤语义；commons-compress 1.27.1 字节码 javap + JDK `skip` 行为实验。未跑真机。

**开工前基线核对**：本批行号为 `b51c3a2`（第 4 批修复 `16f14ce` + 收口已入档，工作树干净），与本批零交叉；文档插入点 = 末尾 `---` 与收尾备注之间。

### 🔴 阻断性问题（必须修）

#### 1. 加密包读侧在生产装配不可达：口令既没人传、也没处输——「能建不能读」在用户路径上原样复现
- 位置：`AppContainer.kt:341-345`（`openArchive` 签名无 password）与 `:409`（唯一的应用侧构造 `ArchiveVfs(host, kind, local, env)`，口令缺省 `null`；`ArchiveVfs.kt:57` 的 `password` 是构造器 val，无其它入口）；读条目时 `ArchiveVfs.kt:443` 抛 `Auth("该压缩包已加密，请输入口令")`；app 全仓无压缩包口令对话框（`口令` 全在连接体系，`ArchiveVfs(` 构造点仅此一处）。
- 问题：用户打开加密 ZIP → 能列表（中央目录可读）→ 打开/预览/解压任一文件 → 报「请输入口令」却**无处输入**，整条读链死路；加密 7z（commons 强制头加密）连 `connect()` 都过不去（`ProtocolError` 里还是底层英文异常）；`testArchive` 把「没口令」逐条计成「损坏」（`BrowserControllerArchive.kt:237-298`）。
- 为什么：v1.1.0 提交即宣称「加密压缩包闭环（C1–C3）」，`EncryptedArchiveRoundTripTest` 的 9 条端到端用例也全绿——但它用 `mount()` 直接构造 `ArchiveVfs(..., password)`，**恰好绕过了断线的那一层**。用户今天体验到的还是 C1 时代的「自产加密包打不开」，而测试给人「已闭环」的信心。这是本轮最容易被漏判的问题：功能测试全绿 ≠ 用户可达。
- 修复：① `openArchive` 增加 `password: String? = null` 并透传给构造器（挂载表缓存逻辑不变，无口令→有口令走 `forgetArchive` + 重挂载）；② 读条目捕获 `VfsException.Auth` 时弹「输入压缩包口令」对话框，验证后重挂载重试（会话内记住）；③ `testArchive` 对「需要口令」单独文案，不计损坏；④ 装配层补一条最小接线测试（传参 + 重挂载路径）。

### 🟡 建议修复（应该修）

#### 2. 加密 STORED 条目：`skip`/seek/`readFullyAt` 会在不推进密钥的情况下跳过密文 → 静默乱码
- 位置：`ZipCryptoStream.kt:79-97`（`DecryptingInputStream` 只重写 `read()`，`skip` 继承 `FilterInputStream` = 裸跳底层密文，密钥状态不动）；触发：`ArchiveVfs.kt:379-397`（`readFullyAt` 的 skip 循环）、`:407`（seek 后重开 + `openEntryStream(path, pos)` 的 skip，`:467-476`）。
- 问题：加密 STORED 条目上 `readFullyAt(p>0)` 与 `seek(p>0)+read` 返回乱码/错位数据，且 CRC 只在 `pos==0` 全程顺序读才校验（`:409-412`），**错误无声**。DEFLATED 不受影响（JDK `InflaterInputStream.skip` 是读式推进 inflater，本批实验确认 + 仓库的 DEFLATED 随机读用例互证）；现有随机读用例（`EncryptedArchiveRoundTripTest.kt:243`）恰好只盖 DEFLATED，STORED 用例（`:225`）只做全量顺序读。
- 为什么：静默错数据正是这块代码专门装 CRC、校验字节来防的东西——skip 路径恰好绕开全部防线。一旦 🔴1 接通口令，媒体预览（`MediaDataSource.kt:272` 按 `dataSpec.position` 打开）、编辑器分区读（`EditorFileIo.kt:112`）立即踩中。
- 修复：在 `DecryptingInputStream` 重写 `skip`，走循环 `read` 进临时缓冲丢弃（自然解密并推进密钥）；把 `:243` 用例参数化为 DEFLATED/STORED 两个变体。

#### 3. `showHidden=false` 与搜索词过滤对「目录」失效：压缩包内隐藏目录永远可见、搜索会拖出无关目录
- 位置：`ArchiveVfs.kt:258-291`——`childDirs` 在两道过滤之前收集（`:266` 深层合成、`:269` 显式目录），`showHidden`（`:270`）与 `filter`（`:272`）只作用于「叶子为文件」的 items；合成目录在 `:285-287` 无条件补入。对照 `LocalVfs.kt:106-109`（本地对**所有**条目统一过滤，是 `ListOptions` 的全仓语义）。
- 问题：① 关闭「显示隐藏文件」后，压缩包内 `.git` 一类隐藏目录照常显示；② 搜索 `abc` 时，名字不匹配的目录也会被合成出来、并带出无关层级。目录过滤只在文件路径上生效。
- 为什么：同一个开关/同一个搜索框，在本地、远程都对，一进压缩包就变样；`ListOptions` 是统一契约，这里私有语义，后续任何复用 `dirPaths` 模式的 VFS 会照抄这个错误。
- 修复：抽 `visibleDir(name)` = （`showHidden || !name.startsWith(".")`）&&（`filter` 为空 || `name.contains(filter, true)`），用于 `:266/:269` 两处 add；`:285` 合成循环自然继承。补用例（隐藏目录 + filter 命中/不命中）。

#### 4. 「同时加密文件名」是无效开关：`encryptNames` 三处流转零使用，7z 勾与不勾产物等价
- 位置：`ArchiveCompressor.kt:82`（声明）→ `:90`（传给 `compressSevenZ`）→ `:156-162`（收下从不读）；ZIP 加密路径 `:96` 不传；UI `Dialogs.kt:709-716`（勾选框 `enabled = supportsPassword` + 「7z：文件名一并加密」提示）。
- 问题：参数全链路无人使用。7z 带口令时 commons-compress 1.27.1 **强制**头加密（javap：`SevenZOutputFile` 无任何 header-encryption 开关），勾/不勾除随机量外产物等价；未勾选时用户会以为文件名可见（对方工具里实际被隐藏）。ZIP 忽略该选项是「有说明的弃权」，7z 是「静默做不到」。
- 为什么：选项说谎最难排查（换任何工具都复现不出预期）；死参数迟早被某个新调用点当真。若未来要让 7z 真正可选，需换/扩展写侧实现，别在原地假装。
- 修复：删 `encryptNames` 参数；UI 对 7z 改为固定说明行（「7z 带口令时文件名一并加密（库行为）」）或禁用勾选框；对 ZIP 保留现有说明。

#### 5. 包内编辑三部曲无进度、无取消，且取消会被兜底 catch 报成「失败」
- 位置：`BrowserControllerArchive.kt:330-345`（删）`:347-361`（改）`:363-389`（加）——三处均 `catch (e: Exception)` 兜底（`:341/:357/:389`）；`ZipEditor.kt:22` 注释「大 ZIP 会 O(size) 重写，UI 上会显示进度提示」与实现（`rewrite` 无进度/取消通道）不符。
- 问题：整包重写 = 本地 O(包大小) 重写 + 整包回传（大包在 SFTP/SMB 上分钟级），没有进度也没有取消；同时 `CancellationException` 会被兜底 catch 报成「修改压缩包失败：Job was cancelled」——同文件的 `extractArchiveTo`/`compressInto` 都单独 rethrow，这三处与 U4/U5 定下的「取消不是失败」不一致。
- 为什么：GB 级包编辑时用户被锁在不明进度里，唯一的「取消」是杀进程；catch 的议题现在靠「没有取消按钮」掩盖，一旦补取消就立刻变成可见 bug。
- 修复：照 U4 接法——`launchBusy` + `ZipEditor.rewrite(onProgress)` + 三处 `catch (e: CancellationException) { throw e }`；最起码先改 catch 与 `ZipEditor.kt:22` 的注释（一分钟的活）。

### 🔵 可选优化（可以修）

6. **整包重写丢条目属性**（`ZipEditor.kt:58-76`）：所有条目被重建为默认 DEFLATED（`STORED→DEFLATED`）；外部属性/权限位、comment、extra 全部丢失（重写一次含 exec 位的包后属性即蒸发）。修复：保留 `method`（STORED 走 `getRawInputStream` 原样搬运）、补拷 `externalAttributes` 与 `comment`。
7. **空压缩包反复重建索引、旧句柄被直接覆盖**（`ArchiveVfs.kt:142` 用 `index.isNotEmpty()` 当「已连接」标志；`:159/:178` 重开时直接覆盖 `zipFile/sevenZ` 不关旧值）：0 条目的包每次 `list/stat` 都重开一个 `ZipFile` 并丢弃上一个（靠 finalizer 兜底）。修复：加 `indexed` 标志（区分「空」与「未索引」），重开前 close 旧句柄。
8. **tar.bz2 能创建、不能打开**：`ArchiveCompressor.Format.TAR_BZ2` 可产出，但 `ArchiveVfs.ArchiveKind.ofFileName`（`:76-87`）不认识 `.tar.bz2`。修复：补 `BZip2CompressorInputStream` 读侧（kind 映射 + 包装各一行），或从创建列表撤下。
9. **压缩源会话缺失被静默跳过**（`ArchiveCompressor.kt:121/:179/:376` 三处 `?: return@forEach`）：条目静默缺席而最终状态仍报「已压缩为 …」。修复：汇总「N 项源不可用（已跳过）」提示，或快速失败。
10. **ZipCrypto 非 ASCII 口令的字节化**（`ZipCrypto.kt:36`）：`ch.code.toByte()` 是 UTF-16 码元截断，与注释「按平台默认编码取字节」不符、与 7-Zip 等（UTF-8/OEM）互不兼容——对方打的加密包即便口令正确也会报「口令不正确」。修复：两侧统一 `password.toByteArray(UTF_8)`，补非 ASCII 口令用例，并注明旧包兼容性权衡。
11. **加密 ZIP 条目数 ≥ 65535 静默截断**（`EncryptedZipWriter.kt:208` 两处 `u16(central.size)` 溢出）：与既有 4GB 检查（`:69`）同类，补一条「超过 65535 条目请改用 7z」拦截即可。
12. **三处无调用 API**：`Format.ofExt`（`ArchiveCompressor.kt:45`）、`Level.ofLabel`（`:63`）、`contentTypeOf`（`:419`）全仓零调用——删除或注明预留。

### 🟢 做得好的地方
- **路径穿越三重防御**：读取侧 `normalize`（`ArchiveVfs.kt:219-233`）拒绝绝对路径/`..`，写入侧 `safeEntryName`（`ZipEditor.kt:150-162`）覆盖改名与添加，且有专项用例（含测试构造的恶意包）。
- **C1–C3 读回闭环（读侧部分）**：自研 ZipCrypto 解密 + 「bit3 → DOS 时间高字节」校验字节的正确用法（`ArchiveVfs.kt:433-441`，含踩坑注释）、CRC 终读校验（256 分之一的漏网口令也跑不掉）、Python 交叉验证当外部 oracle——全仓少见的「用外部实现当裁判」的测试设计。
- **ZipCrypto 单测**：Python 参考向量逐字节、加解密不对称防回归、头随机化——教科书式的自研加密回归。
- **性能事故真修**：`dirPaths` 一次构建把列目录从 O(n²) 拉回 O(n)（`:108-130`）、顺序读持流不再「每块重开 + skip」（`:404-405` 注释记录旧实现灾难）。
- **U4 取消语义**：压缩器把 `CancellationException` 原样上抛 + `abort()` 清半成品，并有两条回归用例（`ArchiveCompressorCancelTest`）。
- **装配层 U4**：`openArchive` 单飞去重防并发下载、远程缓存按 (hash+mtime+size) 失效并清理旧版本（`AppContainer.kt:375-405`）。
- **数据安全取向**：加密包直接拒绝应用内增删改名（可执行中文文案），不静默降级。

### 安全（轻量两项抽查）
- 无硬编码密钥（测试口令均为占位符）；条目名 / 重命名 / 添加三处外部输入全有校验（见 🟢）。一句话带过：ZipCrypto 的已知密码学弱点与 `ZipCrypto.kt:83` 用 `java.util.Random` 生成头随机数，均属「与 MT 对齐 + 传统格式」层面的取舍，不另开条目。

### 下一批建议
- 下一模块：**core/vfs 远程协议**（🟡）——ftp / smb 零测试（571 / 576 行）+ webdav / sftp / s3 的 `readFullyAt` / `seek` / 续传契约与 transfer 衔接（与本批同类「skip 语义」风险的协议侧排查）+ S3 分片上传失败面。

> 备注：本批行号为 `b51c3a2` 基线；会话开始时在途的第 4 批修复已随 `16f14ce` 落地、收口随 `b51c3a2` 入档，与本批零交叉。修复随 `e4dd285` 落地（见下表）。

### 修复记录（第 5 批 · 2026-10-08）

| # | 状态 | 说明 |
|---|------|------|
| 🔴1 | ✅ | **加密包读侧全链路接线**：`AppContainer.openArchive` 透传口令（不符即重挂载、缓存替换）；`ArchiveVfs.checkPassword()` 三态探测（ZIP：中央目录加密标志 + 试读校验字节；7z：试读内容）；口令缺失/损坏异常映射为中文文案；应用侧 `mountArchiveInteractive()` 统一「进入 / 解压 / 完整性测试」的弹框→重挂载→复检→错误重试；新增「输入压缩包口令」对话框（挂起等待，与冲突框同模式）；`testArchive` 的「需要口令」单独计档，不再误报「损坏」。 |
| 🟡2 | ✅ | `DecryptingInputStream` 重写 `skip`（读式丢弃、经解密推进密钥）：加密 STORED 的 seek / readFullyAt 不再静默乱码。用例：STORED 随机读 + seek 回归 1 例。 |
| 🟡3 | ✅ | `list()` 抽出 `visibleDir()`：隐藏 / 搜索过滤对（合成）目录生效，与 `LocalVfs` 同语义。用例 1 例。 |
| 🟡4 | ✅ | 删「同时加密文件名」死开关 + 如实文案。**实测修正**：commons-compress 1.27.1 的 7z 写侧**不加密文件头**（探针 + raw 字节：next header = `0x01` 明文头）——原审查文字里「强制头加密」不成立，真实行为始终是「内容加密、文件名可见」；相关旧注释同步更正。 |
| 🟡5 | ✅ | `ZipEditor.rewrite` 增 `onProgress` + `ensureActive`；包内增删改三入口改 `launchBusy`（进度 + 取消，取消只回滚临时区）；`CancellationException` 不再被兜底 catch 报成失败。用例：取消回归 1 例。 |
| 🔵6 | ✅ | 重写保留条目属性：STORED 走 raw 搬运（带 size / crc）、补拷 `externalAttributes`（权限位）与 `comment`。用例 1 例。 |
| 🔵7 | ✅ | `indexed` 标志区分「空」与「未索引」；重建前先关旧句柄；`close()` 复位。用例：空包列目录稳定性 1 例。 |
| 🔵8 | ✅ | tar.bz2 读侧（`ArchiveKind.TAR_BZ2` + `BZip2CompressorInputStream`）。用例：创建 + 回读 1 例。 |
| 🔵9 | ✅ | 源会话缺失静默跳过 → 快速失败（`ArchiveCompressor` ×3 + `ZipEditor.additions`）。用例 1 例。 |
| 🔵10 | ✅ | ZipCrypto 非 ASCII 口令改 UTF-8 字节（Python zipfile 交叉验证）。用例 1 例。 |
| 🔵11 | ✅ | 加密 ZIP 条目数 ≥ 65535 拦截（`EncryptedZipWriter.finish()`）。 |
| 🔵12 | ✅ | 清死 API：`Format.ofExt` / `Level.ofLabel` / `contentTypeOf`。 |

> 验证：`core:vfs-archive` 测试 **46 例全绿**（本批新增 12：口令探测 4 / STORED skip 1 / ZIP 过滤 1 / 空包 1 / tar.bz2 1 / 源缺失 1 / ZipEditor 2 / 非 ASCII 1）；`app` 单测全绿；`compileDebugKotlin`（core + app）全绿。
> 修复提交：`e4dd285`。
> 说明：口令的「读时重试」未做全链路拦截（预览 / 编辑器等读取失败仍只给文案，回到压缩包重新进入即可再次触发弹框）；如需可把 `VfsException.Auth` 接到统一重试入口，另立项。

---

## §6 模块审查：core/vfs 远程协议（webdav / ftp / sftp / smb / s3）（2026-10-08 · 第 6 批 · 五协议联合）

> **结论一句话**：S3 的列表 XML 手写状态机有一个必现错误——列任何「含子目录的非根目录」都会**丢第一个子目录**，目录里同时有文件时还会把最后一条文件复制成一个**同名假目录**（kxml2 2.3.0 实物逐行实证）；此外 FTP 的控制连接模型（普通命令不在锁内）与编辑器保存的失败清理（FTP 上会泄漏控制锁导致会话死锁）各带一颗雷。webdav / sftp / s3 共 30 用例本批 `--rerun-tasks` 强制重跑全绿；ftp / smb 571 / 576 行仍为零测试。
> 范围：5 模块 / 15 源文件 / 3,767 行（webdav 839 · ftp 571 · sftp 777 · smb 576 · s3 1,004）+ 测试 3 模块 / 617 行 / 30 用例。等级：**🟡 维持**（缺陷各有清晰修法，本批未发现 S3 列表级之外的系统性损坏面）。
> 方法：15 文件全量通读 + 全仓交叉取证（TransferTask 生命周期 / AppContainer 装配 / SessionLocator / PreviewScreen.skip / EditorFileIo）+ **kxml2 2.3.0（Gradle 缓存实物，Android 内置 KXmlParser 的上游）把 `S3Client.listObjects` 解析循环逐行翻译为 JVM 程序、喂 7 组 AWS 风格响应样例（紧凑 / 缩进 / 分页 / 根目录）**。未跑真机。
>
> **开工前基线核对**：HEAD `b51c3a2 → d117924`（会话期间第 2 批收口入档）；工作树同时有**第 5 批修复会话在途**（vfs-archive / browser / AppContainer 口令接线等），与本批 5 个协议模块**零文件交叉**；本批引用行号已按当前工作树逐条实测校准。

### 🔴 阻断性问题

**1. S3 列表解析：`key` 残留顶替第一个 `<CommonPrefixes>` —— 丢子目录 / 假目录，必现**
- 位置：`S3Client.kt:202`（`key` 是跨条目复用的共享变量）、`:226`（`"prefix" -> if (text.isNotBlank() && (key.isEmpty())) key += text`）、`:232-240`（END 分支：`contents` 落条后**不清 key**，`commonprefixes` 拿残留 key 直接落条）。
- 问题：`key` 的清理点错位（只在 `</CommonPrefixes>` 清）导致两种必然污染——① 响应回显的 `<Prefix>dir/</Prefix>`（请求带前缀时**必然存在**）先占住 key；② 上一条 `<Contents>` 的文件名残留在 key。两者都会在下一个 `</CommonPrefixes>` 被当作「该目录的前缀」落条，而组内真正的 `<Prefix>` 文本因 `key.isEmpty()` 门槛被跳过。实测（A：2 文件+2 子目录 / B：2 子目录）：**A 丢 `s1/` 且 `f2.txt` 复制为同名假目录；B 丢 `s1/`**；只有根目录（回显为空）不受影响。另：同一状态机对缩进格式响应会把空白文本读进 key / size，并覆盖 `IsTruncated` / `NextContinuationToken`（D2 实证：truncated 被清回 false、token 清空、size=0）——触发条件是非紧凑 XML，一并修掉。
- 为什么：这是 S3 日常浏览的**主路径**——「文件夹少一个 + 多出一个点进去是文件的『文件夹』」；自 v0.3.0（`08387e7`）原样存在两个大版本，因为 5 条 S3 测试全部只覆盖签名与路径（`S3SigningPathTest` / `SigV4Test`），解析层零测试（单测里 `android.util.Xml` 是桩，是历史障碍）。
- 修复：重写为「父元素感知、每条目独立临时变量」的纯函数 `parseListResult(parser)`：回显 Prefix 不进任何 key；`CommonPrefixes` 只认组内子元素；TEXT 一律 `trim` 后非空才处理。补样例驱动回归（`testImplementation("net.sf.kxml:kxml2:2.3.0")` 驱动纯函数即可绕开 android.util.Xml 桩——kxml2 正是 Android 实现的上游），样例直接取自本批 A/B/D2 三例。

### 🟡 建议修复

**2. 编辑器保存（saveText）失败无任何 writer 清理——FTP 上会泄漏控制锁，整个会话永久卡死**
- 位置：`EditorFileIo.kt:177-193`（`openWrite` 之后没有 try / finally；异常直接上飘到 `:193` 的 catch 只报「保存失败」）。
- 问题：六个 `openWrite` 调用方里**唯一**没做生命周期清理的（TransferTask / ArchiveCompressor ×3 / ZipEditor / BrowserFileActions / ToolsScreens / 两个 touch 全部实现「失败 abort」）。后果分协议：**FTP**——对象连同 `controlMutex` 锁一起被丢（`FtpVfs.kt:449` 加锁后无人调 `:499 abort` / `:514 close`）→ 该会话后续所有操作在 Mutex 上无限等待，只能断开重连；WebDAV → 服务器遗留 `.name.panelfm.part`；S3 → 遗留未完成 multipart 分片（占空间、不显示）；SFTP / SMB → channel / handle 泄漏（SMB 的 part 句柄会锁在服务器上）。
- 为什么：编辑器保存是远程文件高频操作，一次网络闪断或中途取消就留下一处泄漏；对照 `TransferTask.kt:786-798` 的成熟模式（可续传→`close()` 保断点 / 否则 `abort()`），此处照抄即可。
- 修复：`try { write → commit } catch (e) { if (resumable) runCatching { close() } else runCatching { abort() }; throw e }`（可再补 finally close）。

**3. FTP 控制连接串行模型半途而废：普通命令根本不在锁内**
- 位置：`FtpVfs.kt:99`（`connected()` 的 withLock 只包住「建连接」）、`:150-153`（`withControl` 拿到 client 后在**锁外**执行 block）——list / stat / mkdir / delete / rename / setModified 全部如此；而 FtpReader / FtpWriter 反而手动持锁（`:396`、`:449`）。
- 问题：两个并发命令（双窗格同 FTP、刷新 + 传输、批量删除 + 浏览）会同时往**同一条** FTP 控制连接写命令读回复——commons-net 的 FTPClient 非线程安全，响应交错 = 命令与回复错配。另一面：长传输持锁到 `close()` 期间其它命令**无限等待、无超时无提示**。两个缺口是同一个「控制连接串行」意图的两半。
- 为什么：`:41` 的注释白纸黑字写着「控制连接串行（controlMutex）」，实现只串行了连接建立——是漏改不是取舍；FTP 是分诊表里零测试的协议，这类并发面只能靠审计发现。
- 修复：`withControl` 改为 `controlMutex.withLock { block(client) }`（建连与命令共用一把锁）；锁等待加 `withTimeoutOrNull` 并在超时时报「控制连接忙（可能正在传输）」而不是无限挂起。

**4. FTP 上传无原子落位：直接写目标文件，失败/取消会连累原文件**
- 位置：`FtpVfs.kt:447-468`（`storeFileStream(uri.path)` 直写目标）、`:487-497`（commit）、`:499-508`（abort 里 `:505 deleteFile(uri.path)`）、`:514`（close）。
- 问题：与 SFTP / SMB / WebDAV / 本地的 `.part` 落位设计相反——写侧直接对**目标名**开流，覆盖场景下原文件当场被截断；失败后 `abort()` 还把目标整个删掉（半成品与原文件一起没了）。`close()` 路径则留下半写文件。
- 为什么：注释只解释了「不做偏移续写」（服务器行为不一致），没解释为什么连 `.part` 也不用；FTP 的 RENAME 是标准命令，同款方案 SFTP / SMB 已在用。覆盖上传中断 = 用户数据损失，这是本模块数据安全面的最大缺口。
- 修复：写 `.name.panelfm.part`，commit 时 `deleteFile(target)`（若存在）+ `rename(part → target)`（或 RNFR/RNTO）；abort 只删 part，不再碰目标名。`resumable = NONE` 的整文件重传语义保持不变。

**5. SMB rename：目录改名必失败；源不存在时还会凭空造一个空文件**
- 位置：`SmbVfs.kt:325-343`（rename 先 `openHandle(..., write = true)`）、`:378-383`（openHandle 固定 `FILE_NON_DIRECTORY_FILE` + `FILE_OPEN_IF`）。
- 问题：`FILE_NON_DIRECTORY_FILE` = 「打开对象必须是文件」——对目录 open 直接失败 → **SMB 上文件夹改名 / 移动永远报「重命名失败」**；`FILE_OPEN_IF` = open-or-create——源不存在时不报错，而是**创建一个 0 字节文件再把它改名过去**（「重命名一个不存在的文件」的净效果是源位置多出一个空文件）。
- 为什么：目录改名 / 移动是 SMB 日常操作（现在只能报错收场）；「造空文件」更坏——把它从「可感知的失败」变成「静默脏写」，SMB 无回收站，用户事后根本不知道这个 0 字节文件从哪来。
- 修复：rename 前先 `getFileInformation` 判型（或 `folderExists`）；`openHandle` 增 `forDirectory` 分支（目录用 `share.openDirectory` / 不带 NON_DIRECTORY 选项）；重命名场景改用 `FILE_OPEN`（纯打开、不创建）。

**6. WebDAV 对「服务器忽略 Range」零校验：编辑器 / 预览会静默读到错位数据**
- 位置：`WebDavVfs.kt:365-388`（ensureStream 请求 `Range: bytes=$pos-` 后直接假定流从 pos 开始）、`:377`（`if (!r.isSuccessful && r.code != 206)`——服务器返回 200 全量也照单全收）、`:350-359`（readFullyAt 同款）。
- 问题：HTTP 允许服务器忽略 Range 返回 200 全量 body。此时流从文件头开始、代码却按 `pos` 定位——编辑器第 N 页会显示第一页内容且后续顺序读全部错位（改完保存 = 错数据回写）；`readFullyAt` 返回整文件而非请求窗口。没有任何响应校验兜底（对比：S3 / FTP 的 Range 语义由服务端协议保证，这里最脆）。
- 为什么：错位数据会一路流进编辑器「显示 → 保存」闭环（静默数据损坏）；不尊重 Range 的服务器不多但确实存在，而修复只是零成本的响应码断言——不校验等于把「偶尔错数据」当成可接受风险。
- 修复：两处断言 `r.code == 206 || (r.code == 200 && pos == 0L)`；不符时要么本地 skip 掉 pos 字节，要么抛 `ProtocolError`（宁报错不静默错数据）。

**7. S3 列表无分页：>1000 个子项静默只显示第一页**
- 位置：`S3Vfs.kt:119`（单次 `listObjects`，无 continuation 循环；删除 / 复制路径反而有完整翻页 `:209-213`、`:245-252`）。
- 问题：UI 没有「加载更多」，`list()` 也不翻页——超过 1000 个条目的目录只显示按 key 序的前 1000 条，无任何提示（S3 相册 / 备份目录常见）。
- 为什么：S3 相册 / 备份目录破千很常见；「少一半且无提示」会直接误导判断——用户把看不到当成不存在，进而重复上传或误以为文件已丢。
- 修复：`list()` 内补 `do { page } while (truncated && token != null)` 收集（解析层在紧凑响应下已能正确读出 truncated / token，见本批 D_page 实证）；若担心大目录渲染，至少给「仅显示前 1000 项」提示。

**8. SMB 根路径：`split()` 先抛致两处检查成死代码，「列出所有共享」从未实现**
- 位置：`SmbVfs.kt:148-159`（split 对空路径直接抛 ProtocolError）、`:206-212`（stat 的 isRoot 分支在 split 之后，到不了）、`:164-171`（list 同款）、`SmbConfig.kt:11`（注释「空 = 列出所有共享」）。
- 问题：不填共享名时用户看到的是「协议错误：请在连接设置里指定共享名（如 public / media）」——而那两句精心写的「或在地址里用 /共享名 进入」引导文案（Unsupported 分支）是**死代码**；Config 承诺的「空 = 列出所有共享」能力在实现里不存在（SMBJ 有 `session.listShares()` 可用）。
- 为什么：这是 SMB 连接的第一次体验——不填共享名是常态（用户未必知道共享名），当前只能拿到一句「协议错误」；而 Config 注释承诺的正是「让用户从共享列表里选」，不是让用户猜。
- 修复：把 isRoot 判断提到 split 之前（或让 split 返回可空）；「列出所有共享」要么实现、要么删掉 Config 注释与死分支、只保留一条文案。

**9. FTP / SMB 零测试债（571 / 576 行），S3 解析实测再证「协议层必须有测试」**
- 位置：`core/vfs-ftp/`（无 test 源集）、`core/vfs-smb/`（无 test 源集）、`core/vfs-s3/src/test`（仅签名 / 路径）。
- 问题：本批 🔴1 能潜伏两个大版本，直接原因就是解析 / 协议层零覆盖；FTP 是下载上传高频协议、SMB 是局域网主协议，全部行为只靠真机手测。
- 为什么：🔴1 就是最直接的代价——「目录里恰好有子目录」这种手测不会系统覆盖的组合，让一个必现 bug 活过了两个大版本；FTP 的并发与清理路径（🟡2/3/4）同样属于「手测撞不上、出事就是疑难杂症」的类别，没有回归测试等于每次修复都在赌。
- 修复：FTP——按 `SftpVfsTest` 的内嵌服务器模式补最小闭环（连接 / 列表 / 上传 / 删除 / 下载）；SMB——把 `split` / `partRel` / 偏移续写语义抽成纯函数补单测 + 列手动测试清单；S3——解析重写后必须带样例测试（见 🔴1 修复栏的 kxml2 方案）。

### 🔵 可选优化

**10.** SMB 连接建立无互斥（`SmbVfs.kt:101-146` `connectBlocking` 无锁；并发首开会双连并覆盖 `client/connection/session` 引用 → 泄漏一个连接）——修复 3 行（Mutex），对照 SFTP / FTP 均有锁；**11.** S3 `mutex` 过宽且不一致（`S3Vfs.kt:69`：包住 list / stat / delete 等，又漏 touch / openWrite；S3 无共享可变状态可护——建议删掉或注释动机）；**12.** S3 目录删除逐条 `deleteObject`（`S3Vfs.kt:209-213`：10 万文件 = 10 万请求；S3 原生有每批 1000 的 DeleteObjects 批量接口未用）；**13.** `FtpReader.readFullyAt` 契约违约（`FtpVfs.kt:378-393`：读了流但不更新 `pos`，与顺序 read 混用会带偏位置——当前全仓零消费，属预防性修复；要么实现为独立短流、要么文档化限制）；**14.** `FtpConfig.kt:27` 死分支（FTPS 两个分支都返回 21；隐式 TLS 应为 990）；**15.** WebDAV 是 `.panelfm.part` 唯一手写处（`WebDavVfs.kt:297-301`；契约 `VfsStreams.kt:21-23` 明说「必须用同一个名字，任何一处改动都会让续传悄悄失效」——改用 `partNameOf`）；**16.** `VfsCapabilities` 13 字段仅 4 个被消费（rename / serverSideCopy / resumable / permissions；`rangeRead/rangeWrite/space/symlinks/recursiveDelete/touch/setModified/streamingList/writable` 零读取——各协议 `recursiveDelete` 填法还不一致（webdav/ftp/s3 为 true、sftp/smb 为 false）却无人验证），删掉或注明预留；**17.** `SftpSession.kt:175` 把所有 `SshException`（含连接中断）一概映射成「认证失败」——用 `UserAuthException` 细分；**18.** 死承诺与死字段：`LOCAL_NETWORK_DENIED` 文案（`VfsExceptions.kt:20/47`）无人抛出且 `VfsEnv.kt:15 localNetworkAllowed` 传入后零消费（「Android 17 未授权时给明确错误」未接线），`SftpSession.kt:47 lastKeyWarning` 声明后从未赋值使用。

### 🟢 做得好的地方

- **S3 签名体系**：`SigV4Test` 对 AWS 官方向量逐字节一致 + `S3SigningPathTest` 的「发送路径 == 签名路径」不变量（锁死 `+`→`%2B`、表单解码两个历史坑）——全仓外部契约测试的标杆；这正是 🔴1 修复模板：解析层值得同等待遇。
- **SFTP 真服务器端到端测试**：内嵌 MINA SSHD（连接 / 建目录 / 上传 / 偏移续传 / 下载 / 重命名 / 删除 / 随机读 / 错误密码，5 用例）；跳板机两个历史 bug 的正确姿势注释（`break` 而非 `return@repeat`、`SshdSocketAddress` 非 `InetSocketAddress`）；TOFU 指纹变化拒绝带人类可读操作指引。
- **WebDAV 自研重定向拦截器**（307/308 保方法保 body、跨主机剥 Authorization、根尾斜杠保留）+ 17 用例覆盖；`DavXml` 的 `?c=` query 保留修复（当年「存储会话不可用」的根因，带回归测试）。
- **`.part` 原子落位家族**（SFTP / SMB / WebDAV / 本地 + 引擎 `partNameOf` 统一）与 `TransferTask.kt:786-798` 的 writer 生命周期契约（可续传→close 保断点 / 否则 abort）——本批 🟡2 正是对照它抓出的漏网。
- **诚实的能力声明**：FTP / S3 对「上传偏移续写不稳定」显式 `resumable = NONE` 并拒绝 offset（宁重传不写坏）；S3 multipart 边传边分（8 MB 内存地板）+ ETag 校验 + CDN 直链。

### 安全（轻量两项抽查）

- 无硬编码凭据（Kotlin 全仓；测试里 `AKIA…EXAMPLE` 为 AWS 公开文档值）。
- 外部输入：S3 key 逐段 RFC 3986 编码（含测试）；一句话提及——FTP 路径未做控制字符剥离、直接进入 commons-net 命令拼接（其 3.13 是否逐方法防护未核实），建议在 FtpVfs 入口统一拒绝 CR / LF；按约定不展开。

### 下一批建议

- 下一模块：**app/ui/connections**（🟡）——`ConnectionEditScreen.kt` 710 行 + 深缩进；重点：凭据写入 / 编辑回显的 secret 生命周期（`SecretStore` 与 `disconnectConnection` 的衔接）、选项面板（trustSelfSigned / 编码 / 跳板机字段）与各协议 Config 的一致性、测试连接路径。说「继续」即开审。

> 备注：本批行号为 `d117924` 基线；修复落地于 `03ea6aa`（core/vfs 六模块，与并行第 7 批 `a33f6d4` 零文件交叉）。

### 修复记录（第 6 批 · 2026-10-08）

| # | 状态 | 说明 |
|---|------|------|
| 🔴1 | ✅ | ListObjectsV2 解析重写为「父元素感知」状态机：回显 Prefix / Contents 残留 / 缩进空白三类污染全修（「丢子目录 + 末文件复制成假目录」实测必现级）。`S3Client.parserFactory` 测试接缝（修掉单测环境 `android.util.Xml` 是桩的历史障碍），样例驱动测试 6 例 + MockWebServer 分页端到端 1 例。 |
| 🟡2 | ✅ | `saveText` 失败补 abort/close 收尾（与引擎同款口径）——FTP 控制锁不再泄漏（会话死锁）；WebDAV/S3/SFTP/SMB 遗留 `.part` / 分片 / 句柄一并清理。 |
| 🟡3 | ✅ | 建连与全部命令共用同一把控制锁（`withControl` 入锁执行）；锁等待 30s 超时给「控制连接忙」文案（旧实现无限挂起）。 |
| 🟡4 | ✅ | FTP 上传改 `.part` 原子落位：写 part → commit `RNTO` 落位；abort 只清 part，不再 `deleteFile(目标)`（覆盖上传中断不再毁原文件）；0 字节提交（touch）补 `ensureOpen`。 |
| 🟡5 | ✅ | SMB `rename`：按类型打开（目录 `FILE_DIRECTORY_FILE`，此前目录改名必失败）；源缺失用 `FILE_OPEN` 直接报错（不再凭空造空文件）。 |
| 🟡6 | ✅ | WebDAV Range 校验：拒绝「忽略 Range 的 200 全量」（照读会静默错位数据）；`readFullyAt` 仅 `position==0` 时按窗口截取接受。 |
| 🟡7 | ✅ | `S3Vfs.list` 按 continuation-token 翻页拉全（>1000 条不再静默只显示第一页）。 |
| 🟡8 | ✅ | `splitSmbPath` 纯函数化 + 「根 + 无共享名」引导文案接通（两处死代码清理）；SmbConfig 注释据实修正（smbj 0.13 无共享枚举 API，不做「列出所有共享」）。 |
| 🟡9 | ✅ | FTP / SMB 首套测试基建：`SmbPathTest` 4 例、`FtpConfigTest` 4 例（S3 解析 7 例见 🔴1）。 |
| 🔵10 | ✅ | `SmbVfs.connect` 入 mutex（并发首开不再双连泄漏）。 |
| 🔵11 | ✅ | 移除 S3 全局 `Mutex`（无保护对象、还会串行化独立操作；结论注释在案）。 |
| 🔵12 | ✅ | S3 `DeleteObjects` 批量删除（每批 ≤1000，大目录 10 万请求 → 100）+ key 的 XML 转义。 |
| 🔵13 | ✅ | `FtpReader.readFullyAt` 收紧为「仅从 0 起的首次预读」+ 预读后同步推进 pos（旧实现读完不动指针）。 |
| 🔵14 | ✅ | 隐式 FTPS 默认端口 990（旧实现两个分支都写 21）。 |
| 🔵15 | ✅ | `partPathOf` 改用 `partNameOf`（`.part` 命名唯一来源契约的最后一处手写）。 |
| 🔵16 | ✅ | `VfsCapabilities` 注记消费现状（13 位仅 4 位被读取，其余属预留；勿据此驱动行为）。 |
| 🔵17 | ✅ | 删死字段 `lastKeyWarning`；SshException 不再一律映射「认证失败」——认证阶段在调用点归类 Auth、其余按网络错误（sshd 2.x 无独立认证异常类，替代方案已注明）。 |
| 🔵18 | ✅ | `VfsRegistry.acquire` 接线 Android 17 局域网授权（`LOCAL_NETWORK_DENIED` 文案此前无人抛出）。 |

> 验证：core 六模块 **62 用例全绿**（vfs-api 17 / vfs-s3 15 / vfs-ftp 4 / vfs-smb 4 / vfs-webdav 17 / vfs-sftp 5；本批新增 15：S3 解析 6 + 分页 1 + SMB 路径 4 + FTP 配置 4）；`app` 单测、transfer / vfs-local / vfs-archive / data / ui 回归全绿（并发构建的产物竞态重试后通过——与第 7 批记录同一现象）。
> 修复提交：`03ea6aa`（core/vfs 六模块 + 编辑器收尾 + 测试，18 文件，+699/−174）。

---

## §7 模块审查：app/ui/connections（2026-10-08 · 第 7 批）

> 结论一句话：S3 连接**编辑一次就坏一层**——回填按 SFTP 格式解析、保存按 `AK:SK` 再拼一次、连接端只取第一段：三处各自「看起来对」，合起来每保存一次给 secret 多叠一层 `AK:`，认证必挂（纯代码级证据链，故障在所有 S3 用户日常编辑路径上）。另有 3 个「显示但零消费」的静默字段（SFTP 编码、SMB/S3 根路径）、WebDAV IPv6 编辑保存损坏 host、Android 17 未授权时扫描静默全空等 5 项 🟡。
> 范围：`app/src/main/kotlin/com/u707t/panelfm/ui/connections/`（3 文件 / 922 行：ConnectionEditScreen 710 / LanScanScreen 129 / WebDavUrl 83）+ 测试 1 文件 / 74 行（仅 WebDavUrlTest）。等级：**🟡 维持**。
> 方法：3 文件全量通读 + 全仓交叉取证（SecretStore / ConnectionDao / VfsRegistry / saveSecret 全链 / SmbVfs / S3Config / FtpConfig / DavHttp / HomeScreen / AppRoot / LocalNetwork）+ 逐字段「UI 显示 ↔ 落库 ↔ 协议消费」三方对照。未跑真机；🔴1 与 🟡4 为字符串级完整推演（修复栏含对应测试要求）。
> **基线核对**：HEAD `b9dd0f3`（第 5 批修复收口 + §6 已提交）；开工时工作树干净，审读期间第 6 批修复会话全面在途（属 §6 修复清单的多协议文件 + 新测试），与本批 3 文件零交叉。

### 🔴 阻断性问题

**1. S3 编辑连接后 secret 被层层加前缀——每次保存多叠一层 `AK:`，认证必然失败**
- 位置：`ConnectionEditScreen.kt:83-85`（回填）、`:94`（password 状态）、`:238-239`（保存拼接），`S3Config.kt:38-40`（连接端拆分）。
- 问题：三处约定各自成立、组合成数据损坏链——① 回填统一走 `SftpSecrets.parse(loadSecret)`（`:84`）：S3 的 secret 是 `"AK:SK"` 拼接串，不以 `{` 开头被当成裸密码，整串塞进 `password` 字段（`:94`）；② 保存走 `buildSecret()`：`"$user:$password"` → `"AK:AK:SK"`；③ 连接走 `S3Config.from`：`split(':', limit=2)[1]` 取到 `"AK:SK"` 当 secretKey → 签名错误（403）。**编辑页打开即已污染**：只点「测试」也会失败（测试用 `buildSecret()` 直传，`ConnectionEditScreen.kt:343`）；保存后（`:282-291`）旧会话因 `oldSecret != secret` 被断开，下次连接用污染值。每编辑保存一次再叠一层。
- 为什么：这是 S3 日常主路径——改备注 / 换 bucket / 调 region 都会路过保存；症状是「AK/SK 明明没动，连接突然认证失败」，用户在密码框（掩码）里看不出被加了前缀，也绝不会怀疑是编辑器。`SftpSecrets` 的解析/序列化与 S3 secret 编解码**全链零测试**（`core/vfs-sftp` 无 SftpSecrets 测试；本模块测试只覆盖 URL），所以三处约定没有一处被往返测试锁住。
- 修复（二选一）：
  - A) 最小修——回填按协议拆：S3 时 `password = secret.split(':', limit = 2).getOrElse(1) { secret }`（与 `S3Config.from` 的「无冒号=整串是 SK」兼容分支对齐）；重建的 `"$user:$password"` 与旧串相等时不会触发误断连。
  - B) 正解（推荐）——存储格式改纯 SK：`buildSecret()` 的 S3 分支改 `password.ifEmpty { null }`；`S3Config.from` 无需改（`:40` 已兼容裸 SK）；回填一次拆旧 `"AK:SK"` 前缀。加一张**往返测试表**（5 协议 × 有/无 secret × 旧格式输入），锁死「回填→保存」恒等。
- 前提：先做 🟡6 的纯函数抽取，测试才有挂点。

### 🟡 建议修复

**2. SFTP「编码」选择器是静默无效开关——作者自己立下的规则在此漏网**
- 位置：`ConnectionEditScreen.kt:602-618`（UI）、`:212-215`（写入 options）；`core/vfs-sftp/` 全目录对 `OPT_ENCODING` 零引用（消费方不存在）。
- 问题：编码选择器（UTF-8 / GBK / GB18030 / Big5）在 FTP / FTPS / SFTP 三种类型下显示并落库，但 SFTP 侧无人读取——SFTP 文件名编码由 SSH 协议固定 UTF-8，本就无法由该选项改变。而 `:581-584` 的注释刚写下「只对**真正读取该选项**的协议显示开关……静默无效的开关比没有开关更糟：用户会以为自己已经放开了校验」——编码区块正是同一次修复的漏网。
- 为什么：用户选 GBK 后以为治好了乱码（没治好，且无任何反馈），实际会把排查方向带偏；规则已经写进代码却没执行完，说明「UI 显示 ↔ 消费方」靠人工同步不可靠——这正是本批做三方对照的原因。
- 修复：显示条件（`:603`）与写侧条件（`:212`）都从 `FTP || FTPS || SFTP` 收回 `FTP || FTPS`（各一行）；在附近注明「SFTP 固定 UTF-8，无编码选项」。不建议为 SFTP 接线（协议层行为）。

**3. FTPS「隐式 TLS（990 端口）」不联动端口——开了开关仍连 21；配置层 990 兜底被 `port > 0` 屏蔽**
- 位置：`ConnectionEditScreen.kt:597`（开关只改状态）、`:178-180`（端口重置只在新连接时），连接端 `FtpVfs.kt:86` / `:110`（`FTPSClient(true)` + `connect(cfg.host, cfg.port)`），配置层兜底 `FtpConfig.kt:25-31`。
- 问题：开关 label 承诺「990 端口」，但打开后没有任何代码把端口 21 → 990——`FTPSClient(true)` 在 21 端口上做隐式 TLS 握手，标准服务器必然失败，用户须自行悟到「去改端口」，而 label 恰恰让他别想。配置层兜底（`:31`：`else if (implicitTls) 990`）被前置 `config.port > 0` 屏蔽：UI 端口恒 ≥1（有 1..65535 校验），兜底只对脏数据生效——**对真实用户路径一步没走**（该兜底为审读期间修复会话新补，`FtpConfig.kt:30` 注释即此意图）。
- 为什么：一开即错，报错是「连接被拒 / 握手失败」，不会指向端口；对目标用户（隐式 TLS / 990 服务器）100% 踩中；「标准端口 990」的注释还会让后续维护者误以为已处理。
- 修复：UI 开关联动端口（开且 `port=="21"` → `"990"`；关且 `port=="990"` → `"21"`）；`FtpConfig` 兜底保留（防脏数据），注释注明「不覆盖 UI 默认 21 的场景，联动在 UI 层」。

**4. WebDavUrl 互转不闭合：IPv6 丢失方括号（编辑保存即损坏 host）、host 区不剥 `?`/`#`**
- 位置：`WebDavUrl.kt:79-81`（build）、`:39-40` 与 `:56-64`（parse 的 hostPort 段）、`:68`（query/fragment 只在 rawPath 上剥）。
- 问题：① `parse` 支持 `[fe80::1]:5244` 并剥离括号存 host，`build` 却不回填括号——编辑任何 IPv6 连接时 URL 框已显示成 `http://fe80::1:5244/dav`（错的），用户点保存 → `applyUrlIfWebDav` 再解析 → 多冒号落入 `:61-63` 防御分支 → **host 落库为 `"fe80::1:5244"`、port 重置 80**——零修改的「打开→保存」就损坏配置。② 无路径 URL（`http://host?x=1`）的 `?`/`#` 不剥（只处理了 rawPath），query 被并进 host 落库。
- 为什么：静默数据损坏（用户没动 host 却被改坏，之后连接必挂且难归因）；`WebDavUrlTest` 的 `buildRoundTrip` 只测 IPv4、IPv6 只测 parse——单侧测试掩盖了不闭合。IPv6 NAS（fe80/ULA）用户占比小，但「打开-保存即坏」的确定性损坏配 🟡。
- 修复：`build` 对含冒号的裸 IPv6 加回方括号（`if (host.contains(':') && !host.startsWith("[")) "[$host]"`）；parse 在 `:40` 后先对 `hostPort` 剥 `?`/`#`（`[::1]?x` 场景会自然落入 null 拒绝）。补测试：IPv6 `build→parse` 往返恒等 + `http://host?x=1` 用例。

**5. Android 17 未授权时 LAN 扫描静默全空——权限判据用错了条件，文案触发点与授权无关**
- 位置：`LanScanScreen.kt:38`（prefixes）、`:49`（「未检测到（需要局域网访问权限）」文案）、`:77-92`（扫描块）；对照 `LocalNetwork.kt:13-19`、`HomeScreen.kt:270-276`、`AppRoot.kt:202-216`。
- 问题：进入扫描页无任何 `LocalNetwork.isGranted` 检查——API 37+ 未授权时探测全部失败，用户拿到「已发现 0 台」且无任何指引；而权限文案的触发条件是 `prefixes.isEmpty()`（网卡枚举结果，与授权状态无直接关系）——需要它的场景不出现，不需要它的场景（无网络接口）才出现。对照：启动对话框（`AppRoot.kt:202-216`，文案自己都写着「未授权时连接会直接超时且没有提示」）与首页卡片（`HomeScreen.kt:270-276`）都有引导，唯独最依赖该权限的扫描页没有。
- 为什么：用户在启动对话框点「稍后」→ 进扫描页 → 0 台——功能看起来「坏了」，无恢复路径；此为 §6 🔵18（`LOCAL_NETWORK_DENIED` 连接层无人抛出）在 UI 侧的另一端，两处各自半截。
- 修复：进页或点「开始扫描」前检查 `LocalNetwork.isGranted`，未授权时直接显示授权按钮（复用首页 PermissionCard 模式）；扫描结束 0 台且未授权 → 明确提示「未授权，结果不可信」。

**6. 表单的状态 / UI / 组装 / 校验四向分散、零测试——🔴1 的温床**
- 位置：`ConnectionEditScreen.kt:81-148`（约 40 个状态声明）、`:151-176`（私钥导入）、`:203-247`（options / secret 组装）、`:260-347`（保存 / 测试流程）、`:379-680`（UI）。
- 问题：一个字段的生命周期要跨四段代码手工同步（默认值、UI 控件、组装、回填），5 协议 × N 字段没有单一事实源；secret 编解码写死在 `buildSecret()` 闭包里（不可测），回填散在 `:84 / :94 / :131 / :136`。🔴1 正是「回填端按 SFTP 格式、保存端按 S3 格式、连接端按 `split` 取值」三个远端各自演进、没有任何往返测试兜底的产物；本批核对出的 3 个「显示 ↔ 消费」不一致（🟡2、🔵8）同为此类漏网。710 行本身不必拆 UI，但这个逻辑基线必须先收拢。
- 为什么：加一个字段 / 协议要同步改 4 处，任何一处漏掉都是静默故障；修 🔴1 只能靠人肉比对三处代码，下次同样会漏。
- 修复（渐进三步）：① 抽 `ConnForm`（data class）+ 独立文件 `ConnFormCodec.kt`：`buildOptions(form)` / `buildSecret(form)` / `parseSecret(type, raw)` / `validate(form)` 全部纯函数；② 首套测试：S3 secret 往返（含旧格式兼容）、SftpSecrets JSON 往返、选项矩阵（每协议应写/不应写哪些键）、validate 边界（端口 / URL / SMB share）；③（可选，最后做）UI 按协议拆区块 composable。

### 🔵 可选优化

**7.** `secure` 开关的 443 静默失效：只在 `true` 时写入（`ConnectionEditScreen.kt:204`）+ Config fallback `port==443`（`DavHttp.kt:31`、`S3Config.kt:41`）——443 端口上「关闭 HTTPS」/「显式 `http://`」都改不回；修复：编辑加载时按同款 fallback 初始化 `secure`，相关协议保存时无条件写 `"secure"=值`（旧数据加载即得真实值，回写不回退）。**8.** SMB / S3 的「根路径」字段语义错位（`ConnectionEditScreen.kt:468-475`、`Connection.kt:55-66`、`SmbVfs.kt:562-572`）：SMB 上 openPath 首段=共享名（`splitSmbPath` 的 `parts.first()`，`:569`）——「共享名=public + 根路径=/docs」（最自然的表达）会去找共享 docs（`shareOf` 报「打开共享失败：docs」，`:143`）；S3 上它是与「初始路径」重复的 key 前缀。修复：SMB 的 label / placeholder 指向「/共享名/子目录」模型或隐藏该框（配合 §6 🟡8）；S3 隐藏（用「初始路径」表达）。**9.** `insert()` 返回 -1 未检查（`ConnectionEditScreen.kt:294-295`、`ConnectionDao.kt:26-30`）：DB 写失败仍 `saveSecret(-1, …)` 并正常返回；修复：`id <= 0` 时置错误状态并 return。**10.** busy 期间「取消」可中断保存（`ConnectionEditScreen.kt:676`）：`rememberCoroutineScope` 随组合销毁取消协程，`saveSecret`（`:282`）与 `update`（`:288`）之间退出会留下「新 secret + 旧配置」半态；修复：取消按钮 `enabled = !busy`（或 busy 时先确认）。**11.** LanScan 回调从并发协程直接读改写 Compose 状态（`LanScanScreen.kt:83-85`、`LanScanner.kt:64-67`）：`found = found + host` / `done = …` 是非原子 RMW，多主机同时命中时理论丢条目 / 进度回退；修复：UI 侧用 Mutex 包两个回调（`onFound` 已是 suspend）或经 Channel 收集。**12.** 扫描结果不带协议线索（`AppRoot.kt:293`、`ConnectionEditScreen.kt:87`）：扫到 445 / 21 端口点「建连接」仍默认打开 SFTP 表单；修复：onPick 按端口映射 `initialType`（22→SFTP、21→FTP、445→SMB、80/443→WEBDAV）。**13.** 编辑既有连接切协议时端口不跟随（`ConnectionEditScreen.kt:178-180` 仅 `existing == null` 时重置）：FTP→SMB 后 port 仍 21；修复：type 变化时若 port 仍是旧协议 defaultPort 则同步新默认值。

### 🟢 做得好的地方

- **secret 失败语义的 UI 消费**：`SecretStore.put` 的布尔返回被两处正确消费——更新失败提示（`:282-285`）与新连接失败回滚 `delete`（`:295-299`）；「新连接失败不留半成品」的注释与实现一致，第 5 批成果在 UI 层没有漏掉。
- **doTest 的细节**：6 秒后切换「卡住提示」、FTP 专属「主 / 被动模式」引导（`:313-325`）——把「卡住」从玄学变成可操作提示。
- **端口输入校验双闸**：filter 数字 + take(5) + 保存（`:270-277`）与测试（`:335-340`）各一次 `1..65535` 范围校验——对照注释里旧实现「写进 DB 才在连接时暴露」是实打实的修好。
- **开关类选项「显示范围 = 消费范围」主体成立**：trustSelfSigned（严格 4 协议，FTP/FTPS/WEBDAV/S3 消费方逐一对上）、passive、UA、hiddenInDrawer / loadThumbs（`SideDrawer.kt:311`、`Thumbnails.kt:153`）全部对得上——唯一漏网是 SFTP 编码（🟡2）。
- **WebDavUrlTest**：7 用例覆盖 `+`→`%2B`（不把 `+` 解成空格）、rejects 矩阵（0 / 70000 / 非数字端口）、默认端口、尾斜杠归一——纯函数 + 测试的组合让「URL 单行输入」这个复刻功能站住了。
- **Android 17 权限链**：`LocalNetwork` 抽象 + 启动对话框（`AppRoot.kt:202-216`，文案连「未授权会超时且没有提示」都写清）+ 首页卡片 + Manifest 声明——缺的只是扫描页接入（🟡5）。

### 安全（轻量两项抽查）

- 无硬编码凭据：模块内口令全部走 `SecretStore`（Keystore AES-GCM 加密落库），示例文案不含真实凭据。
- 外部输入：SAF 导入的私钥文件名直接落 `keysDir`（`ConnectionEditScreen.kt:155-157`），无显式清洗；`File(keysDir, picked)` 为相对构造 + `substringAfterLast('/')`，未见实际越界（`..` 会在打开目录时报错而非写出）。一句话提及、按约定不展开。

### 下一批建议

- 下一模块：**app 壳层**（🟡）——`AppContainer.kt` / `AppRoot.kt` / `RemoteHttpServer.kt`（242 行对外 HTTP 面）/ service 等 10 文件 ≈1,500 行。重点：`RemoteHttpServer` 的对外暴露面、AppContainer 装配剩余线头（`env` / `uriForConnection` / `mounted` 本批已见三处）、启动与恢复流程。说「继续」即开审。

> 备注：本批行号为 `b9dd0f3` 基线（会话期间第 6 批修复在途，与本批 3 文件零交叉；引用行号已按在途版校准）。修复随 `a33f6d4` 落地（🔴1–🔵13，见下表；🟡6 为渐进第 1 步）。

### 修复记录（第 7 批 · 2026-10-08）

| # | 状态 | 说明 |
|---|------|------|
| 🔴1 | ✅ | 口令编解码收拢到 `ConnectionSecrets`（纯函数）：S3 落库**纯 SK**、回填对旧 `AK:SK` 取 SK 部分；读取端 `S3Config.from` 兼容两种历史形态不变。消灭「编辑保存一次多叠一层 `AK:`」；11 用例锁定往返与兼容。 |
| 🟡2 | ✅ | SFTP「编码」显示 / 写侧范围收回 FTP / FTPS（协议层固定 UTF-8、无消费方——与「只对真正读取的协议显示开关」原则对齐）。 |
| 🟡3 | ✅ | 「隐式 TLS（990）」开关联动端口（开 21→990 / 关 990→21；手改值不动）。配置层 990 兜底（第 6 批修复会话所补）对 UI 默认 21 不可达，本批从 UI 侧打通实际路径。 |
| 🟡4 | ✅ | `WebDavUrl`：`build` 还原 IPv6 方括号、`parse` 剥 host 区 `?`/`#`。3 用例（IPv6 往返 / query / fragment）。 |
| 🟡5 | ✅ | 扫描页权限：未授权显示「去授权」引导条（`LocalNetwork.isGranted`）；「本机网段」空值文案与权限脱钩。 |
| 🟡6 | ◐ | 第 1 步完成：口令链纯函数化 + 测试（🔴1 的挂点）；`FormState` / options 组装收拢留后续（渐进第 2、3 步）。 |
| 🔵7 | ✅ | `secure` 编辑加载按 `port==443` fallback 初始化（与 DavConfig / S3Config 对齐）+ WebDAV / S3 保存无条件写布尔值：443 端口上「关闭 HTTPS / 显式 http://」现在真实生效。 |
| 🔵8 | ✅ | SMB「根路径」→「起始路径（含共享名，如 /public/docs）」、placeholder 同步；S3 隐藏该字段（无消费方，起始前缀用「初始路径」）。 |
| 🔵9 | ✅ | `insert()` 返回 -1 检查：失败给明确文案，不再对 -1 写孤儿 secret 还当保存成功返回。 |
| 🔵10 | ✅ | busy 中禁用「取消」+ 拦截系统返回（`BackHandler`，EditorScreen 同款模式）。 |
| 🔵11 | ✅ | 扫描结果回调经 `Mutex` 串行化（并发「读-改-写」防丢条目）；进度回调维持非 suspend API（影响仅瞬态显示）。 |
| 🔵12 | ✅ | 扫描端口 → 预选协议（22/21/445/80/443，`connectionTypeForScanPort`）；2 用例。 |
| 🔵13 | ✅ | 端口跟随协议默认值（仅当未手改过）；编辑既有连接切协议（FTP→SMB 后端口仍 21）一并修掉。 |

> 验证：`app` 单测**全量 25 类 / 154 用例全绿**（本批新增 16：ConnectionSecrets 11 / LanScanPortHint 2 / WebDavUrl +3）；依赖链（core + app）编译全绿。全量经 `--rerun` 强制重跑（期间与第 6 批修复会话的并发构建发生过产物竞态，重试后通过）。
> 修复提交：`a33f6d4`（`app/src` 8 文件，+313/−27）。

---

## §8 模块审查：app 壳层（2026-10-08 · 第 8 批）

> 结论一句话：装配与生命周期主体扎实——租约 / 前台服务 / 对外 HTTP 三条边都有实测级保障，未发现阻断级问题；
> 本批收口 2 个 🟡（回收站索引的回滚缺口与并行写、release 日志过滤空转）与 2 个 🔵（连接失败仍登记「持有中」、
> 远程管理页面状态与服务真实状态脱节），另记录 1 个有意留待的结构观察（压缩包挂载回收）。
> 范围：`app/src/main/kotlin/com/u707t/panelfm/` 根（AppContainer 440 / MainActivity 59 / SessionLocator 31 /
> LocalNetwork 30 / PanelApp 27）+ `ui/AppRoot.kt` 321 + `service/TransferService.kt` 165 +
> `tools/`（RemoteHttpServer 242 / TrashService 207）＝ 9 文件 / 1,522 行。等级：**🟡 维持**。
> 方法：9 文件全量通读 + 交叉取证（VfsRegistry 租约语义 / ConnectionEditScreen 保存链 / HomeScreen 连接链 /
> LocalVfs.absolutePath / 四协议 connect() 重试语义 / PrefsStore / TransferEngine.taskEvents / ArchiveVfs 句柄 /
> app 构建与 Manifest）；未跑真机。
> **基线核对**：HEAD `0a923a0`（第 6 批文档收口）；开工时工作树干净，零交叉。

### 🟡 建议修复（应该修）

**1. 回收站：跨卷回滚缺口 + 索引读-改-写无串行——「删了但回收站里没有」的两个漏网**
- 位置：`TrashService.kt:114-119`（移入：renameTo 失败即复制+删源）、`:141-143`（回滚只试 `dest.renameTo(src)`）、`:97-146 / :149-174 / :192-204`（四个操作的「读索引→改→写」序列）。
- 问题：① 进入复制路径的常见原因恰是**跨卷**（/storage/emulated 与 /data 不同挂载点），而回滚只试 `renameTo` —— 索引写失败需要回滚时，回滚本身必失败：文件滞留回收站、无索引条目 = 用户视角「删掉了但回收站没有」。② `list()` 只保证单次读原子；`moveToTrash / restore / purge / purgeAll` 各自「读-改-写」无跨段互斥——两个并行操作（大文件移入回收站的同时在回收站里清空）按旧快照互相覆盖索引：丢条目、文件成孤儿。
- 为什么：这两处正是该服务注释自己承诺要防的「静默丢数据」形态。触发概率不高（依赖写失败 / 并行操作），但一旦发生**用户无法自助恢复**（孤儿文件不在列表里，肉眼不可见）。
- 修复：正/反向移动统一为 `moveFile(from, to)`（renameTo || 复制+删源）；四个操作以 `Mutex` 串行化整段读改写；补首套单测（移入 / 回滚 / 还原重名不覆盖 / purge）。

**2. release 日志过滤「空转」：`enabled=DEBUG` 把 WARN+ 一起关掉，且 `w()` 不看 minLevel**
- 位置：`PanelApp.kt:16-17`、`Logx.kt:12`。
- 问题：`Logx.enabled = BuildConfig.DEBUG` → release 连 WARN / ERROR 一起静默，注释宣称的「release 只保留 WARN 以上」从未生效；同时 `Logx.w()` 只查 `enabled` 不查 `minLevel` —— 四个等级里三个查、一个不查，过滤器语义不一致。
- 为什么：发布版出事时 logcat 里什么都没有，诊断只剩「复现猜」；而作者要的是「WARN 以上留痕」。
- 修复：过滤统一交给 `minLevel`（release = WARN），`enabled` 保持「可整体静默」的开关语义；`Logx.w` 补 `minLevel <= WARN`。

### 🔵 可选优化

**3. 连接失败仍登记为「持有中」：`heldLeases` / `mounted` 在 connect 之前写入**
- 位置：`AppContainer.kt:263-274`。
- 问题：`vfs.connect()` 失败后，该会话仍被登记为「App 正在引用」（refs≥1 → 空闲回收器**永不回收**），`mounted` 也指向一个从未连接成功的实例；兜底查找拿到的是它。重试虽因协议层惰性重连可用，但状态账实不符。
- 修复：connect 成功后再登记；失败释放租约、原样抛出。

**4. 远程管理：页面状态与「服务真实状态」脱节**
- 位置：`ToolsScreens.kt:295-300`（local `running`/`url`）、`RemoteHttpServer.kt:32/54/94`。
- 问题：服务不随页面销毁（启动后切走再回来，服务仍在跑），页面每次进入却从 `false` 初始化：明明在跑却显示「启动服务」、URL 空白；也不显示「正在服务哪个目录」。
- 修复：状态从 `container.remote` 真实值初始化；暴露 `url` / `servedRoot` 并在页面展示；顺带给请求头读取加行数上限（防无限发头）。

**5. 压缩包挂载只增不减（记录在案，未改）**
- 位置：`AppContainer.kt:109 / :341-427`（`archives` 缓存；仅口令更换 / 手动刷新时 `forgetArchive`）。
- 问题：同一会话打开过的每个压缩包都常驻（ZipFile 句柄 + 中央目录内存）；没有上限或 LRU。
- 修复思路：在 60s 空闲回收循环里按「上限 + 最久未用」淘汰。未在本批动手：淘汰判断需要「窗格是否仍在该包内」的引用信息（跨 BrowserController 状态），留待专门批次。

### 🟢 做得好的地方

- **租约模型（refs / pinned / closeIdle + 60s 兜底）**：会话生命周期三段式，`heldLeases` 的「浏览中的连接不被回收」注释与实现一致——本批的两个 🔵 都是这个模型边角上的收口，模型本身是对的。
- **`RemoteHttpServer` 路径防线**：`decodeRemotePath`（`+` 不做表单解码）+ `normalizeRemotePath`（拒绝任何越根）+ 7 例测试；HTML / 头注入（转义 / CRLF 剥离 / 引号过滤）逐项落实。
- **`TransferService` 前台化边角**：`startForegroundSafe` 失败即 `stopSelf`（不吃 5 秒硬约束的崩溃）、通知点击回 App、`START_STICKY` 重启后无任务自动收场——三条坏路径都不装死。
- **`TrashService` 既有防线**：索引原子写（tmp + ATOMIC_MOVE）、还原重名不覆盖（(1) 后缀）、批量写一次索引——本批 🟡1 是把同一标准补到回滚与并发上。
- **壳层权限链**：storage → local-net → notification 三段引导 + `LocalNetwork` 抽象 + API 37 分支；第 7 批扫描页接线后权限故事闭环。

### 安全（轻量两项抽查）

- 无硬编码凭据：壳层源码零命中（全仓 `ghp_` / `AKIA` / 私钥头扫描仅测试向量与注释）；口令只走 `SecretStore`。
- 外部输入：远程管理是壳层唯一外部面——路径规整有 7 例测试（v1.10.1 收口），本批补请求头行数上限（100 行）。一句话提及：`lanAddress()` 取「第一个非回环 IPv4」，移动数据网优先时 URL 可能指向不可达网卡（低风险，按约定不展开）。

### 下一批建议

- 下一模块：**app/ui 周边页**（🟡）——`ui/home/HomeScreen.kt` 534 + `ui/settings/SettingsScreen.kt` 371 + `ui/tools/ToolsScreens.kt` 354 + `ui/tools/DiffScreens.kt` 337 + `ui/tasks/TasksScreen.kt` 275 + `ui/bookmarks/BookmarksScreen.kt` 183（6 文件 / 2,054 行）。重点：各设置开关的「UI ↔ 消费方」一致性（第 7 批同款对照法）、Home 的连接 / 存储两条入口状态、任务页与引擎的取消 / 冲突语义、书签的 URI 生命周期。说「继续」即开审。

> 备注：本批行号为 `0a923a0` 基线；修复落地于 `1a2e613`（9 文件，+274/−89；含测试基建：app 模块 `returnDefaultValues` + 真实 org.json）。

### 修复记录（第 8 批 · 2026-10-08）

| # | 状态 | 说明 |
|---|------|------|
| 🟡1 | ✅ | `moveFile` 正反向共用（跨卷复制兜底——回滚不再只在「需要它时」失败）；四个操作的索引读-改-写以 Mutex 串行化；补 `TrashServiceTest` 4 例（回收站首套回归：移入 / 回滚 / 还原重名 / purge）。 |
| 🟡2 | ✅ | `Logx.w` 补 `minLevel` 检查（四等级同语义）；PanelApp 过滤统一交给 `minLevel`（release = WARN+）——「只保留 WARN 以上」从注释变成行为。 |
| 🔵3 | ✅ | `openConnection`：connect 成功后才登记 `heldLeases` / `mounted`；失败释放租约并原样抛出（不再出现「从未连上却被持有」的会话）。 |
| 🔵4 | ✅ | RemoteScreen 状态从服务真实值初始化；`RemoteHttpServer` 暴露 `url` / `servedRoot`（页面显示「正在服务哪个目录」）；请求头行数上限 100。 |
| 🔵5 | ◐ | 压缩包挂载回收：**记录在案未动**——淘汰需「窗格引用」判断（跨层），留待专门批次；现状成本（句柄 + 内存）可接受。 |

> 验证：全仓单测 **430 用例全绿**（app 158 = 上批 154 + 本批新增 4；core 12 模块回归全绿）；`compileDebugKotlin` 随测试任务通过。
> 修复提交：`1a2e613`（9 文件，+274/−89）。

---

（后续批次在本文档追加 §9、§10 …）

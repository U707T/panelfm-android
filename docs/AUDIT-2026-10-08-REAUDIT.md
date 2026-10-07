# PanelFM 全量重审（2026-10-08 起 · 单一总文档）

> **本文件是本轮「全量重审」的唯一记录**：旧审查记录已于 2026-10-08 全部删除（保留 3 份设计说明：
> `EDITOR-ENGINE.md` / `OFFICE-PREVIEW.md` / `SELECTION-MODEL.md`），本文件按批次逐段追加。
>
> 基线：`main` @ `c69a4dd`（v1.10.1）。
> 方法：只读代码 + 全仓 grep 取证（所有结论给 文件:行号）；未跑真机。
> 修复：**第 1 批（app/ui/browser）已全量修复**（见 §1 末尾「修复记录」；本地 `compileDebugKotlin` +
> `testDebugUnitTest` + `assembleDebug` 全绿）；其余批次待审计后继续。

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

---

（后续批次在本文档追加 §3、§4 …）

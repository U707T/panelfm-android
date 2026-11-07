# 本轮审计：v2.0.19 稳定性与体验收口（2026-10-19）

> 范围：**不是**全项目重审，而是「用户体验 + 稳定性」这一条线上的定向排查
> （壳层 / 全局状态 / 列表交互 / 预览页）。
> 方法：只读代码取证（结论都给 文件:行 依据）+ 编译 / 单测 / lint / assembleDebug 全量验证。
> 所有 🔴 / 🟡 **已在本轮修复**（修复即附回归用例或说明验证方式）；🔵 记录在案未动。
> 既有模块结论见 `AUDIT-2026-10-08-REAUDIT.md`（全量重审）与
> `AUDIT-2026-10-11-CHANGESET.md`（编辑器变更审计）。

## §0 分诊表

| 模块 | 范围（路径 / 规模） | 等级 | 依据（证据） |
|---|---|---|---|
| **壳层 / 全局稳定性** | `AppContainer` · `PrefsStore` · `AppRoot` · `TransferService` | 🔴 | 109 处 `scope.launch` 零 `CoroutineExceptionHandler`；DataStore 无 corruption handler；状态提示宿主只存在于浏览页 |
| **ui/browser 列表交互** | `BrowserController` / `PaneView` / `BrowserMenuSheets` / `Thumbnails` | 🔴 | 剪贴板是普通 `@Volatile var`（读它不订阅）；过滤条件变化走 `load()` 重拉目录；行内各自订阅 settings；缩略图磁盘缓存无淘汰 |
| **ui/preview** | `PdfScreen` · `ApkInfoScreen` | 🟡 | `onDispose` 主线程 `runBlocking` 等渲染锁；物化缓存键用 32 位 `hashCode()` |
| **二级页面** | `BookmarksScreen` · `TrashScreen` · `ConnectionEditScreen` | 🟡 | 组合期同步查库 / 读盘（进页面白卡一帧） |
| **core/transfer · core/vfs-\*** | 传输引擎与各协议 | 🟢 | 本轮未发现新问题；既有租约 / 互斥 / 上限设计见 §5、§6 修复记录，保持不动 |

---

## §1 壳层 / 全局稳定性（🔴）

### 🔴 1. 应用级协程作用域没有异常兜底：一处漏 catch 就是闪退

- 位置：`AppContainer.kt:67`（`scope` 定义处）
- 问题：`CoroutineScope(SupervisorJob() + dispatchers.io)` 没有 `CoroutineExceptionHandler`。
  `SupervisorJob` 只保证「一个子作业失败不连坐兄弟」，**不阻止**未捕获异常上抛到线程默认处理器。
- 为什么：全仓 109 处 `scope.launch`，其中大量是读库 / 落盘 / 连服务器这类外部失败面。
  DataStore 写失败、SQLite 磁盘满、协程里抛非 `VfsException` —— 任何一处没写 try/catch
  就是整个 App 闪退，且用户侧看到的是「用着用着突然没了」。
- 修复（已做）：挂 `CoroutineExceptionHandler`，记一条 `Logx.e` 后继续运行。

### 🔴 2. DataStore 没有 corruption handler：设置文件写坏 = 所有设置永久失效且无提示

- 位置：`PrefsStore.kt:120`（`preferencesDataStore(name = "panel_prefs")`）
- 问题：`panel_prefs.preferences_pb` 损坏时抛 `CorruptionException`；无 handler 则该异常从
  `settings` Flow 抛出，三个收集方（`AppContainer`、`BrowserController`、各界面）静默结束。
- 为什么：表现是「设置全部不生效、永远默认值、浏览器启动不自动加载、记住路径失效」，
  没有任何错误提示，用户只能清数据。断电 / 磁盘满 / 杀进程恰好落在 rename 中间都会触发。
- 修复（已做）：`ReplaceFileCorruptionHandler { emptyPreferences() }`，损坏时换空文件继续可用。

### 🔴 3. 状态提示只存在于浏览页：其它页面的操作反馈用户看不到

- 位置：`BrowserDialogHost.kt:628`（旧 Snackbar 位置）→ 移到 `AppRoot.kt`（`StatusHost`）
- 问题：`showStatus(...)` 的消费端挂在 `BrowserDialogHost`，而它只在双列浏览页组合时存在。
- 为什么：回收站还原 / 彻底删除、文本对比「未找到任何差异」、编辑器保存结果、设置页提示
  **全都看不见**；消息积压在队列里，等回到浏览页才弹出来，早已对不上刚才的操作。
  浏览页被移出组合期间也没有任何消费者，队列会一直堆着。
- 修复（已做）：上移到 `AppRoot` 栈最外层；底距改为 `MtSpec.BottomBarHeight + safeDrawing.bottom`
  （旧实现写死 66dp，全面屏手势条会压住下半截）。

---

## §2 ui/browser 列表交互（🔴）

### 🔴 4. 剪贴板是普通可变字段：复制后「粘贴」FAB 不出现

- 位置：`BrowserController.kt:434-438`（旧 `val hasClipboard get() = clipboard.isNotEmpty()`）、
  `PaneView.kt:127`、`BrowserMenuSheets.kt:169`
- 问题：`clipboard` 是 `@Volatile var List<VfsUri>`，界面读它不产生订阅。
- 为什么：复制到剪贴板后 FAB 不出现、⋮ 菜单里也没有「粘贴 / 移动粘贴」，必须切目录触发
  别的重组才「突然冒出来」；移动粘贴清空剪贴板后 FAB 也不消失。用户观感 = 点了没反应。
- 修复（已做）：改为 `MutableStateFlow<List<VfsUri>>` + `setClipboard` / `clearClipboard`，
  两处消费点 `collectAsState`。

### 🟡 5. 改过滤 / 目录内搜索会重拉整个目录（远端 = 一次完整往返），且部分调用点连拉两遍

- 位置：`BrowserControllerSelect.kt:111`（`setSearch`）、`:127`（`setFilter`）、
  `BrowserDialogHost.kt:91`、`:124`、`BrowserMenuSheets.kt:154`
- 问题：过滤与目录内搜索都是**纯客户端**条件（只作用于 `FileMetadata.name` / 扩展名），
  旧实现却 `load(side)`；调用方提交后还紧跟一次 `refresh(focusSide)`。
- 为什么：慢网 / SMB / WebDAV 上每次改过滤条件白等一次目录往返，还连拉两遍；
  本地大目录也要重新枚举 + 排序。
- 修复（已做）：新增 `PaneState.unfilteredItems` 保存未过滤的完整列表，
  纯函数 `applyPaneFilter(source, search, filterKind)`（4 例回归，含「清空后必须回到完整列表」），
  `load()` 与 `setSearch/setFilter` 共用同一份规则；调用点的多余 `refresh()` 删除。

### 🟡 6. 子目录里过滤到空时没有任何解释

- 位置：`PaneView.kt:338`（空状态分支）
- 问题：`pane.items.isEmpty() && !canGoUp` 才显示空状态；子目录里 `canGoUp` 恒为真，
  于是列表只剩一行 `..`。
- 为什么：用户以为目录空了 / 加载失败，实际是过滤条件把内容挡住了，且没有可见出口。
- 修复（已做）：把「过滤后为空」提前到 `canGoUp` 判断之前，给「清除过滤」按钮；
  `EmptyState` 增加可选 action 参数（与 `ErrorState` 同形）。

### 🟡 7. 每一行各自订阅 settings：N 份订阅 + N 次重组

- 位置：`PaneView.kt:566`（`MtFileRow` 内 `collectAsState`）
- 问题：注释里写着「由 PaneView 顶层取一次、按参数下传即可」，但实现仍在行内订阅。
- 为什么：长目录滚动时每行一份 Flow 订阅；settings 任一字段变化（例如 `showSeconds`）
  会让所有可见行重组，即使该字段与行内容无关。
- 修复（已做）：`ThumbPolicy` 与 `listDisplayMode` 由窗格顶层 `remember` 一次后按值下传。

### 🟡 8. 缩略图磁盘缓存只增不减

- 位置：`Thumbnails.kt:32`（`init`）与 `:104`（写缓存后）
- 问题：`cache/thumbs/<sha1>.png` 没有容量上限，也没有淘汰；只有手动 `clear()` 才清。
- 为什么：重度使用几个月就是几百 MB 隐形占用，且系统只在「存储告急」时才动 cache 目录。
- 修复（已做）：启动 + 每写 64 张检查一次总量，超 128 MB 按「最久未修改」淘汰到 80%（IO 上执行）。

---

## §3 ui/preview（🟡）

### 🟡 9. PDF 预览返回时在主线程 `runBlocking` 等渲染锁

- 位置：`PdfScreen.kt:103`（旧 `DisposableEffect` 的 `onDispose`）
- 问题：`runBlocking { lock.withLock { close } }` 写在 `onDispose` 里 —— 组合销毁发生在主线程，
  等于**在主线程上等当前页渲染跑完**。
- 为什么：返回动画明显卡一下，大页 / 高 DPI 更久；渲染协程被取消时仍会把当前页渲染完才释放锁。
- 修复（已做）：改为后台协程 `scope.launch { lock.withLock { close } }`，返回不等它，
  渲染器仍在同一把锁内被关闭（`PdfRenderer` 非线程安全的前提保持不变）。

### 🟡 10. 物化缓存键用 32 位 `hashCode()`：不同文件撞哈希 = 打开别人的缓存

- 位置：`PdfScreen.kt:213`（`doc-${uri.hashCode()}-${size}.pdf`）、
  `ApkInfoScreen.kt:290`（`pkg-${uri.hashCode()}-${size}.apk`）
- 问题：`String.hashCode()` 只有 32 位，不同 URI 可能相同；缓存命中判断只看
  `exists() && length > 0`。
- 为什么：PDF 会**显示成另一个文档**，APK 会解析成**另一个包的信息**（甚至签名 / 版本全错），
  而且因为是缓存，重开仍然错。
- 修复（已做）：改 SHA-256 前 16 个 hex；顺带每次落盘清 3 天未碰的旧物化缓存
  （远程 PDF / APK 动辄几十上百 MB，只增不减）。

---

## §4 二级页面（🟡）

### 🟡 11. 组合期同步查库 / 读盘

- 位置：`ToolsScreens.kt:64`（`remember { trash.list() }`，读 JSON 索引）、
  `BookmarksScreen.kt:59`（`remember { browser.bookmarks() }`，查 SQLite）、
  `ConnectionEditScreen.kt:81`（`remember { connectionDao.all().firstOrNull { … } }`，**全表读再筛一条**）
- 问题：都在组合（主线程）里做 IO。
- 为什么：点进页面白卡一帧；库被别处写锁时更明显；连接编辑还顺带做一次口令解密。
- 修复（已做）：回收站 / 书签改 `produceState` + `Dispatchers.IO`；
  连接编辑改为「IO 读库 + 首帧 `LoadingState` 占位」，读完再组合表单
  （不能改成异步回填 —— 全部字段都用它做 `remember` 初值，回填会被初值锁死）。

---

## §5 🔵 记录在案（本轮未动）

- **`TransferService` 的 `scope` 也没有异常兜底**：它是独立 `CoroutineScope(SupervisorJob() + Main)`，
  收集 `taskEvents` 时若抛异常同样会打到线程默认处理器。改动面涉及前台服务生命周期，
  建议单独一轮处理（与 §1 的 handler 统一口径）。
- **`AppRoot` 的 `Screen.Preview` 栈元素不携带滚动 / 播放进度**：`configChanges` 已覆盖转屏，
  进程被杀后靠 `lastOpenedPreview` 回到文件开头。属于产品取舍，未动。
- **`PaneState.items` 与 `unfilteredItems` 是两份列表**：内存占用翻倍（同目录多一份引用，
  不复制 `FileMetadata` 实例）。上千项目录可接受；若日后要极致省内存，
  可改为「只存 `unfilteredItems` + 派生 `items`」，但要先解决 Compose 快照下的派生开销。
- **`Docs` 里 `AUDIT-2026-10-08` 的分诊表仍写 `BrowserController.kt` 2,289 行**：文件已按职责拆分，
  该表是历史快照，保留不动。

---

## §6 🟢 做得好的地方（本轮点名）

- **纯函数 + 回归先行的传统保持住了**：`statusDurationMs`、`closeTabNewActive`、`menuTargets`、
  `planCompressTargets`、`editorOpenPlan` 等关键规则都有对应测试；本轮新增的 `applyPaneFilter`
  也直接沿用了这个位置（`BrowserModels.kt` 的纯函数区），没有塞进控制器。
- **`BusyReporter` 的节流 + 协作式取消**设计干净（`ensureActive` 注入、假时钟可测），
  长操作状态条是「不可消失 + 明确取消」的正确形态。
- **`TrashService` 的原子写 + 失败回滚**写得扎实：`index.json.tmp` → `ATOMIC_MOVE`，
  落盘失败把已移动文件全部放回（含跨卷复制兜底）—— 这类「宁可不动也不能半途」的取舍是对的。
- **`SecretStore` 的失败语义**（`put` 返回布尔、`get` 绝不抛）避免了「口令丢了还提示保存成功」，
  这类「失败要能被上层看见」的约定值得在别处复用。
- **`SessionLocator` 的三级匹配 + `heldLeases` 租约**把「同主机多账号」「空闲回收」两个坑一次性处理掉了，
  且注释里写明了每条兜底的代价（历史缓存只作最后手段）。

---

## §7 第二批：预览 / 播放链路（2026-10-19 续）

> 范围：`ui/preview`（Office / Markdown / EPUB / 媒体播放）+ `core/common`（新增共享读取逻辑）。
> 分诊：🟡（本轮未发现崩溃级问题，但有**静默数据截断**与「播完不续播」两类体验缺陷）。

### 🔴 12. 预览读取上限「只比 size」：size 未知时静默截断文档

- 位置：`PreviewWeb.kt:275`（`readPreviewBytes`，Office / Markdown / CSV 共用）、
  `EpubScreen.kt:258`（`readArchiveEntry`）
- 问题：两处都是 `if (item.size > max) 拒绝`，然后「读满 max 就收手当读完」。
  `FileMetadata.size` 的默认值是 **-1（未知）**，压缩包 gz 条目更是显式 `size = -1L`；
  此时 `-1 > max` 恒为 false → 上限检查**完全失效**，读到 max 字节就当整份文件。
- 为什么：用户看到的是**被截断的文档**（docx 表格少一半、PPT 缺页、EPUB 章节后半段丢失），
  没有任何报错或提示 —— 与 v2.0.17 修掉的编辑器 🔴 是同一类「把截断当全文」。
- 修复（已做）：新增 `core/common/BoundedRead.kt`（`readBounded` + `definitelyExceeds`），
  三处调用点（编辑器 `readUpTo` / 预览 / EPUB）统一到一份实现；
  「多读 1 字节」区分「正好等于上限」与「超过上限」，超限明确报错而不是交半截内容。
  回归：`BoundedReadTest` 8 例（含碎块读、正好等于上限、读端恒返回 0 不死循环）。

### 🟡 13. 播放列表播完不自动接下一集

- 位置：`MediaScreen.kt:161`（`onPlaybackStateChanged`）
- 问题：只处理了时长 / 位置刷新，`Player.STATE_ENDED` 没有任何分支；自动续播只能手动点「下一集」。
- 为什么：看剧集 / 听专辑时一集放完停在最后一帧，用户以为播放器卡死（尤其熄屏时）。
- 修复（已做）：`STATE_ENDED` → `nextTrackAfterEnded(playlistSize, currentIndex, looping)`
  （纯函数 + 4 例回归）：列表末尾 / 单集 / 空列表 / 非法下标都停在原地，
  单曲循环交给播放器 `repeat`，不切歌。回调经 `mutableStateOf` 槽持有 ——
  `Player.Listener` 只注册一次，直接捕获 `playAt` 会把首次组合的 playlist 冻住。

### 🟢 做得好的地方（本批点名）

- **`VfsDataSource` 的事件成对性**写得很细：`started` 标志保证
  `transferStarted` / `transferEnded` 严格配对，`open` 失败时不留半截事件
  （否则 Media3 的 `TransferListener` 会统计出负数）。
- **`describePlaybackError`** 把 Media3 的英文技术错误逐条翻成可执行的中文动作，
  这是「错误文案可执行化」约定里做得最完整的一处。
- **`MediaScreen` 的意图令牌（`playToken`）**：连点「下一集」时先发后至的异步结果整体丢弃，
  同时回滚乐观高亮 —— 竞态处理有明确的模型而不是碰运气。
- **`PreviewWebView` 的 WebView 加固**：`allowFileAccess` / `allowContentAccess` /
  两条 `*FromFileURLs` 全部显式关掉，页面只走拦截器提供的同源 https，
  并在 `onRelease` 里 `destroy()`（WebView 泄漏是最常见的预览类内存问题）。

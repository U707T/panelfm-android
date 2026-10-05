# 更新日志（PanelFM）

> 版本号规则：`versionName` 带 `-rc` 后缀 → CI 自动发布为 **prerelease**。
> 发版三步：改 `versionName` → 本文件顶部加段落 → push main（CI 自动构建 + 建 Release）。
>
> ⚠️ **关于 v1.0.0–v1.0.2 段落中的「基准：MT 解析文档」**：那份仓库外文档已被废弃，
> 不再作为任何结论的依据（其中大量资源 ID 推断无法从本仓库复核）。这些段落保留为历史记录，
> 但**不要**再引用它们去论证「已对齐」。当前有效的差距与审计结论见 `docs/AUDIT-2026-10-05-CODE-TRUTH.md`。

## v1.2.2 — 播放器手势/进度条修复 + 浏览模式记忆

### 修复：进度条拖不动 / 拖到一半控件消失

**根因（两条同时存在）**：

1. **手势层与进度条抢事件**：覆盖全屏的播放器手势层（横向滑动 = 调进度）
   与底部的 `Slider` 争同一串移动事件。现在手势层会**避让控制层** ——
   触点落在顶栏 / 底部控制条范围内就完全不参与播放器手势（用实测高度判定）。
2. **自动隐藏会打断拖拽**：控制层 4 秒后无条件隐藏（`LaunchedEffect` 的 key 里
   没有「正在拖拽」），于是拖到一半滑条被整个移出组合 ——
   `onValueChangeFinished` 永不触发，`seekPreview` 留在半路，**seek 根本没执行**。
   现在拖拽期间禁止自动隐藏；即使被打断，隐藏时也会把未落定的进度落定并清理。
   另外拖动时**画面实时跟手**（不再等松手才跳），松手前显示预览时间。

### 修复：控制层消失后无法唤醒

- 触点落在控制层内时手势层**完全让位**，单击唤醒不再依赖「up 事件未被消费」这个
  脆弱前提（旧实现只要事件被任何子控件消费掉，唤醒就失效）；
- 锁定态下点击屏幕会**再次**给出「点左侧锁按钮解锁」提示（旧实现只在进入锁定时提示一次）。

### 修复：每次打开都是单列模式

**根因**：浏览模式默认值是 `AUTO`，而 `AUTO` 在手机（屏宽 < 600dp）展开成**单列** ——
与本应用「打开即双列」的定位相悖，表现为「每次打开都是单列」。

- 默认改为 **`DUAL`**；「自动切换」保留为显式选项（想按屏宽自适应的用户仍可选中）；
- 用户选过的模式会持久化并在启动时读回（v1.1.0 起已支持，本版修掉默认值这一环）；
- **老数据兼容**：v1.1.0 之前只有 `single_column` 布尔开关、没有 `browse_mode` 键，
  曾开启「默认单列显示」的用户按旧值继续单列，不会被新默认值静默改成双列。
  两个键此后保持同步。

### 测试

新增 `core:data` 首个测试模块（`BrowseModeDefaultTest`，2 项）。
全量：**10 个模块 251 用例全绿**；lint Error 0。

## v1.2.1 — 基础体验修复（像素级可用性）：深色主题 / 输入框 / 返回键

依据 `docs/AUDIT-UX-2026-10-05.md` 的批 U-A（P0 三条）。

### 修复：深色主题下文件列表几乎不可读

**现象**：切到深色主题，文件名 / 副标题 / `..` 行都是近黑色文字配 `#121212` 背景，对比度≈1:1。
浅色主题正常，所以一直没暴露。

**根因**：列表行文字写死了浅色主题的常量（`RowNameLight = 0xEE000000`、
`RowSubLight = 0x99000000`、选中勾 `AccentLight`），而深色专用的
`RowNameDark / RowSubDark / AccentDark` 虽然早就定义好，**全仓使用点 0**。

**修法**：
- 列表文字/选中态改用主题感知的 `MaterialTheme.colorScheme.onSurface / onSurfaceVariant / primary`
  （浅色方案本来就映射到同一组浅色常量，改完两个主题都正确）；
- 分段标题同理（`Components.kt`）；
- 新增 `LocalPanelDarkTheme`（由 `PanelTheme` 提供），替换全项目 **7 处直接调
  `isSystemInDarkTheme()`** 的地方 —— 那些地方在「应用内选浅色 / 深色」与系统设置不一致时
  会取错分支（顶栏底色、抽屉头部、行选中底色、对话框图标、分割线、编辑器配色）。

### 修复：输入对话框不自动聚焦、不弹键盘、回车不提交

重命名 / 新建文件夹 / 新建文件 / 跳转路径 / 过滤 / 压缩包内重命名 —— 这些是文件管理器最高频的操作，
旧实现每次都要「再点一下输入框才能打字、打完移手指去点确定」。

现在 `TextInputDialog`（覆盖上述全部入口）：
- 打开即自动聚焦并弹出键盘；
- **打开即全选**（重命名时直接输入即可覆盖原名）；
- 键盘右下角为「完成」，**回车直接提交**；
- 焦点请求延后一帧，避免对话框窗口尚未布局导致偶发失效。

### 修复：返回键退不出 App；「退出前双次确认」是死设置

**根因**：`DualPaneScreen` 注册了 `BackHandler(enabled = true)` 无条件拦截返回键，
且它的 `else` 分支跳主页。而它注册顺序晚于 `AppRoot` 的退出处理 ——
按 `OnBackPressedDispatcher`「后注册先收到」的规则，退出处理永远轮不到。
于是「浏览器 ←→ 主页」来回跳，退不出去；`confirmExit` 开关也从不起作用。
（顺带：那段「再按一次返回上级」是**恒 false 的不可达分支**，配套的 `upArmed` 是死状态。）

**修法**：
- 浏览器只在**真有内部目标**时拦截返回键（抽屉打开 / 加载中 / 多选 / 能返回上级）；
- 没有内部目标时把返回键让给 `AppRoot`：`confirmExit` 开 → 连按两次退出；
  关（默认）→ `moveTaskToBack`（与 MT 一致：退到桌面但**不销毁现场**，回来还在原目录）；
- 让「已在最上级 → 再按一次返回上级」这段真正可达。

### 测试

全量：**9 个模块 249 用例全绿**；lint **Error 0**。
（U1–U3 都是渲染/交互行为，单测覆盖不到，验收步骤见审计文档各条的「实机验证」。）

## v1.2.0 — 播放链路修复 + 图片缩放修复 + 按类型图标 + 点击路径去卡顿

### 修复：网络存储上的视频/音频「存储会话不可用」

**根因（两层）**：

1. **WebDAV 解析层把连接号丢了**。`DavXml` 用 `VfsUri.of(scheme, authority, path)` 构造条目 URI，
   **丢掉了 query**（其中 `?c=<connectionId>` 是会话定位与同主机多账号隔离的唯一依据）。
   于是 `stat` / `list` 出来的条目再也找不到原会话 —— 点开网络视频就报
   「存储会话不可用（可能已断开，请重新打开该存储）」。
   现在 `DavXml.entryUri()` 用 `requested.copy(path = ...)` 整体保留 query（含其它参数）；
   `WebDavVfs.mkdir` 里同样丢 query 的构造也一并修正。
   新增 `DavQueryPreserveTest`（4 项）锁死这条不变量。

2. **会话被回收后没有任何自愈**。`mounted` 表只是历史缓存（会话被空闲回收后不会清理），
   单靠它可能返回一个已死的实例；URI 丢过 `?c=` 时（历史书签、旧路径记录）又无法用连接号定位。
   现在补齐三层：
   - `SessionLocator` 增加「按 URI 反查连接（含 S3 的 bucket-authority 形态）→ 取活会话」兜底；
   - `AppContainer.resolveSession()`：找不到就**自动重连一次**，把「请用户手动重开存储」
     变成「应用自己解决」；
   - 播放预检、播放列表、`VfsDataSource.open()`、图片同目录列表、预览页、PDF / APK / 字体 / 编辑器
     全部改用 `resolveSession()`。

### 修复：图片无法放大

`ImagePage` 的手势 lambda 用 `pointerInput(bm)` 作 key，因此**只捕获首次组合时的 `scale`/`offset`**
（永远是 `1f` / `Zero`）：每个缩放事件都拿「1f × 本帧增量」→ 放大看不到、一松手就弹回 1x。
现在手势里读 `rememberUpdatedState` 的实时值，缩放正确累积（1x–5x），
并且：

- 未放大时**把单指拖动让给翻页**（旧实现里 `detectTransformGestures` 一越界就 consume，
  缩放和左右切图互相抢事件，两个都不好用）；只在「双指」或「已放大」时消费；
- 缩回适应窗口时平移自动归零，不会「缩小后画面偏在角落」。

### 新增：按文件类型给出不同图形

行内图标此前**所有文件共用同一个「折角文件」剪影**，只靠底色区分 —— 列表里一眼看不出
是图片还是视频。现在 `TypeGlyph` 为每种类型绘制不同剪影：
图片（相框+山+太阳）/ 视频（播放三角）/ 音频（均衡器四柱）/ 压缩包（箱体+拉链）/
APK（机器人头，眼睛用底色挖空）/ 字体（字母 A）/ PDF（折角页+内容线）/
代码（`< >`）/ 文本（三条横线）/ 未知（折角页）。全部用 Canvas 绘制，不引入图标依赖。
新增 `GlyphKindTest`（2 项）保证这些类型映射到互不相同的图形。

### 修复：点击文件发涩（主线程查库）

- `openItem`（**每次点击**都会走）此前同步查 SQLite（打开方式默认值）→ 改为后台查询；
- 「打开方式」对话框在**组合期**查库（每次重组一次）→ 改为 `produceState` 异步加载一次；
- 缩略图：`rememberThumb` 曾在**每一行**各订阅一次 settings 流 → 改由行内已有 settings 下传
  （`ThumbPolicy`），滚动期间不再产生 N 份订阅。

### 测试

新增 6 项（`DavQueryPreserveTest` 4 + `GlyphKindTest` 2）。全量：**9 个模块 249 用例全绿**。

### 仍未处理（见 `docs/AUDIT-2026-10-05-CODE-TRUTH.md` §6/§3）

死代码清理、传输断点写库节流、`RemoteHttpServer` 补测试。

## v1.1.1 — 修复「服务端快路径失败被误判为传输失败」+ 依赖加固

### 修复：服务端复制/移动返回 false 时整批失败

`VirtualFileSystem.serverSideCopy` 的接口注释写明「不支持返回 false（**引擎降级为流式泵**）」，
但 `TransferTask` 把 `false` 直接当成失败（`failed += FailedItem(src, "服务端操作失败")`）——
违反接口契约。`WebDavVfs.serverSideCopy()` 返回的是 `r.isSuccessful`，
所以「WebDAV 复制到已有同名目标的目录」这类场景会**整批失败而不是降级**。

现在：服务端操作返回 false → 保留已解析出的目标（避免 KEEP_BOTH 二次改名）→
重新规划 → 强制走流式慢路径；MOVE 的降级语义是「复制成功后删源」。
另外顺带补齐了快路径的冲突策略（目录覆盖目录按慢路径的「合并」语义，而不是先删整个目标）
和进度统计（服务端已完成的部分计入 `ok` / `doneBytes`，百分比不跳变）。

补丁来自先前会话遗留、从未并入 main 的一批未提交改动（清理临时 worktree 时抢救出来），
已用变异测试确认其新测试确实能捕获该缺陷（回退修复 → 2 项失败）。

### 依赖加固

- `commons-lang3` 3.16.0 → **3.21.0**：`commons-compress 1.27.1` 传递引入的 3.16.0
  在释出前有「长输入导致非受控递归」告警；这是**唯一随 APK 发布且当时仍有修复版本**
  的 Dependabot 告警（bcprov 此前已约束到 1.85，其余 netty/jose4j/jdom/bcpkix 只出现在
  构建工具或 lint 工具链里，不进 APK —— 已用 dex 扫描确认）。

### 测试

- `core:transfer` 20 项（+4）：服务端复制/移动返回 false 的降级、快路径 KEEP_BOTH/SKIP、
  目录覆盖走慢路径合并。
- 全量：**9 个模块 243 用例全绿**。

## v1.1.0 — 审计修复：加密压缩包闭环 + 失效设置收口 + 应用图标

> 依据 `docs/AUDIT-2026-10-05-CODE-TRUTH.md`（基线 `6ca18f4`）逐条修复。
> 该报告里 **C1/C2/C3 三条 Critical 与 R1–R4 四条 Required 全部在本版处理完毕**。

### 修复（Critical）：加密压缩包「能建不能读」

旧版本存在三条互相独立的硬伤，且都被同一类缺口掩盖 —— **没有任何测试验证过
「PanelFM 自己能不能读回自己加密的包」**：

- **ZIP 带口令压缩 100% 失败且写出 0 字节**：`addRawArchiveEntry` 见到 entry 上的
  encryption 标志就抛 `UnsupportedZipFeatureException`。
  现改为整包交给仓库里**本来就有、却从未接线**的 `EncryptedZipWriter`（自研流式写侧）。
- **加密 7z 能创建、读不回来**：`SevenZFile` 从未传口令 → `PasswordRequiredException`。
  现 `ArchiveVfs` 支持口令，7z 挂载时透传。
- **加密 ZIP 读不回来**：commons-compress 1.27.1 **根本没有**加密 ZIP 读实现
  （`ZipFile` 无 password 参数，只有一个 `PasswordRequiredException` 异常类型）。
  新增 `ZipCryptoStream.kt` 自研解密（12 字节加密头 + 连续密钥流），
  并按 APPNOTE 用 **DOS 时间高字节**（`ZipUtil.toDosTime`，不是 epoch 右移）校验口令。
- 加密 ZIP 条目读完即比对 CRC：ZipCrypto 只用 1 个字节判口令，256 次里有 1 次会放过错误口令，
  CRC 是唯一能真正判定内容对错的手段。
- 加密包**不允许**应用内增删改名（整包重写会破坏加密），给出可执行中文文案而不是底层英文异常。
- `EncryptedZipWriter` 补齐压缩级别与「仅存储」支持（此前加密路径忽略用户在压缩对话框里的选择）。

### 修复（Required）：四个「设置项不生效」

- **`trustSelfSigned` 是死开关**：设置页能开、协议层硬编码 `false`。现经 `VfsEnv` 桥接为
  全局兜底默认值（连接级设置优先），改完即时生效。
- **信任开关对 S3 静默无效**：`S3Client` 此前完全没配 TLS。现已接入；
  同时把「信任自签证书」开关**按协议显示** —— SFTP / SMB 并不读取该选项，
  继续显示只会让用户以为自己放开了校验。
- **任务并发重启失效**：设置页只在点按钮那一刻生效，冷启动回到默认 2。现启动时读回。
- **浏览模式（单列/双列/自动）不持久化**：「自动切换」永远留不住。现落盘并在启动读回。

### 修复（Required）：同主机多账号会话隔离

`?c=<connectionId>` 此前只有两处**手工拼串**产生，书签 / 最近路径 / 同步路径 / 返回上级
全都漏掉 → 同主机不同账号会被 `VfsUri.sameMount` 判成同一挂载点，
`isInside` 误报「目标在源内部」直接拒绝操作。现统一由 `AppContainer.uriForConnection()`
与 `BrowserController.open()` 注入，所有入口一致。

### 其他

- `VfsException.Auth.userMessage` 改为优先使用具体原因（固定文案「请检查用户名/密码」
  会把「压缩包口令不对」「主机密钥变化」的用户引向错误方向）。
- 「信任自签证书」实现从 WebDAV / FTP 各一份收敛为 `core:vfs-api/TlsTrust` 单一实现。
- 应用图标改为 PNG 多密度资源（mdpi–xxxhdpi），移除自适应图标 XML。

### 测试

- 新增 `EncryptedArchiveRoundTripTest`（9 项）：**压缩(带口令) → 重新挂载 → 列目录 → 读内容**
  的端到端闭环，覆盖 ZIP/7z、STORED/DEFLATE、嵌套目录、二进制一致、随机读、错误口令文案、
  加密包拒绝改写。这组用例就是上述三条 Critical 的回归防线。
- 新增 `ConnectionIsolationTest`（7 项）：锁死 `?c=` 补全前后的 `sameMount` 行为。
- 全量：**9 个模块 232 用例全绿**（此前 223）。

### 已知未做（不是本版范围）

`docs/AUDIT-2026-10-05-CODE-TRUTH.md` §6 的死代码清理、§3 的性能项，
以及 `.preserved/unmerged-batch2-fastpath-fallback.patch`（服务端快路径失败降级，
违反 `VirtualFileSystem.serverSideCopy` 的接口契约）仍在待办。

## v1.0.6 — MT 对齐全面复核 + 传输安全加固

- 传输引擎补齐目录冲突策略：`跳过 / 覆盖 / 保留两者` 对整棵子树生效，不再出现目录已选「保留两者」但子文件写入旧目录的问题。
- 覆盖前删除改为失败即停，并在删除后再次确认目标确实消失；修复自身复制误删源文件的边界。
- `VerifyMode.SIZE/HASH` 从模型字段接入实际执行；HASH 使用 SHA-256 流式校验。
- 修复 `openWrite` 失败时源 reader 泄漏；补充删除失败、目录冲突、校验、会话隔离回归测试。
- 修复压缩包路径穿越、ZIP 内部重命名路径校验、远程分享子目录越权、同主机不同账号会话复用，以及 Media3 失败路径的传输事件配对问题。
- ⚠️ 本段当时宣称的「对齐差距见 `docs/MT-ALIGNMENT-CODE-REVIEW.md`」**已失效**：该文档已删除。
  实际差距（含压缩包加密读写不闭环等 Critical 项）见 `docs/AUDIT-2026-10-05-CODE-TRUTH.md`。

## v1.0.5 — 修「左滑出现怪页面」+ 视频播放链路彻底修好 + code review

### 修复：左滑进入多选后出现「黑屏怪页面」（v1.0.3 引入的回归）

**根因**：`MtActionButton` 用了 `Modifier.fillMaxHeight()`，而 v1.0.3 把顶栏合并成
「一行 Row」后这一行是 **wrap_content**（没有高度约束）。
一进多选（动作条出现）`fillMaxHeight` 就拿到「整屏剩余高度」→ 顶栏被撑满全屏，
只剩 ☰ + 路径 + 复制/剪切 + ⋮ 孤零零地排在中间（就是截图里那个页面）。

**修法**：
1. `MtActionButton` 改成**固定高度** `MtSpec.TopBarHeight`（MT 的顶栏就是固定 56dp，
   动作项 `layout_height=-1` = match 该行）；
2. 顶栏那行 Row 也显式 `.height(MtSpec.TopBarHeight)` —— 给整行**有界高度**，
   行内任何 `fillMaxHeight` 子项都只会填满这一行，不会再撑到整屏（防御同类回归）；
3. 全项目复查了其余 4 处 `fillMaxHeight`，父容器都有高度约束（Row / 固定高度底栏），无同类问题。

### 修复：视频仍然无法播放（v1.0.4 修得不彻底）

v1.0.4 只修了「后缀判型」，但还有两个致命问题：

1. **`VfsDataSourceFactory` 是唯一的数据源，却不认识标准 scheme**。
   `DefaultMediaSourceFactory(DataSource.Factory)` 会把唯一这个工厂用于**所有**请求；
   而 v1.0.5 之前本地文件走 `file://`（`Uri.fromFile`）时，
   `VfsDataSource.open()` 会抛「非法媒体地址」→ 看起来就是「视频无法播放」。
   **修法**：`VfsDataSourceFactory` 现在同时持有 Media3 的 `DefaultDataSource.Factory`，
   `VfsDataSource.open()` 遇到非 `panelfm://` 的请求就**委托**给它
   （file / content / http(s) / data … 全覆盖），`read`/`close` 也跟着走委托分支。
2. **本地文件多绕了一层**。现在 `mediaItemFor(uri, localPath)`：本地文件直接 `Uri.fromFile`
   交给 Media3 自带的 `FileDataSource`，完全绕开「自定义 scheme + VfsDataSource + runBlocking」
   整条链路；只有网络 / 压缩包内才走 `panelfm://`。

**另外新增**：
- **播放前预检**（`preflight`）：prepare 之前先 stat + 读 1 字节，
  把「文件不存在 / 是文件夹 / 0 字节 / 无权限 / 会话断开」直接变成可执行文案，
  不再让用户对着黑屏猜；
- **`describePlaybackError` 接受任意 Throwable**（prepare 抛的不一定是 `PlaybackException`），
  非 Media3 异常也给出可读提示。

### 新增测试

`MediaUriTest` 增加「标准 scheme 应交给 Media3 自带数据源」的断言
（`file://` / `content://` / `http(s)://` 都不能被 `panelfm://` 的解析器认领）。

## v1.0.4 — 修复视频无法播放 + code review 修复

### 修复：视频无法播放（关键）

**根因**：播放地址被构造成 `panelfm://vfs?u=<编码后的 VFS URI>`（**path 为空**）。
Media3 用 `Uri.getLastPathSegment()` 的后缀推断容器类型（`Util.inferContentType`）：

- `getLastPathSegment()` 拿到的是 authority `vfs`（没有点号）→ 类型恒为 `CONTENT_TYPE_OTHER`；
- 同时 `MediaItem.fromUri` 的 mimeType 为 `null`；
- → `DefaultMediaSourceFactory` 选不到合适的 Extractor → **黑屏 / 播放失败**。

**修法**：
1. 播放地址改成 `panelfm://vfs/<真实文件名>?u=<完整 VFS URI>` —— path 末尾保留文件名与扩展名，
   `inferContentType` 能按 `.mp4` / `.mkv` / `.m3u8` 正确判型；
2. 新增 `mediaItemFor()`：额外用 `setMimeType` 显式给出容器类型（`mimeTypeForName`，含 mp4 家族
   区分 `video/mp4` 与 `audio/mp4`）；
3. 编解码规则抽成纯函数 `mediaUriString()` / `vfsUriFromMediaUri()`（读侧不再手动 `URLDecoder`
   —— 原来会**双重解码**，把文件名里的 `%2B` 之类解错）；空格编成 `%20` 而不是 `+`
   （`URLDecoder` 会把 `+` 当空格，Android 的 `Uri` 不会）；
4. `VfsDataSource` 的 `isNetwork` 从 `false` 改成 `true`（同一套数据源既要读本地也要读网络，
   标成网络让 Media3 用更宽容的超时与重试策略）。

### 修复：code review 发现的问题

- **Media3 事件不成对**（`VfsDataSource`）：`open()` 里先 `transferInitializing` 再 `openRead`，
  若 `openRead` 抛异常（文件被删 / 权限不足 / 会话断开），`transferStarted` 不会执行，
  但 `close()` 仍会 `transferEnded` → `TransferListener` 记出负数。
  现在用 `started` 标志保证严格成对。
- **图片预览的 InputStream 缓冲区竞争**（`PreviewScreen`）：`skip()` 复用了 `read()` 的共享
  `buf`，并发/嵌套调用会读到脏数据（图片偶发解码失败）。
  现在 `skip()` 用独立缓冲区，并在 VFS 支持随机访问时直接 `seek`（不浪费带宽）。
- **`available()` 恒返回 0**：部分解码器用它估算缓冲，恒 0 会让它们退化。
  现在返回「剩余可读字节数」。
- **播放失败无出口**：原来只有一行错误文本。现在按 `PlaybackException.errorCode` 翻译成
  **可执行的中文提示**（文件不存在 / 没权限 / 网络超时 / 解码器不支持 …，附原始原因），
  并提供「重试」与「用其他应用打开」（本地文件）两个按钮。

### 新增

- `MediaUriTest`（20 项）：锁死播放地址的 path 必须带真实文件名、编解码成对、
  mimeType 映射、以及端到端往返（含中文 / 空格 / 百分号 / 加号 / 多段扩展名）。

## v1.0.3 — 顶栏合并为 MT 的单块结构

**基准**：MT 原版截图 + `0x7f0c0033` 的 `09046B` / `09038A` 结构

### 修复

- **顶栏合并成一块**（关键差异）：MT 的顶栏是 `09046B` 里的**一个自定义 View（`09038A`）**，
  自己画「☰ + 路径（居中大字）+ 统计（居中小字）+ ⋮」——**全在同一行、垂直居中**，
  不是「工具行 + 标题行」上下两层。之前把 ☰/⋮ 放在第一行、路径放第二行，与 MT 截图明显不同。
- ☰ 与 ⋮ 图标 26dp（与 MT 的视觉重量一致），路径 18sp / 统计 13sp 居中
- 动作条与 TabLayout 挪进同一行右侧（多选 / 多标签时才占位），不再各占一行
- `MtSpec.TopBarHeight` 注释补上「顶栏是一块，不是两层」的说明

## v1.0.2 — 按截图重新复刻 MT 原版 UI

**基准**：`/workspace/mt-analysis/MT-UI-功能-逻辑-解析.md` + 用户提供的 MT 原版截图逐帧比对

### 修复（与 MT 截图对比后发现的差异）

- **列表副标题**：MT 显示的是**时间**（`26-10-04 13:16`）而不是权限位 →
  默认档从「权限+大小」改成「时间+大小」，时间格式改为 `yy-MM-dd HH:mm`（与 MT 截图一致）
- **文件大小格式**：MT 是**单字母单位 + 两位小数**（`384.95G` / `94.80G`），
  旧实现是 `384 GB` / `94.6 GB`（双字母 + 一位小数）
- **侧拉栏「已用/可用」**：改成 MT 原文 `%1$s已用 , %2$s可用`（`0x7f110206`，注意空格与逗号位置）
- **侧拉栏分段标题**：MT 是**中灰**（不是纯黑）+ **矢量折叠箭头**（原为「︿ / ﹀」文字符号）
- **侧拉栏三段可折叠**：本地 / 网络 / 后台 / 工具都支持点击标题折叠（MT 截图每段右侧都有箭头）
- **顶栏去掉面包屑**：MT 的顶栏只有「☰ + 路径（居中，完整不省略）+ 统计 + ⋮」，
  没有二级面包屑行；统计里的储存改成紧凑单位（`384.95G/479.51G`）
- **顶栏 TabLayout 与 ＋ 只在多标签时出现**：MT 的文件浏览态（单标签）顶栏没有标签页与 ＋
- **底栏「同步」图标**：MT 用的是 **swap_horiz（⇄）**（两窗格同步语义），不是刷新箭头
- **⋮ 菜单顺序逐条对照 MT 截图**：刷新 / 搜索 / 全选 / 过滤 / 排序方式 / 隐藏文件 ▶ /
  添加书签 / 设为首页 / 交换窗口 / 设置 / 退出（扩展项插在同语义位置）
- **修 BUG**：⋮ 菜单「类型过滤null」（`?.let{} ?: ""` 的优先级问题导致 null 被拼进文案）
- **行内图标改回「深底 + 白剪影」**：文件夹 = 近黑方块 + 白剪影；文件 = 类型色方块 + 白剪影
  （中途有一版误判成「浅底 + 深剪影」，与 MT 截图明显不符）
- **连接表单改用 MT 的 Material 填充式输入框**（`MtTextField`）：label 在上、placeholder 在框内、
  **只有下划线**（不是 `OutlinedTextField` 的四边框）；字段与 MT 一致（URL / 用户名 / 密码 👁 /
  自定义 UA / 初始路径 / 备注 / 网络分组），去掉 MT 没有的推广文案
- **协议标签**：MT 用无边框文字标签、选中项主题蓝、`对象存储(S3)` 带括号写法
- **连接页标题**：`← 返回` 用矢量箭头 + 标题 20sp（MT 截图样式）
- **分段标题留白**收紧（上 14dp / 下 6dp）、占用条改直角细线 + 6dp 间距

### 新增

- `core/ui/MtWidgets.kt`：`MtTextField`（MT 的 TextInputLayout 复刻）
- `core/ui/MtIcons.kt`：补 `UNFOLD_UP` / `UNFOLD_DOWN`（MT `0x7f0800b3` / `0x7f0800a4`）

## v1.0.1 — MT 交互 / 手势全量对齐 + 列表位置记忆

**基准**：`/workspace/mt-analysis/MT-UI-功能-逻辑-解析.md`（正文 §0–§8 + 附录 A–G）

### 新增

- **进子文件夹再返回上级，列表停在原地**（复刻 MT 手感，不再跳回顶部重新加载）
  - 新 `core/common/ScrollMemory.kt`：每个目录各自记住滚动位置（含 `..` 行的列表下标 + 像素偏移），
    LRU 上限 64 个目录；纯逻辑 + 8 项单测
  - `PaneState.loadedUri`：区分「这批 items 属于哪个目录」——切目录时 `uri` 先变、`items` 后到，
    用 `uri` 记账会把旧目录的位置写到新目录头上
  - 恢复与记录**串行**（先 `scrollToItem` 恢复、再 `snapshotFlow` 持续写回），
    否则记录会先用顶部的 (0,0) 把记忆擦掉
  - 无记忆时**显式回顶部**：`LazyListState` 跨目录复用，不显式归零会沿用上一个目录的下标
  - 覆盖全部导航路径：点击文件夹 / 点 `..` / 底栏 ← → ↑ / 返回键 / ⋮ 跳转 / 交换窗口
    （控制器侧 `registerScrollSaver` + `flushScroll` 兜底）
  - 打开预览或编辑器再回来也保持位置（`DisposableEffect.onDispose` 落盘）
- **真实矢量图标库（94 个）**：从 APK 反解 `pathData` 进代码，支持 MT 混用的
  24/32/48/100/108/144/200/450/512/1024 多种 viewport 与 `fillAlpha` 挖空
- **全部 emoji / 文字符号清除**（⋮ 菜单、底栏、动作菜单、侧边栏、主页、打开方式、
  密码可见性、编辑器分页、差异导航），共 27 个文件
- **顶栏重做**（`0x7f0c0034`）：☰ + TabLayout（横向滚动，单标签也显示）+ 动作条（横向可滚动）
  + ⋮ + ＋；NORMAL 态动作条**整行** GONE
- **新 `core/ui/MtWidgets.kt`**：`MtDialog` / `MtFab` / `PaneEdgeShadow` / `MtMenuRow` /
  `MtActionButton` / `MtBottomIconButton` / `MtInfoRow` / `MtSectionDivider` / `DividerPx` / `VDividerPx`
- **`MtGesture`**（附录 G.5 阈值集中 + 4 项单测）：长按 400ms / 容差 12dp /
  滑动选择 24dp(\|dx\|>2\|dy\|) / 右滑出菜单 48dp / 上滑书签 32dp / 再按一次 2000ms
- **「点击连选」开关**（`0x7f110630`）
- **「再按一次断开连接」**（`0x7f1106fa`，2 秒窗口）
- **书签长按拖动排序**（`0x7f110140`）+ DB v2→v3 加法迁移
- **`MtListSubtitle`**（`0x7f110200/201/202` 三档，7 项单测）——原 `listDisplayMode` 是死开关

### 修复

- **右滑出菜单与「滑动进多选」抢事件**：改为**仅已多选态生效**（文档 F.5 冲突消解顺序第 5 条），
  阈值从 120dp 收到文档的 48dp
- **长按触发时间** 从系统默认 500ms 改为 MT 的 400ms；位移容差改用文档的 12dp
- **分割线**：MT 是 `dividerHeight=1px`（**物理像素**），原先 1dp 在 3x 屏上是 3px、明显偏粗
- **列表副标题三档**此前无任何消费点（设置项是死的），现已接到列表行
- **`scrollIndex` 死字段**移除，替换为真正生效的滚动位置记忆
- 「再按一次退出程序」文案对齐 MT 原文（`0x7f110588`）

### 观感自查（附录 F.7 的 15 条）

逐条已核。

### 明确不做

Dex / Arsc / AXML 编辑、签名、加固、插件、账号 / VIP、云备份、Root / Shizuku / 注入文件提供器。
**回收站保留**（MT 没有，属 PanelFM 自加能力）。

---

## v1.0.0 — MT 2.14.5 解析文档全量对齐收尾

把文档 §7 差距清单里剩余的全部 P1 / P2 项做完（P0 十项在此前的批次 1–5 已完成）：

- 对比器：忽略四档（不忽略 / 忽略首尾空格 / 忽略全部空格 / 忽略空格和空行）+ 区分大小写；
  浏览模式三档（自动切换 / 双列 / 单列）；上一个 / 下一个差异跳转
- 编辑器：行操作全集（复制行 / 剪切行 / 删除行 / 清空行 / 重复行 / 转大小写 / 增删缩进 / 切换注释）、
  压缩代码、格式化代码（JSON / XML）、转到指定行；查找条改一行式
- 输入框历史（`app:recordKey`）：过滤词、批量重命名三处、编辑器查找 / 替换
- 搜索：条数上限提示（「已搜索到 N 个结果，你确定继续搜索？」）+ 停止搜索 + 在当前结果中二次搜索
- Hex 数值解释面板（大端模式 + 逐类型解释）
- 「再按一次」防误触范式；「选择当前目录」模式；对话框图标三模式
- 明确不做：逆向类功能（Dex / Arsc / AXML 编辑、签名、加固、插件、账号 / VIP）

## v0.13.0-rc.11 及更早

见 git 历史（`git log --oneline`）。

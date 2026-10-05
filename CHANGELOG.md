# 更新日志（PanelFM）

> 版本号规则：`versionName` 带 `-rc` 后缀 → CI 自动发布为 **prerelease**。
> 发版三步：改 `versionName` → 本文件顶部加段落 → push main（CI 自动构建 + 建 Release）。

## v1.0.6 — MT 对齐全面复核 + 传输安全加固

- 传输引擎补齐目录冲突策略：`跳过 / 覆盖 / 保留两者` 对整棵子树生效，不再出现目录已选「保留两者」但子文件写入旧目录的问题。
- 覆盖前删除改为失败即停，并在删除后再次确认目标确实消失；修复自身复制误删源文件的边界。
- `VerifyMode.SIZE/HASH` 从模型字段接入实际执行；HASH 使用 SHA-256 流式校验。
- 修复 `openWrite` 失败时源 reader 泄漏；补充删除失败、目录冲突、校验、会话隔离回归测试。
- 修复压缩包路径穿越、ZIP 内部重命名路径校验、远程分享子目录越权、同主机不同账号会话复用，以及 Media3 失败路径的传输事件配对问题。
- 对齐差距与未完成项见 `docs/MT-ALIGNMENT-CODE-REVIEW.md`。


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

# PanelFM 路线图（2026-11-08）

> 本文件只记录**本轮确认要做的范围与顺序**。没进表的项在 [§7](#7-本轮明确不做留档) 留档，
> 避免以后重复讨论；要重启某一项，先在 §7 写清触发条件。
>
> 排序用的四把尺子：
> 1. 每天碰到的 > 偶尔用的；
> 2. 引擎已实现只差接线的 > 从零写的；
> 3. 纯逻辑可单测的 > 必须真机回归的（沙箱无 `/dev/kvm`、`adb` 起不来）；
> 4. 数据安全 > 观感。

---

## 0. 本轮范围（合并为一次发布）

原计划分四批，按「东西不多、一次做完」的要求合并为**一版**（v2.4.0）。
批次四的媒体 / PDF / EPUB 与 DocumentsProvider 体量单独计算，放在主线之后。

| 状态 | 项 | 落点 | 量级 |
|---|---|---|---|
| ✅ | 自然排序（数字段按数值） | `core/common/NaturalOrder.kt`、`FileListing.kt` | S |
| ✅ | 多选属性 / 总大小 | `DialogsProperties.kt`、`BrowserModels.kt` | S |
| ✅ | 传输前剩余空间预检 | `BrowserControllerTransfers.kt`、`BrowserDialogHost.kt` | S |
| ✅ | `VerifyMode` 三档接线 | `PrefsStore.kt`、`SettingsScreen.kt`、`transferRequest()` | S |
| ✅ | 失败项明细 + 重试 | `TransferTask.kt`、`TaskDetail.kt`、`TasksScreen.kt` | S |
| ✅ | 回收站保留策略（天数 / 容量 + 启动清理） | `TrashService.kt`、`AppContainer.kt` | S |
| ✅ | 换行符 LF / CRLF 转换 | `EditorMenuModel.kt`、`EditorScreen.kt` | S |
| ✅ | 快速滚动条 | `PaneView.kt`（`FastScroller`） | S |
| ✅ | split APK 导出（`.apks`） | `ToolsScreens.kt` | M |
| ✅ | `.apks` 安装（PackageInstaller） | `BrowserFileActions.kt`、`TypeActions.kt`、Manifest | M |
| ✅ | APKS/XAPK/APKM 转 APK（合并 + 内置测试证书重签） | `core/apk/`、`DialogsApkConvert.kt` | L |
| ✅ | APK 信息补全（ABI / 安装位置 / 可调试） | `ApkInfoScreen.kt` | S |
| ~~✅~~ | ~~目录大小（懒计算 + LRU + 上限）~~ **v2.4.2 已废止** | ~~`ui/browser/DirSize.kt`、`PaneView.kt`~~ | M |
| ⬜ | 媒体字幕 / PiP | `MediaScreen.kt` | M |
| ⬜ | PDF / EPUB 纵深 | `PdfScreen.kt`、`EpubScreen.kt` | M |
| ⬜ | DocumentsProvider | 新增 `provider/` | L |

**验证**：`testDebugUnitTest` 全模块通过（新增 `NaturalOrderTest` 7 例、`FileListingTest` 2 例），
`:app:compileDebugKotlin` 通过。真机清单见 [§6](#6-真机验证清单)。

---

## 1. 批次一 · v2.4.0「手感与可靠性」

| # | 项 | 落点 | 量级 |
|---|---|---|---|
| 1.1 | 多选属性 / 总大小 | `DialogsProperties.kt`、`BrowserMenuSheets.kt` | S |
| 1.2 | 复制前剩余空间预检 | `core/transfer`、`BrowserControllerTransfers.kt` | S |
| 1.3 | 快速滚动条 | `PaneView.kt`（新增 `FastScroller`） | S |
| 1.4 | 自然排序 | `core/common`（新增 `NaturalOrder`） | S |
| 1.5 | `VerifyMode` 三档接线 | `Prefs`、`SettingsScreen`、传输请求构造点 | S |
| 1.6 | 失败项重试 | `TransferTask.kt`、`TransferModels.kt`、`TaskDetail.kt` | S |
| 1.7 | 回收站保留策略 | `TrashService.kt`、`Prefs`、`AppContainer` | S |
| 1.8 | 换行符 CRLF/LF/CR 转换 | `EditorMenuModel.kt`、`EditorScreen.kt`、`EditorFileIo.kt` | S |

### 1.1 多选属性 / 总大小

**现状**：`BrowserMenuSheets.kt` 的 `properties` 动作 `enabled = multi <= 1`；
`DialogsProperties.kt` 只接受单个 `FileMetadata`。选 20 个文件想知道总共多大，只能一个个点。

**做法**
- `DialogsProperties` 增加 `items: List<FileMetadata>` 重载；单选走原路径，不改观感。
- 多选展示：`N 项（文件 a / 文件夹 b）`、`总大小 X`、`未知大小 c 项`。
  总大小只累加 `size >= 0` 的文件；**目录不递归**（不触发网络遍历），只计入文件夹数。
- 「复制路径」支持多行（每项一行）。

**验收**
- 选 20 项混合 → 总大小 = 已知文件之和；文件夹数正确。
- 网络目录不产生额外 `stat` 请求（只用列表里已有的 metadata）。
- 单选属性面板与此前完全一致。

### 1.2 复制前剩余空间预检

**现状**：`VirtualFileSystem.space(uri): SpaceInfo?` 已存在（`LocalVfs` 用 `StatFs`，
其余协议默认 `null`），但 `TransferRequest` 从不预检。

**做法**
- 计划生成后拿 `OperationPlan.totalBytes`，若 `destVfs.space(destDir) != null && free > 0`：
  - `totalBytes > free` → 弹「空间不足：需要 X / 可用 Y」，默认「取消」，另给「仍然继续」；
  - `totalBytes > free * 0.9` → 只出状态条提示，不拦。
- `space()` 返回 `null`（SFTP / WebDAV / S3 / 压缩包）→ 静默跳过，**不误报**。
- `SERVER_MOVE` 快路径不占额外空间 → 跳过预检。

**验收**
- 本地目标剩余 100 MB、复制 500 MB → 弹窗且默认取消。
- 目标是 SFTP/S3 → 不弹任何窗。
- 同卷 move → 不弹。

**风险**：`StatFs` 对 SAF/分区根的「可用空间」含义不同（可能报整卷）——文案写「目标所在卷」。

### 1.3 快速滚动条

**现状**：`PaneView.kt` 是纯 `LazyColumn`，长目录只能靠甩。

**做法**
- 右侧 14dp 触摸带 + 自绘 thumb；thumb 高 = 可视比例（最小 32dp）。
- 位置映射用 `listState.layoutInfo`：`firstVisibleItemIndex / (total - visible)`；
  拖动时 `scrollToItem(index)`（不带动画），松手 1.5s 后淡出。
- 索引要跳过首行 `..` 与加载/错误行（用 `itemIndexOffset`），否则拖到底会差一行。
- 手势优先级：thumb 命中区内消费事件，不触发「左右滑动进入多选」。

**验收**
- 2000 项目录拖动跟手、不跳变。
- 与滑动多选 / 长按 400ms 菜单不冲突。
- 项目少于一屏时自动隐藏。

**风险**：手势冲突只能真机确认（见 §6）。

### 1.4 自然排序

**现状**：搜索结果用 `lowerName` 排序；文件列表比较器需先定位到 `load()` 链里的
`sortedWith`（本文件不假设它已做自然序）。

**做法**
- 新增 `core/common/NaturalOrder.kt`：`compare(a, b)` 按「数字段 / 文本段」交替比较；
  数字段按 `Long` 比（前导零不改数值序，相等时短者在前）；文本段用 `Collator`（`Locale.CHINA`）。
- `SortBy.NAME` / `SortBy.TYPE` 走它；`SIZE` / `TIME` 不动。
- 比较器实例缓存（`Collator` 非线程安全，按线程或加锁）。

**验收**
- `1, 2, 10` 顺序正确；`a1 < a02`；中文与英文混排不崩。
- 10,000 项排序 < 100 ms；排序结果稳定（相同 key 保持原序）。

### 1.5 `VerifyMode` 三档接线

**现状**：`VerifyMode { NONE, SIZE, HASH }` 定义在 `core/model/Ops.kt`；
`TransferTask.verifyFile()` 已实现「大小 + 双向 SHA-256」；但**所有 `TransferRequest(...)`
都没传 `verify`，永远是 `NONE`**。

**做法**
- Prefs 增加 `verifyMode`，默认 `SIZE`（不是 HASH：远端会把源和目标各完整读一遍）。
- 所有请求构造点统一走 helper（`TransferRequest.fromSettings(...)` 或集中补参数），
  避免以后再漏一处。
- 设置页「传输」分组三档；HASH 选项旁注明「源与目标各读一遍，大文件/远端耗时翻倍」。
- 任务详情增加「校验」行：`已校验（SHA-256）` / `仅大小` / `未校验`。

**验收**
- 三档均生效；HASH 不一致 → 任务失败且原因可见（引擎已有该分支）。
- UI 层映射有单测（引擎侧已有覆盖）。

### 1.6 失败项重试

**现状**：`TransferTask` 内部有 `failed: MutableList<FailedItem>`，但 `Done` 只暴露计数
（`failed = failed.size`），用户不知道失败的是哪几个、为什么。

**做法**
- `TaskState.Done` 增加 `failures: List<FailedItem>`（截断 200 条）+ `failureTotal`，
  防止大任务把快照撑爆。
- `TaskDetail` 列出「名称 + 原因」，可展开。
- 「重试失败项」：COPY 直接按原 sources 重投；**MOVE 要区分两种失败**——
  「未开始」可重投，「已复制但删源失败」只能重试删除，不能重跑复制（否则二次覆盖）。
- 重试前对源 `stat`，失效项标「源已不可用」，不整批失败。

**验收**
- 10 项里失败 3 项 → 详情能看到 3 条原因；重试只跑这 3 项。
- MOVE 的「已复制但删源失败」不会重复复制。
- 源被删/会话关闭 → 明确提示，不崩。

### 1.7 回收站保留策略

**现状**：`TrashService` 只有手动 `purge` / `purgeAll`，索引是 `files/trash/index.json`
（已有 `indexMutex`）。

**做法**
- Prefs：`trashRetentionDays`（**默认 0 = 不自动清理**，自动删用户数据必须显式开启）、`trashMaxBytes`（默认 0 = 不限）。
- `TrashService.autoPurge(now, days, maxBytes)`：先按时间、再按容量（最旧优先），
  与现有索引读写共用同一把锁。
- 触发：`AppContainer` 启动后延迟 5s 在后台跑一次（幂等；没有过期条目时几乎零成本）。
- 设置页「回收站」分组：保留天数、容量上限、立即清理。

**验收**
- 超期条目被删且索引同步；同时有新删除时条目不丢（锁内读改写）。
- 清理失败只记日志，不影响启动。
- 默认值保守（30 天 / 不限容量）。

### 1.8 换行符 CRLF / LF / CR 转换

**现状**：`EditorScreen.kt` 只在行操作时临时把 CRLF 归一成 LF、写回再还原；
没有显式转换入口，也没有「当前文件是什么换行符」的显示。

**做法**
- 编辑菜单「视图」下加「换行符：LF / CRLF / CR」，显示检测值。
- 转换：先归一为 LF，再按目标展开；标脏，撤销栈保留一次。
- 保存按文档当前 EOL 写回，与「指定编码保存」正交。
- 文件 > 5 MB 时提示「可能耗时」，并在后台线程执行。

**验收**
- CRLF → LF 保存后字节级无 `\r`；LF → CRLF 每行恰好一个 `\r\n`。
- 撤销能回到转换前；行操作不再意外改动 EOL。

---

## 2. 批次二 · v2.5.0「应用管理正确性」

### 2.1 split APK 导出 / 安装

**现状**：`exportApk()` 只复制 `info.sourceDir`（base.apk）。
现代应用几乎都是 split，导出的包**装不上**——这是正确性问题，不是缺功能。
Manifest 目前没有 `REQUEST_INSTALL_PACKAGES`。

**做法**
- 导出：`splitSourceDirs` / `splitPublicSourceDirs` 全量收集，打成 `.apks`（zip）或目录；
  命名 `<label>_<version>.apks`；复用现有进度回调与重名策略。
- 安装：`.apk` 继续走现有 `ACTION_VIEW`；`.apks` 用 `PackageInstaller` session
  （base + 各 split），补 `REQUEST_INSTALL_PACKAGES`。
- 本轮只支持 `.apks`；`.xapk` 结构差异大，后续单开。

**验收**
- 微信 / Chrome 等 split 应用导出 `.apks` 后能安装；普通单 APK 行为不变。
- 导出过程可取消、进度可见；重名不覆盖。
- 签名不一致导致装不上时，提示「签名不一致」而不是笼统失败。

**风险**：Android 14 对 session 的限制；split 名称与 ABI 相关；部分应用自带校验。

### 2.2 APK 信息补全

**做法**
- ABI：`primaryCpuAbi` / `secondaryCpuAbi`（或 zip 内 `lib/<abi>/`）。
- `targetSdk` / `minSdk`、安装位置（`INSTALL_LOCATION_*`）、是否 `debuggable`。
- split 列表（base + N 个 split，显示名称与大小）。
- 签名：证书摘要 + 证书数量（`signingInfo.apkContentsSigners`）。

**待定**：签名方案 v1/v2/v3 需要读 APK Signing Block 或引 `apksig` 依赖，
本轮不做，单列评估（避免为了一个字段引重依赖）。

**验收**：split 应用显示 base + N splits；ABI/targetSdk 正确；单 APK 页面不回归。

---

## 3. 批次三 · v2.6.0「目录大小」——**v2.4.2 已废止（用户决定：大小只在「属性」里看）**

> **2026-11-14 结论**：v2.4.0 上线、v2.4.1 补闸门之后，用户明确要求「文件大小只在手动点开属性时看，
> 不需要一直显示」。于是列表副标题去掉体积文本、`DirSize.kt` 整个删除、「网络目录也计算文件夹大小」
> 设置项一并下线；`MtListSubtitle` 从三档（时间 / 权限+大小 / 时间+大小）收成两档（时间 / 权限）。
> 文件夹递归统计只保留在「属性」对话框里（`DialogsProperties.folderSummary`，5 万项上限，用户显式触发）。
> 下面这段留作历史记录 —— **不要再按它去恢复列表内统计**，除非用户重新提出。
>
> 另外：`按大小排序`不受影响（读的是 `FileMetadata.size`，与列表显示无关）；
> 目录仍不参与大小排序（目录的 `size` 依旧未知）。

**现状**：列表里目录的 `sizeText` 直接为空；按大小排序时目录等于不可比；
没有任何缓存。

**做法**
- `DirSizeCache`：LRU（上限 2000 条），key = `uri + lastModified`，
  值 = `(bytes, complete, itemCount)`。
- **只算可视行 + 用户点开**；后台协程 + 可取消（换目录 / 刷新 / 退出即取消）。
- 本地默认开启；**网络目录默认关闭**（设置里「网络目录计算大小」显式打开）。
- 行尾展示：`12.3 M` / `计算中…` / `> 50,000 项`。
- 安全阀：单目录最多 50,000 项、单次计算 30s 上限，超限标「未完成」而不是给错数字。

**验收**
- 进入含 1000 个子目录的目录不卡（列表先出，大小后填）。
- 滚动不重复计算；缓存命中秒出；离开目录后计算真的停。
- 远程目录不产生流量（除非显式开启）。

**风险**：SAF 遍历慢、耗电；缓存 key 必须带 mtime，否则改名/替换后显示旧值。

---

## 4. 批次四 · v2.7+「纵深」

### 4.1 媒体：外挂字幕 + 画中画

- 字幕：同名 `.srt` / `.vtt` 自动探测（`.ass` 后置），编码复用
  `core/common/TextEncodings`（GBK 是刚需）；手动选择文件；±0.5s 时间轴偏移；
  渲染用 Media3 `SubtitleView`。
- PiP：Manifest `supportsPictureInPicture` + `PictureInPictureParams`；仅视频；
  退出 PiP 恢复播放位置与控制条。
- **验收**：字幕与画面同步 ±100 ms；切 PiP 再回来进度不丢、焦点不打架
  （注意 v2.2.4 的音频焦点模型不要被绕过）。

### 4.2 PDF 增强

- 缩放（按 scale 重新渲染 + 位图 LRU）、页码跳转、夜间反色、阅读位置记忆。
- 文本搜索/复制需要引入 pdfbox（`PdfRenderer` 不提供取文本），单独评估成本，
  不与缩放同批做。
- **验收**：放大 3 倍仍清晰（按新 scale 渲染，不是拉伸）；返回再进恢复页码。

### 4.3 EPUB 增强

- 字号 / 行距 / 主题（纸 / 夜）、阅读进度记忆（章节 + 百分比）、书签。
- 现有 `EpubScreen.kt` 注释写明「不做书签进度、字体设置」，本项就是拆掉这句话。
- **验收**：杀进程重进回到上次位置；字号变化不丢当前章节。

### 4.4 DocumentsProvider

- 新 `provider/PanelDocumentsProvider.kt`：`queryRoots`（本地卷 + 已保存连接）、
  `queryChildDocuments`、`openDocument`、`openDocumentThumbnail`。
- 文档 ID = `base64(uri)`；网络 IO 全部在 IO 线程 + 超时；目录列表短缓存（2s）。
- 先做**只读**（不提供 create/delete/rename）；设置里「向其他应用开放」总开关，默认关。
- 依赖：`VfsRegistry` 的空闲回收会掐断 provider 的会话 → 请求期间要 `pin`。
- **验收**：系统文件选择器能看到 PanelFM 根；能打开 SFTP 里的 txt/pdf；
  超时返回明确错误；开关关闭后根消失。

---

## 5. 依赖与批次安排

- **1.5 与 1.6 同批**：都要改 `TransferRequest` / `Done` 模型，分两批会改两次。
- **1.3 与 3 独立**：快速滚动条是手势，目录大小是后台计算，互不阻塞。
  （v2.4.2 起「3」已废止，本条只剩历史意义：快速滚动条仍在，列表内统计没了。）
- **4.1–4.3 各自一版**：都动 `MediaScreen` / `PdfScreen` / `EpubScreen`，
  三件事挤一版会让真机回归面失控。
- **4.4 最后**：会话生命周期（idle 回收 / pin）是它最大的坑，放在主线稳定后。

---

## 6. 真机验证清单

> 沙箱无 `/dev/kvm`、`adb` 起不来；以下每批只回归一次，逐条勾。

**批次一（v2.4.0）**
- [ ] 快速滚动条：2000 项目录拖动、与滑动多选/长按不冲突、少于一屏隐藏
- [ ] 空间预检：本地不足弹窗；SFTP/S3 不弹；同卷 move 不弹
- [ ] 多选属性：20 项混合总大小正确；网络目录不发额外请求
- [ ] 自然排序：`1,2,10`；中英混排
- [ ] 校验三档：SIZE/HASH 各跑一次；HASH 失败可见
- [ ] 失败重试：断网制造 3 项失败 → 详情 3 条 → 重试只跑 3 项
- [ ] 回收站：改保留 0 天/1 天各跑一次；索引不丢条目
- [ ] 换行符：CRLF ↔ LF 保存后字节正确

**批次二（v2.5.0）**
- [ ] split 应用导出 `.apks` → 安装成功；单 APK 不回归
- [ ] APK 信息：split 列表、ABI、targetSdk 正确

**批次三（v2.6.0）**
- [ ] 1000 子目录不卡；缓存命中；远程默认不算
- [ ] 改名/替换文件后大小刷新（缓存 key 带 mtime）

**批次四（v2.7+）**
- [ ] 字幕：UTF-8 / GBK 各一；±0.5s 偏移
- [ ] PiP：进出不丢进度、音频焦点正常
- [ ] PDF：3 倍缩放清晰；页码记忆
- [ ] EPUB：杀进程回到上次位置
- [ ] DocumentsProvider：系统选择器可见、SFTP 文件可开、开关生效

---

## 7. 本轮明确不做（留档）

| 项 | 这次不做的原因 / 重启条件 |
|---|---|
| 修改文件时间（touch） | S 级、引擎已有；本轮优先级排不进。**重启条件**：属性面板要动时顺手做 |
| Intent 接入（分享进/打开方式） | 系统级入口，价值高但需真机回归；**重启条件**：单独一版只做它 |
| 图片 EXIF / 旋转 / 设为壁纸 | 查看器收尾项；**重启条件**：图片场景用得多时 |
| 桌面快捷方式 | 小功能；**重启条件**：与 Intent 同批做 |
| 应用锁（BiometricPrompt） | 安全底座，但非高频；**重启条件**：存敏感连接变多 |
| 空间分析 Top N | 需要自己的扫描底座（v2.4.2 已删掉列表内目录统计，没有现成扫描器了）；**重启条件**：用户提出「找出占空间最多的目录」 |
| 全局搜索（跨连接） | 工作量大、搜索已有「当前目录 + 递归」；**重启条件**：文件量上来后 |
| 压缩包内视频缩略图 | 随机读要先解包（`ArchiveVfs.readFullyAt` 是从头解到该位置），比顺序读完还贵；**重启条件**：压缩包实现真随机读（建索引） |
| 任务持久化 + 冷启动恢复 | 依赖失败重试的模型；**重启条件**：传输中断投诉变多 |
| 远程管理可写 + 二维码 | 只读够用；**重启条件**：PC ↔ 手机回传变成常态 |
| 十六进制查看器 | 招牌但非日常；**重启条件**：有二进制排障需求 |
| 压缩包内编辑回写 | `ArchiveVfs.openWrite` 是 readOnly，要新增写回路径；**重启条件**：改包内配置变成常态 |
| 重复文件查找 | 依赖哈希管线；**重启条件**：空间分析上线后 |
| 同步镜像 / 双向 | 删除语义风险高；**重启条件**：目录对比的单向动作不够用时 |
| 网格视图 / 拖拽 | 形态问题，不是缺失；**重启条件**：平板/图片场景 |
| 批量图片工具 | 与文件管理主线弱相关；**重启条件**：明确要做图片整理 |
| **Shizuku 提权** | **挂起**：会改产品定位与 README；主线做完再定 |
| root / DEX·ARSC·AXML / 签名 / 共存 | 永久不做（项目定位） |
| i18n（英文） | 永久不做，除非要出海（几千处硬编码） |
| 云盘官方 SDK | 永久不做（WebDAV / S3 已覆盖） |

---

## 8. 发版约定

- 本轮 11 项合并为 **v2.4.0** 一版；批次四的三个子项再各自一版（v2.5.0 起）。
- 每项完成后在 [§0](#0-本轮范围) 表格打勾；CHANGELOG 一行一条，详细过程放 commit。
- 发版门槛不变：`testDebugUnitTest` 全绿、`:app:lintDebug` Error 0、`assembleDebug` 通过、
  §6 对应清单逐条勾完。
- 被砍项要重启时，先改 §7 的「重启条件」，再进批次表——避免口头决定丢失。

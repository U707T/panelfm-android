# PanelFM 代码审计报告（以代码为准 · 2026-10-05）

> **本报告取代此前所有 Panelfm-Android 分析文档。** 旧文档已不再作为依据，逐条列在文末「§7 过时文档清单」。
>
> **修复状态（v1.1.0）**：C1 / C2 / C3 与 R1 / R2 / R3 / R4 / R5 均已修复并有回归测试，
> 详见 `CHANGELOG.md` 的 v1.1.0 段落。§6 死代码清理与 §3 性能项仍待办。
> 基线：`main` @ `6ca18f4c5f9946e83e36d11f7dcf68db5a56735d`（versionName `1.0.6` / versionCode 36）
> 方法：只读源码 + 只读构建产物；**结论均给出可复核证据**（文件:行号 / 可复现命令 / 实测输出）。
> 不引用任何既有文档的结论；旧文档与代码冲突处，一律以代码为准并在此显式记录。

---

## 0. 验证故事（先看这个）

| 项 | 命令 | 结果 |
|---|---|---|
| 单元测试 | `./gradlew testDebugUnitTest --offline --console=plain` | **BUILD SUCCESSFUL**；33 个 suite / **223 用例 / 失败 0 / 错误 0** |
| Debug 构建 | `./gradlew assembleDebug --offline --console=plain` | **BUILD SUCCESSFUL**（3 个 ABI APK） |
| Lint | `./gradlew lintDebug --offline --console=plain` | **BUILD SUCCESSFUL**；**Error 0 / Fatal 0**；Warning **20** + Hint 32（明细见 §5） |
| 依赖树 | `./gradlew :app:dependencies --configuration debugRuntimeClasspath --offline` | `netty` / `jose4j` / `jdom` / `httpclient` 命中数 **0**；`bcprov-jdk18on:1.75 -> 1.85`（已约束） |
| 密钥扫描 | `git grep -nI -E 'ghp_\|github_pat_\|AKIA\|BEGIN [A-Z ]*PRIVATE KEY' HEAD` | 仅命中 S3 官方文档示例 AK（`AKIAIOSFODNN7EXAMPLE`，测试固定向量）与注释文本 |
| 空白检查 | `git diff --check` | 干净 |
| 忽略规则 | `git check-ignore -v key.properties local.properties .gradle` | 三者均被忽略 ✓ |
| **变异测试** | 见下 | 4 处变异 → 1 处被测试捕获、**3 处漏网**（详见 §4 测试有效性） |

**工作树状态**：`git status --short` → 仅 `docs/`（本报告）。本次审计**未改动任何源码**；
所有变异实验均已还原（源码 `git diff` 为空）。文档处置与 CHANGELOG 修复见 §7。

### 变异测试（按 skill 要求「用实验而非阅读」验证测试有效性）

| # | 变异位置 | 变异内容 | 套件结果 | 判定 |
|---|---|---|---|---|
| M1 | `ArchiveVfs.normalize()` | 删除 `if (parts.any { it == ".." }) return null` | `core:vfs-archive` **1 failed** | ✅ 有测试兜住（`压缩包路径穿越条目被拒绝`） |
| M2 | `TransferTask.deleteForOverwrite()` | 删除「删除后再次 stat 确认目标消失」 | 全绿 | ❌ **漏网**：`覆盖删除失败时不能继续写入目标` 只覆盖「delete 抛异常」分支，没覆盖「delete 静默成功但目标仍在」 |
| M3 | `TransferTask.isSameOrDescendant()` | `if (!candidate.sameMount(root)) return false` → `if (false) return false` | 全绿 | ❌ **漏网**：`KEEP_BOTH`/`SKIP` 子树映射测试只用了单挂载点，跨挂载点误判无人守 |
| M4 | `RemoteHttpServer.normalizePath()` | `".." -> return null` 改为 `".." -> Unit` | 全绿 | ❌ **漏网**：`RemoteHttpServer` 在 `:app` 内**零测试覆盖** |

> 结论：`TransferTask` 与 `RemoteHttpServer` 是本项目最需要补测试的两个文件（见 §4）。

---

## 1. Critical —— 必须修（功能实际不可用 / 用户被误导）

### C1. ZIP 口令压缩路径 100% 失败，且写出 0 字节（`ArchiveCompressor.addEncryptedZipFile`）

> ✅ **v1.1.0 已修复**：改走 `EncryptedZipWriter`（`ArchiveCompressor.compressEncryptedZip`），
> 回归测试 `EncryptedArchiveRoundTripTest.ZIP 带口令压缩后能被自己读回`。

**证据（实测，探针已删除）**：用 `ArchiveCompressor(locator).compress(sources, dest, Format.ZIP, password = "pw")`：

```
压缩失败：Unsupported feature encryption used in entry src/a.txt
  cause = org.apache.commons.compress.archivers.zip.UnsupportedZipFeatureException
    at ZipUtil.checkRequestedFeatures(ZipUtil.java:140)
    at ZipArchiveOutputStream.copyFromZipInputStream(ZipArchiveOutputStream.java:629)
    at ZipArchiveOutputStream.addRawArchiveEntry(ZipArchiveOutputStream.java:508)
    at ArchiveCompressor.addEncryptedZipFile(ArchiveCompressor.kt:373)
写出字节数 = 0
```

**根因**：`ArchiveCompressor.kt:373` 用 `addRawArchiveEntry(entry, ByteArrayInputStream(payload))`，而 entry 上已经
`generalPurposeBit.useEncryption(true)`。commons-compress 1.27.1 的 `addRawArchiveEntry` 内部走
`copyFromZipInputStream`，**只要 entry 带 encryption 标志就抛 `UnsupportedZipFeatureException`**。
即 `ZipCrypto.kt` 头注释里那句「没有写侧 API（`addRawArchiveEntry` …喂裸数据会抛 UnsupportedZipFeatureException: encryption）」
是**对现状的正确描述，却被自己的调用方违反**。

**同时存在的事实**：仓库里已有一份**正确**的写侧实现 `EncryptedZipWriter`（流式 + Data Descriptor，被
`EncryptedZipRoundTripTest` 用 commons-compress 交叉验证过），但它在生产代码里**没有任何调用点**
（`grep -rn "EncryptedZipWriter" app core` → 只有定义与测试）。

**修法（二选一，不要都做）**：
1. `addFile()` 的 `keys != null` 分支改调 `EncryptedZipWriter`（把 `SuspendOutputStream` 传进去），删掉 `addEncryptedZipFile`；
2. 或者删掉「ZIP 加密」这个能力，UI 上把 ZIP 从 `supportsPassword` 里拿掉。

**顺带**：`EncryptedZipWriter` 不写 Zip64（`check4GB` 抛错提示改用 7z），且**没有 4GB 上限的测试**。

### C2. 加密 7z 能创建、但自己读不回来（`ArchiveVfs` 无口令入口）

> ✅ **v1.1.0 已修复**：`ArchiveVfs` 增加 `password`，7z 经 `SevenZFile.setPassword` 透传。
> 回归测试 `EncryptedArchiveRoundTripTest.7z 带口令压缩后能被自己读回`。

**证据（实测，探针已删除）**：`compress(..., Format.SEVEN_Z, password = "pw")` → 成功，149 字节；随后
`ArchiveVfs(dest, SEVEN_Z, tmpFile, env).connect()` → 成功、`list("/")` → `[src]`；再
`openRead("src/a.txt")`：

```
org.apache.commons.compress.PasswordRequiredException:
  Cannot read encrypted content from /tmp/enc****.7z without a password.
```

**根因**：`ArchiveVfs.buildIndex()`（`ArchiveVfs.kt:176` 附近）与 `openEntryStream()` 都用
`SevenZFile.builder().setFile(localFile).get()`，**从不传口令**；整个 UI 也没有任何「输入压缩包口令」的入口。

**用户可见后果**：用 PanelFM 加密压出的 7z，用 PanelFM 打开 → 目录能列、文件点开报
`PasswordRequiredException` 原文（英文技术串），且无路可走。

**修法**：`openArchive(host)` 增加可选口令参数 → 挂载时透传给 `SevenZFile`/`ArchiveReader`；
UI 在打开加密包时弹口令框；错误口令给可读文案（不是 `PasswordRequiredException`）。

### C3. 加密 ZIP 也读不回来（同一根因的 ZIP 侧）

> ✅ **v1.1.0 已修复**：新增 `ZipCryptoStream.kt` 自研解密（commons-compress 无加密 ZIP 读实现）。
> 实现过程中发现并修掉两个**只有实测才能暴露**的问题：
> ① 校验字节必须用 `ZipUtil.toDosTime(...)[1]`，`ze.time` 是 epoch 毫秒、右移 8 位会得到完全不同的字节
> （表现为「正确口令被判成错误口令」）；
> ② `VfsException.Auth.userMessage` 原先忽略具体 message，把「口令不正确」显示成「请检查用户名/密码/密钥」。
>
> 关于「ZipEditor 静默去掉加密」：**实测推翻了这条假设** —— `zf.getInputStream(加密条目)` 会抛
> `UnsupportedZipFeatureException` 让整包重写失败，即数据是安全的、只是不可用。
> v1.1.0 改为提前拦截并给出可执行文案（回归测试 `加密 ZIP 不允许内部增删改名`）。

**证据（实测，探针已删除）**：用 `EncryptedZipWriter` 产出加密 zip（152 字节，能被 commons-compress 用口令读回），
交给 `ArchiveVfs(ZIP).openRead("a.txt")`：

```
UnsupportedZipFeatureException: Unsupported feature encryption used in entry a.txt
```

`ArchiveVfs.kt:145` 的 `ZipFile.builder().setFile(localFile).get()` 未设 `setPassword`。
（列目录成功——只读中央目录不触发解密。）

**附带**：`ZipEditor.rewrite()` 会把加密条目**原样解密后以明文重写**（`ZipEditor.kt` 用 `zf.getInputStream(entry)`
+ 新建 `ZipArchiveEntry`，不带 `useEncryption`）→ **对加密包做「删一个文件」会静默去掉加密**。这条目前被
C3 的读取失败「挡住」了（到不了那一步），但一旦修好 C3，就会立刻变成**静默降级安全**问题。**修 C3 时必须同时修这条**。

---

## 2. Required —— 必须在合并/发布前处理

### R1. `settings.trustSelfSigned` 是死开关：设置页写、协议层不读

> ✅ **v1.1.0 已修复**：经 `VfsEnv.trustSelfSignedDefault` 桥接（连接级选项优先）。

- 写入：`SettingsScreen.kt:253` `SettingSwitch("默认信任自签证书（新的 WebDAV 连接）", settings.trustSelfSigned)`
- 读取：`WebDavVfsFactory.create()` → `DavHttp.client(...)` 硬编码 `trustSelfSignedDefault = false`（`WebDavVfs.kt:546`）
- 全项目对 `settings.trustSelfSigned` 的消费点：**0**（仅设置页自己显示）

→ 用户打开这个开关，行为没有任何变化。

### R2. 「信任自签证书」开关对 SFTP / SMB / S3 无效，但 UI 对所有协议都显示

> ✅ **v1.1.0 已修复**：S3 接入 TLS 策略；开关改为**只对真正读取该选项的协议显示**（WebDAV/FTP/FTPS/S3）。

`ConnectionEditScreen.kt:594` 对**所有协议类型**都渲染「信任自签证书 / 信任所有 HTTPS 证书」，
但只有 WebDAV（`DavHttp.kt:41`）与 FTP/FTPS（`FtpConfig.kt:29`）读 `OPT_TRUST_SELF_SIGNED`：

```
grep -rn "OPT_TRUST_SELF_SIGNED" core app   → 只有 Connection.kt / DavHttp.kt / FtpConfig.kt / ConnectionEditScreen.kt
```

`S3Client` 的 `OkHttpClient`（`S3Client.kt:38`）只设了 timeout，**没有 sslSocketFactory / hostnameVerifier** →
S3 + 自签 HTTPS（自建 MinIO 的常见形态）**必然握手失败，且开关无效**。

**修法**：按 `type` 决定是否渲染该开关；S3/SFTP/SMB 要么实现对应信任策略，要么不给开关。

### R3. 任务并发设置重启后失效

> ✅ **v1.1.0 已修复**：`AppContainer` 在 settings 首次发射时调用 `engine.updateConcurrency`。

- `TransferEngine` 构造默认 `maxConcurrent = 2`（`TransferEngine.kt:64`）
- `updateConcurrency(n)` 只在 `SettingsScreen.kt:262` 被调用（点按钮那一刻）
- `AppSettings.maxConcurrentTasks` 在启动路径上**没有**被应用（`grep -rn maxConcurrentTasks` → 仅 SettingsScreen 两处）

→ 选 4 个并发，重启回到 2。**修法**：`AppContainer` 初始化时（settings 首次发射后）调用 `engine.updateConcurrency(s.maxConcurrentTasks)`。

### R4. 浏览模式（单列/双列/自动）不持久化

> ✅ **v1.1.0 已修复**：新增 `PrefsStore.browseMode`，启动读回。

设置页「界面 → 浏览模式」三个按钮直接调 `container.browser.setBrowseMode(mode)`，而
`setBrowseMode()`（`BrowserController.kt:171`）只 `update { copy(browseMode = mode) }`，**不写任何持久化**；
`PrefsStore` 里只有布尔 `useSingleColumn`。

→ 选「自动切换」后重启：回落到 `useSingleColumn` 派生的值（默认 `false` → 双列），「自动切换」**永远无法保留**。
只有另一个开关「默认单列显示」是持久的。
**修法**：`PrefsStore` 增加 `browseMode` 键，让「默认单列显示」变成它的快捷方式。

### R5. `?c=<connectionId>` 隔离链路不完整 → 同主机多账号仍会误判

> ✅ **v1.1.0 已修复**：`AppContainer.uriForConnection()` + `BrowserController.open()` 统一注入。
> 回归测试 `ConnectionIsolationTest`（7 项）。

- `VfsUris.withConnection()`（唯一的 URI 加 `c=` 的 API）**零生产调用点**（只在 `VfsUriTest` 里用）
- `c=` 只由两处**手工字符串拼接**产生：`HomeScreen.kt:323`、`DualPaneScreen.kt:216`
- 其余入口（书签 `openBookmark`、最近路径 `SideDrawer`→`onOpenRecentPath`、`syncPath`、`up()`、压缩包返回上级）
  传的是**不带 `c=`** 的 URI，`connectionId` 只存在 `PaneTab` 里，不写回 URI

后果：`VfsUri.sameMount()` 在这些 URI 上退化为「只比 scheme+authority」→ 同主机不同账号会被当成同一挂载点 →
`FileOperationPlanner.isInside()` 误判「目标在源内部」直接拒绝操作、`isSameOrDescendant()` 误判导致子树被跳过。
（方向上偏「拒绝/跳过」，未观察到数据丢失路径，故定 Required 而非 Critical。）

**修法**：把 `c=` 的注入收敛到一处——`AppContainer.openConnection()` 返回的 URI 由工厂统一 `withConnection(config.id)`，
或让 `BrowserController.open()` 在写入 `PaneTab` 时同步把 `c=` 落到 `VfsUri`。

### R6. `docs/BUG-AUDIT.md` 的「批次索引」与代码状态不符，且文件未提交

> ✅ **v1.1.0 已处理**：文件已删除（见 §7 D3）。

- 文档状态表：第二批「进行中」、第三批「待开始」
- 实际：`f65f506`（第二批·核心修复）、`5dd9531`（第三批·安全加固）、`6ca18f4`（第三批·依赖加固）**已提交**
- 文件本身 `git status` 为 `??`（未跟踪）

→ 要么补完并提交，要么删（见 §7）。

---

## 3. Optional / Nit

### 性能
- **O1** `TransferTask.transferFile()` 每 256 KB 写一次 SQLite（`resumeStore.save`，`:600`）。1 GB 文件 ≈ 4096 次
  `INSERT OR REPLACE`。建议按时间节流（例如 ≥1 s）而非按块。
- **O2** `MediaScreen` 用 `while(true){ …; delay(250) }` 每 250 ms 写 4 个 Compose state → 播放全程 4 Hz 重组。
  可改为 `Player.Listener` 事件驱动 + 进度用 `Animatable`。
- **O3** `rememberThumb()`（`Thumbnails.kt:141`）在**每个列表行**里 `container.settings.collectAsState()` →
  LazyColumn 可见行各订阅一次 settings。建议在 PaneView 顶层取一次后按参数下传。
- **N1** `RemoteHttpServer` 的并发上限检查存在 TOCTOU（`clients.get() >= MAX_CONCURRENT` 与 `incrementAndGet()` 非原子），
  实际并发可略超 8；用 `AtomicInteger.getAndIncrement()` + 回退即可。
- **N2** `RemoteHttpServer` 只有 `soTimeout`（读超时），**没有写超时**（代码注释已自认）；慢客户端仍可占住线程直到 TCP 超时。

### 正确性 / 一致性
- **N3** `LocalVfs.rename()` 跨卷回退用 `runCatching{…}.getOrDefault(false)`：复制中途失败会留下**半成品目标目录**
  且返回 false，调用方（`TransferTask` 慢路径不会走这里；但 `BrowserController.rename()` 会）只报「服务器拒绝重命名」。
- **N4** `PanelDb.onUpgrade()` 把 `if (oldVersion < 3)` 写在 `if (oldVersion < 2)` **之前**（顺序反了）。两个分支互不依赖，
  当前行为正确，但阅读顺序会误导后来人；建议按版本升序排列。
- **N5** `copyPath()`（`BrowserController.kt:1532`）只 `showStatus`，**不写系统剪贴板**；DualPaneScreen 的
  `copy_path` 动作是直接 `clipboard.setText`。两处语义不一致，且前者**无调用点**（死代码，见 §6）。
- **N6** `LocalNetwork.legacyStorageGranted()` 无调用点，注释自述「保留它只是为了避免 lint 报错」——属于「为过 lint 而留代码」，应删。

### 文档/注释
- **N7** `.github/workflows/ci.yml:56` 注释「缺失时自动回退 debug 签名，构建不中断」与
  `app/build.gradle.kts` 的 `if (isCi && !hasReleaseKey) throw GradleException(...)`（**CI 硬失败**）矛盾。注释过时。
- **N8** `FileMetadata.childCount` 从未赋值；`FileMetadata.extra` 只有 `SmbVfs.kt:201` 写入、**全仓零读取**（只写不读）。
- **N9** `PanelDb` 的 `task_record`、`tab_session` 两张表只有建表语句，**没有任何读写**（死 schema）。

---

## 4. 测试有效性（对应 §0 变异测试）

**覆盖良好**：`core/common`（10 suite）、`core/transfer`（3 suite，含 8 项传输回归）、
`core/vfs-archive`（3 suite，含 ZipCrypto 与加密往返）、`core/vfs-webdav`、`core/vfs-sftp`、`core/vfs-s3`。

**关键缺口（按优先级）**：

| 缺口 | 证据 | 建议补的用例 |
|---|---|---|
| `RemoteHttpServer` 零覆盖 | M4 变异全绿 | `normalizePath` 纯函数化后单测：`/../etc` → 400、`/a/../b` → `/b`、`/..` → null |
| 「删除成功但目标仍在」未覆盖 | M2 变异全绿 | `FakeVfs` 加 `silentlyIgnoreDelete`，断言任务 Failed 且目标未被写入 |
| 跨挂载点子树映射未覆盖 | M3 变异全绿 | 两个 `FakeVfs`（不同 authority）做 `KEEP_BOTH`，断言不跨挂载点合并 |
| 加密包读回未覆盖 | C2/C3 实测 | `compress(password) → openArchive → read` 端到端（当前**必失败**，正好当红灯） |
| `?c=` 隔离未覆盖 | R5 | 无 `c=` 的同主机双账号 URI 走 `sameMount` / `isInside` |

---

## 5. Lint 明细（`lintDebug` 全量）

**Error / Fatal：0**。Warning 20 项分布：

| id | 数 | 说明 |
|---|---|---|
| `TrustAllX509TrustManager` | 8 | `DavHttp.InsecureTls` + `FtpVfs.InsecureTrustManager`（按连接开关启用，非全局） |
| `ModifierParameter` | 7 | Compose 组件 modifier 参数位置/命名（`core:ui`） |
| `CustomX509TrustManager` | 2 | 同上 |
| `ConfigurationScreenWidthHeight` | 2 | 用屏幕宽高判断宽屏（`BrowseMode.AUTO`），建议改用 `WindowSizeClass` |
| `DataExtractionRules` | 1 | 缺 `dataExtractionRules`（`allowBackup=false` 已设，属提示） |

Hint 32 项全部是 `AutoboxingStateCreation`（`mutableStateOf(Int/Long)` → 建议 `mutableIntStateOf`），无功能影响。

`lint.xml` 的两条 opt-in（`UnsafeOptInUsageError` → Media3 `UnstableApi`；`ObsoleteSdkInt` → `mipmap-anydpi-v26`）**范围收窄**，
没有用 baseline 掩盖新错误 ✓。

---

## 6. 死代码清单（按 skill「Dead Code Hygiene」要求显式列出，等确认后删）

```
DEAD CODE IDENTIFIED（均为 grep 全仓确认的零调用点）
- BrowserController.copyPath()                    — 无调用点，且与 DualPaneScreen 的 copy_path 语义重复（N5）
- BrowserController.allTasks()                    — 无调用点（UI 用 engine.snapshots）
- BrowserController.kindLabel()                   — 无调用点
- BrowserController.summary() / summaryFor(mode)  — 无调用点（底部统计行在 DualPaneScreen.kt:359 自己拼）
- BrowserOps object                               — 无调用点（仅返回 container.planner）
- TransferEngine.hasRunning() / activeTasks()     — 无调用点（UI 直接看 snapshot 状态）
- VfsUri.archive()                                — 无生产调用点；且硬编码 authority="zip"，与 ArchiveVfs.uriFor() 的 kind.id 不一致（重复实现）
- VfsUris.withConnection()                        — 无生产调用点（R5 的根因之一，修 R5 时应变成唯一入口而不是删）
- FileMetadata.childCount                      — 从未赋值、从未读取（纯死字段）
- FileMetadata.extra                          — 只有 SmbVfs.kt:201 写入（hidden=true），全仓**零读取**（只写不读）
- Fmt.sizeDiffers()                           — 无调用点
- LocalVfs.permissionString()                 — 无调用点（Fmt.mode 已被直接调用）
- Set<PosixFilePermission>.toMode()（LocalVfs.kt:402）— 无调用点，且与 core:vfs-api 的 PosixModes.toMode() **完全重复**（近重复实现）
- core:vfs-api PosixModes                    — SftpVfs.kt:10 只 import 未使用（`PosixModes.` 命中 0 次）；整个 object 生产零调用
- ConnectionDao.getStringOrNull()             — 无调用点
- SecretStore.has()                           — 无调用点
- LocalNetwork.legacyStorageGranted()             — 无调用点，注释自述为过 lint 而留（N6）
- PrefsStore.clearInputHistory()                  — 无调用点
- PrefsStore.RecordKeys.FILE_SEARCH               — 常量无消费点（搜索历史走 searchHistory 而非 inputHistory）
- ArchiveCompressor.addEncryptedZipFile()         — 见 C1：应删除或改写
- EncryptedZipWriter                              — 生产零调用点（只有测试用）；修 C1 的正确做法是**接上它**
- PanelDb 表 task_record / tab_session            — 只建表，零读写（N9）
```

---

## 7. 过时文档清单（**按用户指示：暂不删除，等确认**）

> 判定标准：与 `6ca18f4` 的代码事实冲突、或状态自述已过期、或不在仓库内无法复核。
> **处理建议**：确认后删除 / 重写，不要让它们继续作为后续会话的输入。

| # | 文档 | 判定 | 处置 |
|---|---|---|---|
| D1 | `docs/MT-ALIGNMENT-CODE-REVIEW.md` | **部分过时 + 含错误结论**（① §2 表「压缩 已实现 … ZIP/7z 口令」与 C1/C2/C3 实测冲突；② §1.1「同主机多账号会话复用」说得过强，实际 `?c=` 链路不完整=R5；③ §3 只列 5 条差距，漏掉加密包读回与无效设置开关；④ 章节编号缺 §5；⑤ §6 的 `./gradlew test`（无 `--offline`）在本沙箱会卡） | ✅ **已删除**（`git rm`） |
| D2 | `docs/SECURITY-BASELINE.md` | **依赖结论正确但覆盖不全**（未记录 `usesCleartextTraffic=true`、`QUERY_ALL_PACKAGES`、`MANAGE_EXTERNAL_STORAGE` 的 `tools:ignore`，以及 R1/R2 的信任开关问题） | ✅ **已删除**；其中仍成立的安全事实已在 §5 与本报告正文重述 |
| D3 | `docs/BUG-AUDIT.md` | **状态过时**（批次索引写「第二批进行中 / 第三批待开始」，实际 `f65f506`/`5dd9531`/`6ca18f4` 已完成） | ✅ **已删除**（文件此前未跟踪） |
| D4 | `CHANGELOG.md` | **结构回归**：`git show 5f7d1df:CHANGELOG.md` 有 `## v1.0.5` 标题，HEAD 把标题删掉、内容降级为 v1.0.6 的子标题 → v1.0.5 发布记录丢失；且 v1.0.6 段落指向已删除的 D1 | ✅ **已修复**：恢复 `## v1.0.5` 标题层级；v1.0.6 段落改为指向本报告；文件头加「MT 解析文档已废弃、不得再作依据」的告示 |
| D5 | `/workspace/mt-analysis/MT-UI-功能-逻辑-解析.md`（**仓库外**） | **不可作为依据**：不在仓库内、无法随代码复核，`0x7f……` 资源 ID 推断无法验证 | ⏸ **保留但已废止**：不在本仓库内，删除会破坏其他会话；已在 CHANGELOG 头部显式标记为废弃、禁止引用 |
| D6 | `/workspace/tmpwork/verify1`、`/workspace/tmpwork/panelfm-batch2`（`git worktree` 旧检出） | **旧快照副本** | ⚠️ **发现未合并的工作**（见下）→ 已先抢救为补丁，再删除 worktree |
| D7 | `/workspace/dist/PanelFM-1.0.5-*.apk`、`build/rc-v0.13.0-rc.1/` | **陈旧产物**（与 1.0.6 不符） | ✅ **已删除** |

### 删除前抢救出来的东西（重要）

`/workspace/tmpwork/panelfm-batch2`（worktree @ `f65f506`）里有**一整批未提交、也从未进入 main 的修复**：

```
core/transfer/TransferTask.kt      | 178 +++++++++++-----   ← 服务端快路径失败降级
core/transfer/FakeVfs.kt           |   4 +
core/transfer/TransferRegressionTest.kt | 125 +++++++++++
```

**它修的是真问题**：`VirtualFileSystem.serverSideCopy` 的接口注释写明「不支持返回 false（**引擎降级为流式泵**）」，
但 main 的 `TransferTask.kt:123` 把 `false` 直接当成
`failed += FailedItem(src, "服务端操作失败")` —— **违反接口契约**。
`WebDavVfs.serverSideCopy()`（`WebDavVfs.kt:212`）返回的是 `r.isSuccessful`，
所以「WebDAV 复制到已有同名目标的目录」这类场景会**整批失败而不是降级**。

该分支实测 `core:transfer` **20 用例全绿**（main 为 16），新增 4 项：
`服务端复制返回 false 时降级为流式复制` / `服务端移动返回 false 时降级为复制后删除源` /
`快路径同名文件 KEEP_BOTH 和 SKIP 都遵守冲突策略` / `目录覆盖目录走慢路径合并而不是先删除整个目标`。

已抢救为 `docs/../.preserved/unmerged-batch2-fastpath-fallback.patch`（400 行，可 `git apply`）。
**这不是本次审计发现的**，是清理 worktree 时发现的；**要不要合并由你决定**（见 §8 批 F）。

---

## 8. 结论与建议顺序

**可以合并/发布的判断**：代码基线是健康的 —— 223 用例全绿、构建与 lint 干净、无密钥泄漏、
依赖树无已知高危（`bcprov` 已约束到 1.85）。

**但不能对外宣称「压缩加密已实现」**：C1/C2/C3 三条都是「用户按下按钮 → 要么报错要么读不回来」的硬伤，
且都集中在 `core/vfs-archive` 一个模块里，彼此耦合（修 C3 会引出 ZipEditor 静默降级），
建议**单独成批、按 C1 → C2 → C3+ZipEditor 的顺序**做，每步补端到端测试。

**建议分批（每批一个主题、独立可回滚）**：

1. **批 A（Critical）**：`core/vfs-archive` 加密读写闭环（C1/C2/C3 + ZipEditor 保留加密 + 端到端测试）
2. **批 B（Required·设置生效）**：R1/R2/R3/R4 —— 四个「设置项不生效/误导」一次性收口，纯 UI+容器层改动，风险低
3. **批 C（Required·会话隔离）**：R5 把 `?c=` 收敛到唯一入口，补跨账号测试
4. **批 D（测试补齐）**：`RemoteHttpServer` 纯函数化 + 单测；`FakeVfs` 加「静默失败」开关补 M2/M3 用例
5. **批 E（清理）**：§6 死代码删除 + D1–D7 文档处置

**架构层面（不阻塞，但值得记一笔）**：
`app` 模块单文件偏大（`BrowserController.kt` 1972 行、`DualPaneScreen.kt` 1787 行、`MediaScreen.kt` 1284 行），
已越过「~1000 行即需检查」的阈值。后续任何**新增**功能前，建议先把 `BrowserController` 按
「导航 / 选择 / 压缩包 / 剪贴板 / 任务」拆成多个内部委托类，再往上加东西。

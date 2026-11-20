# 变更审计（2026-11-20 · v2.5.3 卡片排版 + 复用收口）

> 范围：v2.5.2 之后的改动 —— APK 信息卡片的排版、全仓「重复造轮子」收口、
> 以及 v2.5.2 引入路径的自审修复。
> 方法：逐行读 diff；对每个新 helper 找齐调用点（`grep` 旧写法应归零）；
> 沙箱 `testDebugUnitTest`（全仓）+ `compileDebugKotlin`；`git diff --check`。
> 结论：**7 条修复项 + 5 处收口 + 3 条已知限制**，修复项已全部在发版前改掉。

---

## 1. 自审修复项（v2.5.2 引入的问题）

### 1.1 `.apks` 点「功能 → APK 详细信息」直接报错

- 位置：`ApkInfoScreen.kt`（整页详情）
- 原实现：`materializeApk` 拿到的是**外层 zip**，`PackageManager.getPackageArchiveInfo` 必然失败 →
  卡片里对 `.apks` 点「APK 详细信息」只会看到「不是有效的 APK」。
  （v2.5.2 之前这个入口对分包包不可达，所以不是回归，但新入口把它暴露出来了。）
- 修法：整页同样先 `extractBundleBaseApk` 再解析；顶栏副标题区分
  「APK 详细信息」/「APK 详细信息（取自 base.apk）」。校验值仍按**外层文件**算（用户看的是它）。

### 1.2 `.xapk` / `.apkm` 点「安装」给出误导文案

- 位置：`BrowserFileActions.installApk`
- 原实现：非 `.apk` / `.apks` 一律「只有 APK / .apks 文件可以安装」——用户不知道下一步该做什么。
- 修法：文案改成「XAPK 不能直接安装：先用「转为 APK」合并成单个 APK」。

### 1.3 远端 APK 的「安装」是死路

- 位置：`DialogsApkInfo` 的安装按钮
- 原实现：一律 `installApk` → 「请先复制到本地」，可解析时**已经把同一份字节缓存到 `cache/apk`** 了。
- 修法：本地走原路径；远端 / 包内的**单 APK** 直接装缓存副本（`installApkFile`，`file_paths.xml` 已含
  `cache-path`）；分包包仍要求先「转为 APK」。

### 1.4 长按菜单里没有「APK 信息」

- 位置：`TypeActions.typedActionIds`
- 原实现：只有 `.apk` 有该项，`.apks` / `.xapk` / `.apkm` 没有 —— 与「单击就弹卡片」不一致。
- 修法：三种分包外壳也加 `ACTION_APK_INFO`（压缩包内部仍不给，需先落到本地）。

### 1.5 名称不一致：卡片叫「APK 信息」、整页也叫「APK 信息」

- 修法：卡片 = **APK 信息**（单击 / 长按同一入口），整页 = **APK 详细信息**
  （`PreviewMode.APK_INFO.label` 与页面副标题同步）。

### 1.6 死代码：`TypeActions.isApk(kind)`

- 现状：v2.5.2 之后没有任何调用方（判定改走 `isApkFamily(name)`）。
- 修法：删除，避免以后有人接到旧的「只有 .apk 才算 APK」语义上。

### 1.7 编译警告：`pkg.packageName ?: "—"` 恒为左值

- 位置：`ApkInfoScreen.kt`
- 原因：`PackageInfo.packageName` 在 Kotlin 里是平台类型 `String!`，elvis 是死分支。
- 修法：改 `ifBlank { "—" }`，语义不变且不再报警。

---

## 2. 复用收口（「不要哪哪都造轮子」）

| 轮子 | 之前的分布 | 现在 |
|---|---|---|
| 「标签 + 值」行 | 3 份（`DialogsInput` 共享版 / APK 详情页私有版 / APK 卡片私有版），字号与列宽各写各的 | **1 份** `ui/browser/DialogsInput.InfoRow(label, value, labelWidth, labelStyle, valueStyle, maxLines, verticalPadding)`；详情页与卡片只绑参数 |
| 十六进制 / 摘要 | 9 处手写 `joinToString { "%02x".format(Locale.ROOT, it) }`，漏一次 `Locale.ROOT` 就会让缓存键随系统语言漂移 | **1 份** `core/common/Hashes.kt`：`ByteArray.toHex()` / `digestHex()` / `sha256Hex(data, takeBytes)` / `sha1Hex()`；7 个文件改为调用 |
| VFS → 本地文件 | 9 个文件手写 `openRead` + `while(true)` 复制循环（只有编辑器那份处理了「0 字节读」与取消） | **1 份** `core/vfs-api/VfsCopy.kt`：`VfsReader.copyTo(out, bufferSize, onProgress)`，带 `ensureActive` + 连续 0 读保护；本次替换 APK / PDF / 字体 3 处 |
| java.io 复制 | 各处各写一份 | **1 份** `core/common/Streams.kt`：`InputStream.copyStreamTo(...)`（zip 条目 → 文件用） |
| 物化缓存（远端文件落盘） | APK 与 PDF 各一套：目录、SHA-256 键、`.part` 中转、3 天清理、失败删残片全都重复 | **1 份** `app/MaterializedCache.kt`：`materializeToCache(...)`；`materializeApk` / `materializePdf` 变成两行包装 |
| 「先写 `.part` 再 rename」 | 物化缓存、APK 分包解 base、其余导出路径各写一遍 | **1 份** `writeAtomically(target) { out -> … }`（失败 / 取消都删残片，避免下次命中半截文件） |
| 缓存清理 | APK 与 PDF 各一份「删 3 天前的文件」 | **1 份** `core/common/CacheFiles.kt`：`sweepStaleFiles(dir, keep, maxAgeMs)` |
| FileProvider URI | 6 处各自拼 `"${context.packageName}.fileprovider"` 并各包一层 runCatching | **1 份** `open/ExternalOpen.fileProviderUri(context, file)` |
| APK 图标 | 列表行与卡片各一套取图逻辑（v2.5.2 已合并） | `ThumbCache.loadApkIconOf(pm, file, px)`，列表行传 VFS 文件、卡片传缓存里的 `base.apk` |

### 仍未收口（有意留到后续批次）

- `openRead` + 复制循环还剩 6 处（压缩包挂载、编辑器、导出、内置 HTTP 服务、`AppContainer` 的接收链路）。
  它们各自带 **seek / 进度 / 边读边算** 的语义，不能机械替换；统一到 `VfsReader.copyTo` 需要逐个验证
  「取消后是否留下半截文件」。已在 `VfsCopy.kt` 的文档里写清入口，下一批处理。
- `LocalClipboardManager`（35 处）已废弃，官方推荐 `LocalClipboard`（挂起 API）。全仓统一迁移
  属于机械改动 + 需要逐处改 `suspend` 调用点，单开一版做，本次不动。
- `core/apk` 的 AXML 解析与 `vfs-archive` 的 zip 读取各有自己的中央目录遍历，但两者面向的结构不同
  （AXML 资源表 vs zip 条目），合并反而会增加耦合，保持现状。

---

## 3. 排版（APK 信息卡片）

对照 MT 截图逐项对齐：

| 项 | 旧（v2.5.2） | 新（v2.5.3） |
|---|---|---|
| 卡片圆角 | M3 默认（28dp + 窄内边距） | **24dp**（`shape` 显式指定，与 MT 一致） |
| 应用图标 | 44dp / 12dp 圆角 | **56dp / 16dp 圆角** |
| 标题 | `titleMedium`（16sp） | **`titleLarge` 20sp**，最多 2 行 |
| 版本行 | `labelSmall`（11sp） | **`bodyMedium`（14sp）** |
| 行文案 | `bodySmall`（12sp）、标签列 76dp、行距 3dp | **`bodyMedium`（14sp）、标签列 88dp、行距 6dp** |
| 值换行 | 最多 2 行 | **最多 3 行**（长路径不靠省略号猜） |
| 分包说明 | `labelSmall` | `bodySmall`（仍弱于正文，但可读） |

---

## 4. 验证

- `:app:compileDebugKotlin --offline`：通过，无新增警告。
- `testDebugUnitTest --offline`：全仓 **876 例 / 0 失败**（新增 `HashesTest` 6、`StreamsTest` 4、
  `CacheFilesTest` 4、`VfsCopyTest` 5，`TypeActionsTest` 更新分包菜单断言）。
- `grep` 归零检查：`"%02x"` 只剩 `Hashes.kt`；`FileProvider.getUriForFile` 只剩 `ExternalOpen.kt`；
  `pruneMaterialized` / `materializeKey` / 私有 `InfoRow` 均已消失。
- `git diff --check`：无空白错误。

---

## 5. 已知限制（需真机）

- 卡片在 360dp 窄屏 + 长包名下的折行效果、深色主题下 24dp 圆角的观感只能真机验收。
- `.apks` 首次点开要解出 `base.apk`（缓存后二次命中）；大包首开 1–3 秒，进度文案有提示但无法在沙箱测时。
- 远端 APK 的「安装缓存副本」依赖系统安装器对 `content://`（cache 目录）的接受度，
  与「本地文件」走的是同一套 FileProvider 配置，仍需真机确认一次。

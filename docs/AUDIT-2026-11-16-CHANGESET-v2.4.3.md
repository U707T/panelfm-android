# 变更审计（2026-11-16 · v2.4.3 用其他应用打开）

> 范围：v2.4.3 的 ACTION_VIEW 接收链路（4 个新增主文件、3 个新增测试文件、Manifest 与本地 VFS 映射）。
> 方法：逐文件读 diff + 并发/失败路径推演；沙箱全量单测、`assembleDebug`、`lintDebug`。
> 结论：**3 条建议项 + 1 条结构优化 + 1 条死代码清理**，全部在发版前修掉；无未决阻断项。

## 1. 修复项

### 1.1 rename 失败时退化为直接复制 → 可能留下半截正式文件

- 位置：`app/.../open/IncomingFileReceiver.kt` `copy()`
- 原实现：`part.renameTo(target)` 失败时 `part.copyTo(target, overwrite = true)`。
- 为什么是问题：复制到正式名不是原子的；磁盘满 / IO 错误会留下半截 `target`，而「接收文件」卷会把它当完整文件展示。
- 修法：rename 失败直接抛 `IOException`，由 catch 删掉 `.part`；不产生半截正式文件。

### 1.2 连续两次打开共用同一个 `.part`

- 位置：`IncomingFileReceiver.kt` `copy()`
- 原实现：`part = File(dir, target.name + ".part")`。
- 为什么是问题：`handle()` 取消旧协程后立刻启动新协程；旧协程可能还阻塞在 `read()`，两个协程会写同一个 `.part`，旧协程的 `catch` 还会删掉新协程的进度。
- 修法：`.part` 名加入代际号（`<name>.<gen>.part`）；rename 撞名时重新取一个不冲突的名字再试一次。

### 1.3 保存到窗格 / 压缩包路由缺少异常兜底

- 位置：`app/.../ui/preview/PreviewScreen.kt` `saveIncomingToCurrentPane()`；`app/.../ui/AppRoot.kt` 压缩包分支。
- 为什么是问题：入队或 `openArchiveInPane` 同步抛异常时，点击回调/组合协程没有兜底，用户只看到「没反应」或页面异常。
- 修法：`runCatching` + 状态条明确文案；保存入队成功/失败分别提示。

## 2. 结构优化

### 2.1 `LaunchedEffect(incoming)` 被 Copying 进度反复重启

- 位置：`AppRoot.kt`
- 原实现：key 是整个 `IncomingState`，复制期间每 200ms 变一次，effect 反复取消/重启。
- 修法：key 只取终态（`Ready` / `Error` / null），Copying 只驱动进度对话框重组。

## 3. 死代码清理

### 3.1 删除未消费的 `AUTHORITY_CACHE`

- 位置：`LocalVolumes.kt` / `LocalVfs.kt` / `LocalVfsIncomingAuthorityTest.kt`
- 原实现：先做 `cache` 卷，后改为只暴露 `cache/incoming` 的 `incoming` 卷；`cache` 映射已无消费方。
- 修法：删除 `AUTHORITY_CACHE` 与对应 3 条测试；只保留 `AUTHORITY_INCOMING`，并新增「不能反解到整个 cacheDir」回归。

## 4. 验证

- 全仓 `testDebugUnitTest`：**802 例**全绿（新增 `IncomingNamesTest` 6、`IncomingCacheTest` 5、`LocalVfsIncomingAuthorityTest` 3）。
- `:app:assembleDebug`：通过；APK `versionCode=121 / versionName=2.4.3`。
- `:app:lintDebug`：**0 error**（48 条既有 warning）。
- `git diff --check`：通过；未发现 token / keystore / 明文口令。
- 合并 Manifest 与 APK 内 Manifest 均确认包含两个 `ACTION_VIEW(content://)` filter。

## 5. 仍未验证（需真机）

- 系统选择器能否看到 PanelFM、各类型预览分派、`onNewIntent` 二次打开；
- 大文件取消后 `.part` 清理、同名 ` (1)`、缓存 7 天 / 512 MB 清理；
- 「接收文件」卷、保存到当前窗格、自环排除。

完整清单见 [`OPEN-WITH.md`](OPEN-WITH.md) §5。

# 顶部路径栏 / 路径跳转 · 变更审计（2026-10-31）

范围：v2.1.5「顶部面包屑」+ v2.1.6「文件定位」两次改动的合并审计。
代码：`BrowserPathBar.kt` / `PathInput.kt` / `BrowserControllerNav.kt` / `BrowserModels.kt` 等。

## 结论

交互主链路（点层级跳转 / 当前级菜单 / 长按复制 / 输入解析 / 压缩包面包屑）可重放；
审计中发现并修掉 2 个**会直接导致跳错或定位失效**的问题，另有 3 处补强。全量单测与编译通过。

## 🔴 修复

1. **加载后定位竞态（既有问题，搜索结果也受影响）**
   - 旧实现：`reveal()` 先 `open(parent)` 再写 `selection/scrollToUri`。`open` 的 `load` 是异步的，
     PaneView 拿**旧目录的列表**找不到目标就把 `scrollToUri` 消费掉；新列表回来时已无定位请求。
   - 现在：`PaneState.revealUri` 先记目标，`load` 成功后才由 `applyPendingReveal(items)` 落成
     `selection + scrollToUri`；失败分支清空请求。单测 `RevealAfterLoadTest`。

2. **连接号覆盖（同协议不同主机 / 同主机不同账号）**
   - 旧实现只看 scheme：在 SFTP 里输入 `sftp://另一台主机/…`，或目标 URI 自带 `?c=` 时，
     连接号仍取当前标签页的，`SessionLocator` 先按错误连接号命中会话。
   - 现在 `pickConnectionId`：`local/archive → null`；显式 `c=` / URI 自带 `c=` 优先；
     同 scheme + 同 authority 才沿用当前连接；其余按目标反查。单测 `NavigationConnectionTest`。

## 🟡 补强

3. **文件路径跳转**：输入框粘文件路径（含 `a.zip!/dir/a.txt`）不再当目录打开报错，
   改为 `stat` 后打开所在目录并选中（`openResolvingFile` / `openArchiveAt`）。
   远端用 `resolveSession` 自动重连一次（与打开书签同口径）。

4. **压缩包书签可打开**：`archive://` 书签在下次启动时包未挂载，必然报「未连接」。
   `bookmarkTargetOf` 改为收藏压缩包所在宿主目录（嵌套包退到最外层），状态栏说明原因。

5. **压缩包内 `~`**：此前被当成普通目录名 `~`；现在与普通存储一致，表示包内根。

## 验证

- `:app:compileDebugKotlin` 干净（仅 `LocalClipboardManager` 既有废弃告警）。
- 全量 **665** 例单测通过；本批新增：
  - `PathInputTest` 29 例（解析 / 面包屑 / 完整路径 / 书签目标）
  - `RevealAfterLoadTest` 4 例（定位落点）
  - `NavigationConnectionTest` 7 例（连接号选择）

## 已知边界

- 目标被当前过滤条件排除时，只打开父目录、不选中该文件（不擅自清掉用户的过滤条件）。
- 远端路径跳转会先 `stat`（多一次往返）；慢网下会晚一点出现加载遮罩。
- 压缩包内的**文件**定位已支持；压缩包内的**硬链接 / 特殊条目**仍按普通条目处理。

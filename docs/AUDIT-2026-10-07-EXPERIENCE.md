# PanelFM 体验审计（交互 / 功能 bug 专项 · 2026-10-07）

> **范围**：用户**实际会碰到的交互与功能路径**有没有 bug —— 重点覆盖最近三个版本动过的地方
> （选择模型 v1.9.0、Office 预览 v1.8.x、编辑器 v1.6/1.7）与日常高频动作（长按菜单 / 动作条 /
> 删除 / 压缩 / 分享 / 解压 / 预览）。
>
> **基线**：`main` @ `6df42fc`（v1.9.0），工作树即本轮的修复。
> **方法**：逐条读代码 + 全仓 grep 取证 + 单测；**未跑真机**（手感 / 动画类问题不在本轮结论内）。
> **证据**：文件:行号一律可复核（附录 A 给了命令）。
>
> 与既有审计的分工：`AUDIT-2026-10-05-CODE-TRUTH.md`（正确性/死代码/测试有效性）、
> `AUDIT-UX-2026-10-05.md`（通用实用体验）、`SECURITY-REVIEW-2026-10-06.md`（安全）、
> `MT-ALIGNMENT-REVIEW-2026-10-06.md`（MT 对齐清单）——本文**不重复**它们已记录的问题。

---

## 0. 结论摘要

| 级别 | 条数 | 内容 |
|---|---|---|
| **P0（会误删 / 误操作）** | **2** | F1 长按菜单在「没有选择」时把目标退化成**整个目录**（v1.9.0 回归）；F2 文件列表里的压缩包「解压」接错函数 → 实际是**复制** |
| P1（功能不可用/别扭） | 1 | F3 Office 文档预览不重连网络会话 → 会话被回收后直接报「会话不可用」 |
| P2（体验不完整） | 1 | F4 多选分享只发出去一个文件 |
| 核对后确认无问题 | 7 组 | 见 §3（选择模型 / 导航清选择 / 标签关闭 / 顶栏动作条 / Office 拦截 / 编辑器 / 删除对话框计数） |
| 本轮未覆盖 | — | 见 §4（传输协议层、播放器手势、实机手感等） |

> 两个 P0 都集中在**「长按菜单」这一条路径**：v1.9.0 把「长按不再改选择」改对之后，
> 菜单里的选择型动作没有显式目标，退化成了「没有选择 = 整个目录」。这类"隐式目标"是本项目
> 最容易出高危 bug 的形状，已在本轮一并收敛（所有菜单动作都要求显式目标）。

---

## 1. 修复的问题

### F1（P0，v1.9.0 回归）长按菜单的目标项退化成整个目录 → **误删整个目录**

**现象**：长按任意文件（没有任何多选）→ 菜单里点「删除」/「复制」/「移动」/「压缩」/「复制到剪贴板」，
动作会作用于**当前目录的全部内容**，而不是长按的那一项。
其中「删除」最危险：确认框写的是「确定删除「A.zip」？」，实际把整个目录都删了。

**证据链**（v1.9.0 代码）：

- 长按不再改变选择（`PaneView.handleRowLongPress`，v1.9.0 起）；
- 菜单目标项当时写作 `picked = focused.selectedItems.ifEmpty { listOf(item) }`
  —— 但下游动作根本没用到 `picked`：
  - `"delete" -> deleting = item` → 删除对话框调 `controller.deleteSelected(focusSide)`
    → `targetSources(side)`：**没有选择时返回 `pane.items`（整个目录）**（`BrowserController.kt:950`）；
  - `"copy_to"/"move_to" -> copyToOther/moveToOther` → 同样走 `targetSources`；
  - `"compress"` → `compressToOther/compressHere` → 同样；
  - `"clipboard" -> copySelectionToClipboard` → 同样。
- v1.9.0 之前不会踩到：旧的长按会先把选择集设成 `{item}`（`longPressSelect`），
  于是 `targetSources` 恰好等于那一项 —— **v1.9.0 的"长按不改选择"把这个巧合拆了**。

**修复**：

1. 新增纯函数 `menuTargets(selection, pressed)`（`BrowserModels.kt:214`）：
   按下的项在选择集里 → 整个选择集；否则 → 只这一项（**永不返回空、也永不返回整个目录**）；
2. 长按菜单用它取目标，并把目标**显式**传给下游：
   `copyToOther(…, overrideSources)` / `moveToOther(…, overrideItems)`（确认框的条数/字节数也按目标算）/
   `deleting = picked`（对话框改收列表）/ `compressTargets` / `copySelectionToClipboard(…, overrideSources)`
   （`DualPaneScreen.kt:902/960/961/985/1526/1531`，`BrowserController` 各函数新增 `overrideSources`）；
3. 回归测试 `MenuTargetsTest`（5 例）：没有选择 / 按下的项不在选择集 / 在选择集 /
   同名不同目录不串味 / **目标永远非空**。

### F2（P0，老 bug）文件列表里的压缩包「解压到当前目录 / 单独文件夹」接错函数 → 实际是复制

**现象**：在文件列表里长按一个 `.zip` → 「解压到当前目录」→ 没有解压出内容，
而是把**压缩包自己**复制到了当前目录（生成「name (1).zip」）；若当时有别的选择集，
则复制的是那些项；没有选择时同样会命中 F1 的「整个目录」。

**证据**：

- 菜单把 `ACTION_EXTRACT_HERE` 接到 `controller.extractTo(focusSide, focused.uri)`；
  而 `extractTo` 的语义是**在压缩包内部**把选中的条目复制出去（`BrowserController.kt:1164`，注释写明），
  它只做 `TransferRequest(COPY)`；
- 正确函数是 `extractArchiveTo(item, destDir, ownFolder)`（`BrowserController.kt:1186`，
  「解压文件列表里的压缩包文件」，整包展开）；**同一个动作在「解压…」对话框里接的是对的**
  （`DualPaneScreen.kt:1338/1340`），只有菜单这条接线错了 —— 属于"两处实现分叉"。

**修复**：两个菜单（长按菜单 + 顶栏动作条的类型化动作）都改成
「文件列表里的压缩包 → `extractArchiveTo`；压缩包内部 → 仍用 `extractTo`」，
并保留 `extractToOwnFolder` 给"压缩包内部"分支。

### F3（P1）Office 文档预览不重连网络会话

`OfficeScreen.readCapped` 用的是 `container.locator.find(uri)`，会话被回收（切后台久了、
连过多台设备）后直接抛「会话不可用」；同一个应用里预览页用的是
`container.resolveSession(uri)`（自动重连）。**修复**：统一用 `resolveSession`（`OfficeScreen.kt:230`）。

### F4（P2）多选分享只发一个文件

`shareItem` 只处理单个 `FileMetadata`；多选后分享只把被长按的那一个发出去（MT 是把选中项一起分享）。
**修复**：新增 `shareItems`（`DualPaneScreen.kt:1883`）——一个文件走 `ACTION_SEND`，
多个走 `ACTION_SEND_MULTIPLE`（类型取共同 MIME，取不到用 `*/*`）；`shareItem` 保留为单文件包装。

---

## 2. 修复方式与验证

| 项 | 说明 |
|---|---|
| 代码 | `menuTargets` + 6 处 `overrideSources/overrideItems` + 删除对话框改收列表 + 压缩目标显式化 + 解压接线 + `shareItems` + Office 重连 |
| 单测 | 全量 **309 例全绿**（新增 `MenuTargetsTest` 5 例）；选择模型既有 24 例不受影响 |
| 静态 | `:app:compileDebugKotlin` / `assembleDebug` 通过；`lintDebug` **Error 0** |
| 未验证 | 真机上的实际点击链路（沙箱无设备）——§4 给了 5 条一分钟复核 |

---

## 3. 本次核对过、确认没问题的地方（防重复劳动）

| # | 项 | 结论 | 证据 |
|---|---|---|---|
| 1 | v1.9.0 选择模型三条语义（长按弹菜单 / 滑动选中 / 滑动连区间 / 点击不连选） | ✅ 与文档一致 | `MtSelectionTest` + `MtRowGestureTest`（24 例：MtSelection 11 + MtRowGesture 13） |
| 2 | 换目录 / 切标签 / 前进后退是否残留选择与锚点 | ✅ 都会清 | `openStamped` 与 5 处 tab 操作都走 `withSelectionCleared()` |
| 3 | 关掉最后一个标签会不会把 `tabs.first()` 踩空 | ✅ 有守卫「只剩一个不许关」 | `BrowserController.kt:657` |
| 4 | 顶栏动作条（复制/移动/删除/重命名/压缩/属性/分享）与选择集一致 | ✅ 只多选态出现，动作按选择集 | `TopActionItems`（`picked = focused.selectedItems`） |
| 5 | 删除对话框的文案计数与实际删除项一致 | ✅（本轮改成按目标列表显示） | `DualPaneScreen` 删除对话框 |
| 6 | Office 预览的 WebView 拦截（非 http(s) 返回 null / 资产归一化 / 主框架错误兜底） | ✅ 逻辑未回归（v1.8.1 修法仍在） | `OfficeScreen.OfficeWebViewClient` |
| 7 | 编辑器：行操作 CRLF 归一/还原、重复灌文本防护、查找过期结果丢弃 | ✅ | `EditorScreen.applyWholeTextOp` / `appliedVersion` / `requestId` |

---

## 4. 尚未覆盖 / 需要实机确认

**未覆盖**（要么有专项测试且本轮未重跑，要么依赖真机/真实协议）：
传输引擎与断点续传、冲突对话框、回收站、远程管理 HTTP 服务、SFTP/FTP/SMB/S3/WebDAV 真实协议交互、
媒体播放器手势、PDF/字体/APK 预览、设置项持久化、桌面小组件类入口。

**建议的一分钟实机复核**（对应本轮修复）：

1. **长按一个文件 → 删除**：确认框写「确定删除「xxx」？」，确认后**只有它**消失（不是整个目录）；
2. **长按一个文件 → 复制 / 移动 / 压缩 / 复制到剪贴板**：只作用于它（对面窗格/剪贴板里只有它）；
3. **长按一个 `.zip` → 解压到当前目录**：内容真的被解压出来（不是多出一个 `xxx (1).zip`）；
4. **多选 3 个文件 → 长按其中一个 → 分享**：分享面板里是 3 个文件；
5. **网络盘上的 `.docx`**：先放一会儿（会话可能被回收）再点「文档预览」，应能自动重连打开。

---

## 5. 后续修复（v1.9.2，用户实机反馈）

用户实机反馈两条，均在本轮修复（都不是 §1 那四条，属于"本文未覆盖到"的两处）：

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| F5 | **解压报错/闪退**（长按压缩包 → 解压） | `extractArchiveTo` **没有先挂载压缩包**就构造 `archive://` 根 URI 入队；`SessionLocator` 对 `archive://` 只查"已挂载"（`archiveOf`）→ 计划阶段报「源位置不可用」；且整段跑在 `container.scope.launch` 里、异常无兜底（该 scope 无异常处理器 → 可能直接崩） | 入队前 `openArchive(item.uri)` 挂载；整段包 try/catch（取消除外）→ 任何失败都变成状态栏一句可读的话 |
| F6 | **docx 预览空白** | 沙箱无法复现（无 WebView）→ 按"让失败可见 + 不让用户看空白"处理：全局 `error`/`unhandledrejection` 进状态栏；渲染后检测**空壳**（无 section / 无文字无图）→ **纯文本兜底**（解 `word/document.xml` 抽段落）；`inset` 简写换显式偏移（老 WebView 不认 `inset`，容器会没尺寸）；去掉没有依据的 `experimental: true` | 见 `assets/office/viewer.js`（每步 `[OfficePreview]` 日志会转发到 logcat） |

> 教训（写进 §5 同款）：**"渲染成功但内容为空"没有任何异常**，只靠 catch 兜不住 ——
> 必须显式检查输出，并给一个"最差也能看"的降级路径。

## 附录 A：复核命令（本文结论可一键重现）

```bash
cd /workspace/panelfm-android

# F1：菜单目标规则 + 显式目标
grep -n "menuTargets" app/src/main/kotlin/com/u707t/panelfm/ui/browser/BrowserModels.kt
grep -n "overrideSources = pickedUris\|overrideItems = picked\|deleting = picked" \
  app/src/main/kotlin/com/u707t/panelfm/ui/browser/DualPaneScreen.kt

# F1：targetSources 的「没有选择 = 整个目录」语义（这就是必须显式传目标的原因）
grep -n -A3 "private fun targetSources" app/src/main/kotlin/com/u707t/panelfm/ui/browser/BrowserController.kt

# F2：解压接线（两处菜单 + 对话框）
grep -n "extractArchiveTo\|extractTo(" app/src/main/kotlin/com/u707t/panelfm/ui/browser/DualPaneScreen.kt
grep -n "fun extractArchiveTo\|fun extractTo" app/src/main/kotlin/com/u707t/panelfm/ui/browser/BrowserController.kt

# F3：Office 预览会话重连
grep -n "resolveSession\|locator.find" app/src/main/kotlin/com/u707t/panelfm/ui/preview/OfficeScreen.kt

# F4：多选分享
grep -n "ACTION_SEND_MULTIPLE\|fun shareItems" app/src/main/kotlin/com/u707t/panelfm/ui/browser/DualPaneScreen.kt

# F5/F6（v1.9.2）
grep -n "openArchive(item.uri)" app/src/main/kotlin/com/u707t/panelfm/ui/browser/BrowserController.kt
grep -n "renderDocxTextFallback\|unhandledrejection\|docxLooksEmpty" app/src/main/assets/office/viewer.js
grep -n "statusQueue\|statusDurationMs" app/src/main/kotlin/com/u707t/panelfm/ui/browser/BrowserController.kt

# 回归测试
./gradlew testDebugUnitTest --tests '*MenuTargetsTest' --tests '*StatusDurationTest'
```

*审查人：AI（只读源码 + 单测；未跑真机。修复已随 v1.9.1 发布）*

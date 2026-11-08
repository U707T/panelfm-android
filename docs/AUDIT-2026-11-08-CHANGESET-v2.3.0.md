# v2.3.0 变更审计（2026-11-08 · 传输任务 UI 收口）

> 范围：本地未推送的 v2.3.0（11 文件 / +475 −178）以及它直接依赖的路径 ——
> `core/transfer` 快照层、`ui/tasks`、`ui/browser` 任务条与侧拉栏计数。
> 方法：按「改到哪审到哪」做五轴检查；其余模块沿用 `AUDIT-2026-11-07-FULL.md` 的结论，
> 不重复开单，也不假装重读过。
> 结论：**没有阻断性问题**。v2.3.0 自己引入了一条「状态不可见」的回归（🟡1），
> 连同四条款式 / 口径问题一起在 v2.3.1 修掉。

## 分诊（本次改动路径）

| 模块 | 等级 | 依据（文件/行数/结构特征） | 重点 |
|---|---|---|---|
| ui/tasks | 🟡 | `TasksScreen` 300 行 + 新增 `TaskVisuals`；键值行模板化，但状态分支仍散在卡片里 | 状态可见性 / 空值 |
| ui/browser（任务条） | 🟡 | `TaskRow` 110 行卡在 174 行文件里；行内 5 个元素 → 2 行 + 贴底进度 | 状态可见性 / 布局 |
| core/transfer（快照） | 🟢 | `TransferModels` 174 行纯数据 + 纯函数；`TransferTask.toSnapshot()` 单一出口 | 保持不动 |
| ui/browser（侧拉栏 / 顶部计数） | 🟡 | 两处各自数 `isActive` 当「进行中」 | 口径一致 |

## 🔴 阻断性问题

无。

## 🟡 建议修复（本版已修）

| # | 问题 | 位置 | 为什么是问题 | 修法 |
|---|---|---|---|---|
| 1 | 暂停 / 取消中的任务在任务条上**没有状态词** | `BrowserActionBars.kt` `TaskRow` 的 `detail` | v2.3.0 把「暂停 / 取消」换成图标后，行尾只剩百分比：暂停的任务看起来仍在跑，正在取消的任务完全没有提示（旧文字按钮至少写着「暂停」） | `detail` 覆盖 `Paused` / `Cancelling`：`已暂停 · 1/3 项 · 282.82 M / 949.47 M`；新增纯函数 `barDetail()` 以便单测 |
| 2 | **没有保留帧也画进度条** | `TaskRow.showBar`、`TaskCard` 的进度条判断 | 「排队中就被暂停」的任务 `retainedProgress = -1`，`overallProgress()` 夹成 0 → 画出一条 0% 空槽，读起来像「传输被重置」 | 暂停 / 取消中只在 `retainedProgress >= 0f` 时画；任务条与任务页同口径 |
| 3 | 任务页暂停卡片**丢了字节数** | `TasksScreen.TaskCard` | v2.2.x 的副标题里有统计，v2.3.0 重排成键值行后只剩一句「已暂停」，用户看不出停在哪 | 暂停 / 取消中补「进度」行（`retainedStats`，不沿用速度 / 剩余时间 —— 上一帧残影） |
| 4 | 「进行中」把排队 / 暂停 / 冲突 / 取消中全算进去 | `SideDrawer`、`TasksScreen` 顶栏 | 暂停全部之后标题仍写「2 个进行中」，与用户看到的状态矛盾 | 计数改用 `TaskState.Running`；无在传但有未完成时显示「已暂停 / 等待中」 |
| 5 | 引擎注释与实现相反 | `TransferEngine.startWorker` | 注释说「暂停的任务会一直占着并发位（run() 挂在闸门里）」，那是 v2.2.5 之前的实现；照它改代码会把已修的 bug 写回去 | 按现状重写：`checkpoint()` 抛 `PauseSignal` → `run()` 返回 → 槽位立刻释放 |
| 6 | 「正在统计…」阶段显示 `0/0 项` | `TransferTask.statsLine()` | 统计未完成时 `total = 0`，项数被无条件拼进副标题，是个假值 | `total > 0` 才写项数；运行中 / 暂停共用同一个 `statsLine()` |

## 🔵 可选优化（未改，留档）

- **28dp 图标按钮低于 48dp 无障碍触控下限**（`TaskRowActions`）：为对齐 MT 的紧凑行高刻意压小；
  要改需同时动行高与「双任务并排」的总高度，等真机看完再定。
- `TaskRow` / `TaskRowActions` 里 `Done` / `Cancelled` 分支不可达（`forBrowserBar()` 已过滤）：
  防御性代码，暂留，避免将来放开「任务条显示完成项」时静默失效。
- `fromLabel` 只取第一个源：多选跨目录复制时「从」只代表第一个源（MT 进度块同样只显示一个，
  不算缺陷，记录语义边界）。
- 侧拉栏条目进度是 `isActive` 的平均值：排队任务记 0 会拉低平均（视觉上可接受，未改）。

## ✅ 做得好的地方

- 状态视觉收口到 `TaskVisuals`（`accentColor` / `statusLabel` / `trailingLabel`），任务页与任务条
  不再各写一份 `when (state)` 配一套颜色 —— v2.3.0 之前同一个状态两处颜色不一致。
- 暂停保留帧的思路正确：`retainedProgress` 是**展示层复用的最后一帧**，没有伪造字节数，
  也没有把 `Paused` 硬编码成某个百分比。
- 进度条贴底 + 卡片化确实解决了「和底栏分不开」的问题；`ThinProgressBar` 增加 `trackColor`
  而不是在卡片里另画一个进度条，复用得干净。

## 未覆盖（明确没读）

- `ui/preview`（MediaScreen 986 行）、`core/vfs-archive`（ArchiveVfs 1058 行）；
- `core/vfs-*` 各协议实现；`ui/tools` / `ui/editor` / `ui/connections`。
- 以上与本轮改动无交集，沿用 `AUDIT-2026-11-07-FULL.md` 的结论。

## 验证

- `./gradlew testDebugUnitTest --offline`：**730 例全绿**（新增 `TaskBarLabelTest` 6 例）；
- `./gradlew :app:lintDebug --offline`：**Error 0**（72 条 warning/hint 均为既有）；
- `assembleDebug` 通过；
- **仍未做真机验证**：沙箱无 `/dev/kvm`、`adb` 起不来。建议真机重点验：
  ① 暂停后任务条是否写着「已暂停」且进度条停在原位；
  ② 「暂停全部」后标题与侧拉栏不再显示「N 个进行中」；
  ③ 双任务并排时任务条总高度、深色主题下卡片与底栏的分界。

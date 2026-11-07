# 本轮修复：传输服务 / 引擎（2026-10-21 · 未发版）

> 范围：`service/TransferService.kt` · `core/transfer/TransferEngine.kt` · `AppContainer.kt`（服务启停收集器）
> 验证：`./gradlew testDebugUnitTest --offline` 全模块 → **599 例 / 0 失败**。

## 修复清单

| # | 问题 | 修复 |
|---|---|---|
| 🟡1 | 服务级 scope 无异常兜底：通知协程一挂，前台状态冻住或进程崩溃 | 挂 `CoroutineExceptionHandler`（`Logx.e` 后继续运行）；`onDestroy` 显式 `stopForegroundCompat()` |
| 🟡2 | `TransferService.start()` 静默吞启动失败，任务无前台服务硬跑 | 返回 `Boolean`；`AppContainer` 在失败时 `showStatus` 一次（同一批任务不重复提示），并保持 `foreground=false` |
| 🟡3 | `watchAutoDismiss` 每任务一个协程，移除任务时取消不掉 | `watchers: ConcurrentHashMap<id, Job>`；`remove` / `clearFinished` 一并取消；完成时自动清理句柄 |

## 记录未动

- 🔵 暂停任务占用并发位（`TransferTask.pause()` 注释与总文档已留档的取舍）。
- 🔵 通知进度只显示第一个活动任务；标题已写「共 N 个任务」，够用。

## 下一批

`core/common`（1,746 行，全项目共享工具，此前没有单独批次）：`readBounded` 的「连续 0 读」、
`Throttle` 的线程假设、`Fmt.count` 的进位。

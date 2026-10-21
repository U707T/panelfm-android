# 本轮修复：浏览器对话框（2026-10-21 · 未发版）

> 范围：`ui/browser/Dialogs.kt`（1,023 行）拆成 6 个文件 · `BrowserModels.planCompressTargets/typedExtractPath`
> 验证：`:app:testDebugUnitTest` → **599 例全绿**（新增 2 例：压缩文件名收敛、解压路径点段归一化）。

## 修复清单

| # | 问题 | 修复 |
|---|---|---|
| 🟡1 | 压缩文件名 / 解压目标路径把 `..`、`/` 原样拼进 VFS 路径 | `sanitizeArchiveFileName()` 只取叶子名；压缩框检出分隔符 / `.` / `..` 时禁用确定并提示；`typedExtractPath()` 做段级 `.` / `..` 归一化，上跳到根为止 |
| 🟡2 | 冲突对话框 `remember(sourceName, destName)`：不同目录同名文件沿用上一次选择 / `applyAll` | `ConflictInfo` 增加全局唯一 `token`（`TransferTask` 用 `AtomicLong` 生成），对话框按 `token` 重置 |
| 🟡3 | 12 个不相关对话框挤在 1,023 行 | 拆成 6 个文件（见下表），同包内移动，调用点零改动 |
| 🔵4 | 文件夹属性统计到 5 万项静默截断 | `FolderSummary.truncated`，界面显示「… 以上（已达 5 万项上限）」/ `N+` |
| 🔵5 | 权限对话框 `remember` 无键，换文件不重新取初值 | `remember(fileName, initialMode)` |
| 🔵6 | 口令错误重开时保留旧输入 | `LaunchedEffect(wrong)` 清空并重新聚焦 |

## 拆分结果

| 文件 | 行数 | 内容 |
|---|---|---|
| `DialogsTransfer.kt` | 124 | 移动确认、同名冲突 |
| `DialogsInput.kt` | 189 | 文本输入、确认、消息、`InfoRow` |
| `DialogsProperties.kt` | 158 | 属性面板 + 文件夹统计 |
| `DialogsActions.kt` | 170 | `MtAction` / 动作面板 |
| `DialogsArchive.kt` | 394 | 口令 / 压缩 / 解压 |
| `DialogsPermission.kt` | 118 | 权限九宫格 |

## 下一批

`service/TransferService` + `core/transfer`：服务级 scope 的异常兜底、任务事件收集、前台服务生命周期与取消。

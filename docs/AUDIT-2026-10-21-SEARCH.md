# 本轮修复：搜索链路（2026-10-21 · 未发版）

> 范围：`core/common/FileSearch.kt` · `ui/browser/BrowserControllerSelect.kt`（`searchTree`）·
> `BrowserDialogHost.kt` 搜索段 · `BrowserDialogsState.kt` · `SearchDialog.kt` · 新增 `SearchPolicy.kt`
> 依据：本文件对应 2026-10-21 审计（搜索链路此前从未进入 §1–§12 任何批次）。
> 验证：`./gradlew :core:common:testDebugUnitTest :app:testDebugUnitTest --offline` → **587 例全绿**（含新增 11 例）。

## 修复清单

| # | 问题（审计结论） | 修复 | 回归 |
|---|---|---|---|
| 🔴1 | `limit=300` 与 `confirmEvery=300` 撞在一起，「继续搜索」点了也停在 300 条 | 新增 `SearchBudget`：确认后 `limit = maxOf(limit, count) + step`；无询问通道时置 `capped` 而非静默截断 | `SearchPolicyTest` 3 例 |
| 🔴2 | 搜索中重搜：旧协程取消后仍写 `searching = false`，新搜索在根目录被自己的「已停止」判定掐断 | `searchGeneration` 代次隔离（`@Volatile`，`isCancelled` 在 IO 线程读）：旧代次不写任何 UI 状态；`onPartial` / `onAskContinue` / 收尾统一门禁 | 手工场景（慢目录重搜）+ 代码路径复核 |
| 🟡3 | `vfs.list` / 内容读取失败被吞成「空目录 / 未命中」 | 根目录失败直接抛出（会话不可用可见）；子目录失败 `failedDirs`、内容失败 `contentFailures`，结果窗逐条显示 | 结果窗新增失败行 |
| 🟡4 | 内容搜索把 `size == -1`（未知）当成「太大」跳过 | `contentScanWorthReading()`：只有**已知** >2MB 才跳过；未知大小按 512KB 读上限尝试 | `SearchPolicyTest` 1 例 |
| 🟡5 | 内容搜索无总字节预算；取消只在目录边界检查 | `maxContentBytes = 64MB` 总预算；每个文件、每 64KB 读块都查 `isCancelled()` | 代码路径复核 |
| 🟡6 | glob / 正则每个文件名现编译；`compareBy` 每次比较重算 `rank` | `FileSearch.compile()` → `Matcher`（一次编译）；命中时算好 `rank` + `lowerName`，快照只排序；glob 保持**整串匹配**（`Regex.matches`，不走 `containsMatchIn`） | `FileSearchTest` 等价性 3 例 + `PaneFilterTest` 通配/正则/否定 1 例 |
| 🔵7 | `parseSizeText` 把 `10KB` / `-5` 静默当成「不限制」 | 返回 `Long?`：`null = 非法`；支持 K/KB/M/MB/G/GB；非法时禁用「确定」并标红；`min > max` 同步拦截 | `SearchPolicyTest` 3 例 |
| 🔵8 | `searchJob` 放 Compose state（多余重组）；`searchAsk` 取消后残留；边搜边「清除」停不下来 | Job 改普通字段；`onStop` / 新搜索 / 清除都复位 `searchAsk` 并使代次失效 | 代码路径复核 |

## 行为变更（有意）

- 结果超过 300 条：确认后继续累积；**不会**再出现「确认了却仍是 300 条」。
- 搜索失败不再显示「没有匹配的项」：根目录失败弹 Vfs 错误；部分目录失败在结果窗显示数量。
- 手动「停止搜索」的提示文案与「数量过多」分开（<300 条时不再误报「数量过多」）。

## 下一批

工具页（`NetToolboxScreen.kt` / `TextToolboxScreen.kt`）：Ping 状态旗标无 `finally`、字符串工具箱逐字节 `String.format` 在主线程、
HTTP HEX 视图主线程格式化、`OkHttpClient` 每次进入页面新建且不释放。

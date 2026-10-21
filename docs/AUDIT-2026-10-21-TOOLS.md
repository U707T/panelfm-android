# 本轮修复：工具页（网络 / 字符串）（2026-10-21 · 未发版）

> 范围：`ui/tools/NetToolboxScreen.kt` · `ui/tools/TextToolboxScreen.kt`（含两个 Kit）
> 验证：`./gradlew :app:testDebugUnitTest --offline` → **588 例全绿**（工具页 17 例，其中新增 1 例）。

## 修复清单

| # | 问题 | 修复 |
|---|---|---|
| 🟡1 | Ping 的 `running` 无 `finally`：写历史失败就永久停在「Ping 中…」 | 整个 launch 体 `try/finally`；历史写入 `runCatching` 隔离；异常路径顺带 `destroy()` 在途进程 |
| 🟡2 | 字符串工具箱 16 个操作同步跑在主线程，且逐字节 `String.format` / `substring` | 十六进制查表 + `StringBuilder`（HEX/URL/Unicode/摘要/解码全部）；`apply` 改 `withContext(Default)` + `busy` 禁用 chips |
| 🟡3 | HTTP 的 HEX 视图在主线程逐字节格式化 16KB | `hexDump` 改查表 + `StringBuilder`，输出格式逐字符不变（现有格式测试护住） |
| 🟡4 | `OkHttpClient` 每次进页面新建且不释放 | `onDispose` 里 `evictAll()` + `executorService.shutdown()` |
| 🔵5 | 只有读超时：慢速滴流可让请求永不结束 | `.callTimeout(30s)` |
| 🔵6 | 失败状态用灰色 | 「请求失败 / 已取消」用 error 色 |
| 🔵7 | Ping 目标以 `-` 开头会被当参数 | `isPingTargetValid()` + 提交前提示（新增单测） |

## 有意保留

- `decodeBody` 只显示前 256KB、HEX 只显示前 16KB：上限明确且状态行标注「已截断」，不是静默截断。
- `TextToolboxKit` 的 Base64 解码仍按 UTF-8 尽力而为（二进制内容本来就不属于这个工具的目标）。

## 下一批

连接表单 / 网关：编辑模式读库失败会被当成「新建」（保存会插重复行）；`basePath` 跨协议残留（WebDAV → S3 时
变成隐藏的对象前缀）；`loadSecret` 仍在主线程；SMB「共享名」与「起始路径」两份状态无一致性校验；
`ConnectionEditScreen` 主 Composable 740 行 / 28 处协议分支。

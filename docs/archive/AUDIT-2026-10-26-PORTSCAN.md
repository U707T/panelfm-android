# 端口探测：闪退修复 + 并入局域网扫描（v2.1.2）

> 范围：`ui/tools/NetPortPane.kt`（删除）、`ui/tools/NetToolboxScreen.kt`（减一个页签）、
> `ui/connections/LanScanScreen.kt`（重写）、新增 `core/common/PortScan.kt`、`core/common/LanScanner.kt`（瘦身）
> 验证：`./gradlew --offline :core:common:test` → 通过；
> `./gradlew --offline :app:compileDebugKotlin` → 成功（仅剩 6 处既有的 `LocalClipboardManager` 废弃告警）；
> `./gradlew --offline :app:testDebugUnitTest` → **624 例全绿**（新增 `PortScanTest` 8 例）。

## 一、闪退是怎么来的

端口面板（v2.1.0 新增）有三条独立路径能把进程带走，每一条都单独成立：

| # | 位置（原 `NetPortPane.kt`） | 机制 | 后果 |
|---|---|---|---|
| 🔴1 | `job = scope.launch { try { … } finally { … } }` | `rememberCoroutineScope()` 用的是**组合期上下文**，它没有 `CoroutineExceptionHandler`（全仓只有 `AppContainer.scope` 挂了 `crashGuard`，见 `AppContainer.kt:64`）。这里只有 `finally`，没有 `catch` | 协程体内任意一处抛出 = 未捕获异常直上线程默认处理器 = **整个 App 闪退** |
| 🔴2 | `ports.forEach { port -> launch { … } }` + `NetToolboxKit.parsePorts` | 上限只按**单段区间 2048** 算，多段可拼到 65535（`1-2048,2049-4096,…`）。一次点击 = 6 万多个协程 + 6 万多个待办探测 | 低端机 **OOM 闪退**；侥幸不 OOM 也会在末尾连续全量排序时 ANR |
| 🔴3 | `socket.getInputStream().bufferedReader().readLine()` | `readLine()` 无长度上限：对端不换行地灌数据就一直往 StringBuilder 里堆 | 探一个会推流的服务就 **OOM**（`LanScanner.probe` 里是同一份代码，soTimeout 800ms 也挡不住） |

🟡4 补刀：`parsePorts` 在 `remember(portText)` 里**每次按键**都在主线程展开区间，输入 `1-65535` 会有一次
6 万次循环的卡顿。

## 二、改后结构

```
core/common/
  PortScan.kt     新增：端口解析（全局上限）/ 单目标探测 / 有界 worker 池扫描（Flow<Event>）/ 文本导出
  LanScanner.kt   只留网段枚举（localPrefixes / hosts），socket 代码整体删除

app/ui/tools/
  NetToolboxScreen.kt   三页签（Ping / DNS / HTTP）
  NetPortPane.kt        删除
  NetToolboxKit.kt      移除 CommonPorts / parsePorts / portLabel / PortResult / portSummary

app/ui/connections/
  LanScanScreen.kt      重写：网段 / 单机 两种模式 + 端口多选与预设 + 结果可建连接、可复制、可存盘
```

依赖方向依旧单向：面板 → 控件 → 引擎；`PortScan` 不依赖 Compose / Android，可直接 JVM 单测。

## 三、逐条修复

**🔴1 未捕获异常** —— `LanScanScreen.start()` 里 `collect` 外面套三层：
`catch (CancellationException) { throw }` → `catch (Throwable) { status = "失败：…" }` → `finally { scanning = false }`。
另外引擎侧 `PortScan.probe` **本身不抛**（超时 / 拒绝 / `UnknownHost` / `EMFILE` 全部收成 `open=false`），
双保险。

**🔴2 协程扇出** —— `PortScan.scan` 换成「固定 worker 池 + 有界队列」：
`Channel(capacity = 64)` + `concurrency.coerceIn(1, 128)` 个 worker 循环取任务。
内存只与并发数相关，与用户填了多少端口**无关**；填错只会慢，不会把 App 撑死。
端口总数也加了**全局**上限 `MAX_PORTS = 256`（`parsePortsDetailed` 返回 `truncated`，界面明说「只取前 N 个」），
业务层再按 `MAX_PROBES = 8192` 校验，超了直接拒绝并解释原因。

**🔴3 无上限 banner** —— `PortScan.readBannerHead`：只做**一次** `read()`，`ByteArray(128)`，
遇到 `\r` / `\n` 截断，只保留 `0x20..0x7E`（二进制 / 非 UTF-8 噪声不进结果行）。
再坏也只是「banner 少半截」，不会吃内存。`LanScanner.probe` 已删除，不再有第二份实现。

**🟡4 主线程解析** —— 端口解析仍在 `remember(portText)` 里缓存，但上限从 6 万降到 256，
最坏情况是一次 256 次循环（可忽略）；界面上「将探测 N 台 × M 个端口 = K 次」直接给出规模。

## 四、合并的交互设计

旧版是「网段 × 单端口」与「单机 × 多端口」两个入口，用户要先想「我该点哪个」；排查「连不上」时
这两步往往先后都要做。合并后一页两模式：

- **网段**（默认）：扫本机 /24，端口默认 `5244,22`（254 × 2 = 508 次，几秒出结果）；
- **单机**：目标是一个 IP / 域名（带输入历史，沿用 `NET_PORT_HOST` 记录键），端口默认 `22,80,443,445,3306,5432,6379,8080`；
- 端口：快捷胶囊是**多选开关**，另有 `常用端口 / Web / 数据库 / 远程 / 清空` 预设与任意 `1-1024` 区间；
  端口列表是一段大区间（或超过 32 个）时快捷胶囊自动禁用，避免把区间展开成一长串；
- 结果：`主机:端口 + banner + 耗时`，点一下按端口预选协议建连接（5244/80/443 → WebDAV、22 → SFTP、
  21 → FTP、445 → SMB），并支持「复制 / 存到当前窗格」；
- 模式切换时，端口列表若还停在**上一模式的默认值**就跟着切；用户手改过则不动（不做「猜你想」）。

## 五、有意保留

- 不做 1–65535 全扫：单次上限 256 个端口 + 8192 次探测。真需要全扫应该用专门的工具（手机发热且会被网络设备限速）。
- 不做 UDP 探测（DNS 53 / SNMP 161 这类探不到）：TCP connect 的结论才稳定，UDP 的「无应答」无法区分关闭与丢包。
- 单机模式下关闭的端口也入列表（由「只看开放」过滤）；网段模式只留开放项，否则 254 台 × N 端口没法看。

## 六、下一批可做

- 扫描结果导出 CSV（现在只有纯文本）。
- 网段扫描支持手动指定网段（当前只取本机 /24）。
- 端口探测历史（记住上次用的端口集合）。

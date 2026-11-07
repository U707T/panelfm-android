# 网络工具箱 v2.1.0 重构（2026-10-23）

> 范围：`ui/tools/` 下的网络工具箱全部文件（原 `NetToolboxScreen.kt` 754 行 → 拆 7 个文件）
> 验证：`./gradlew --offline :app:testDebugUnitTest` → **279 例全绿**（工具页 34 例，其中新增 17 例）；
> `./gradlew --offline :app:assembleDebug` → 三个 ABI 的 debug APK 均出包成功。

## 一、原状（v2.0.24）

`NetToolboxScreen.kt` 一个文件 754 行，装了两个工具：Ping 与 HTTP 请求。问题分三类。

**功能 Bug（会直接给出错误结论）**

| # | 问题 | 后果 |
|---|---|---|
| 🔴1 | HTTP 响应体一律 `String(bytes, UTF_8)` | GBK / Big5 页面整屏乱码；PNG / zip 刷满 U+FFFD |
| 🔴2 | Ping 的 `-w` 总超时固定 `4` 秒 | 次数改成 20 后仍 4 秒被 `ping` 自己掐断，看起来「只 ping 了几次」 |
| 🔴3 | 手写 `Host` / `Content-Length` 头 → OkHttp 抛 `IllegalArgumentException` | 整次请求失败，界面只有「请求失败：…」，看不出是哪个头 |
| 🟡4 | Ping 统计只存在原始输出里 | 丢包率 / 平均延迟要用户自己数 |
| 🟡5 | `HEAD` 也 `peekBody()` | 多等一次读超时 |
| 🟡6 | 响应体无法导出 | 这是个文件管理器，调试结果本该能存成文件 |
| 🟡7 | 缺 DNS 查询与端口探测 | 排查「连不上」缺的正是这两步，只能去用别的 App |

**结构问题**

| # | 问题 | 后果 |
|---|---|---|
| 🟡8 | 单文件 754 行、两个工具、两个 Kit 混在一起 | 加第三个工具必然继续膨胀 |
| 🟡9 | 每个工具的输入区 / 结果框各写一套 `Box + 0.35f 底色` | 参数漂移，页与页之间对不齐 |
| 🟡10 | 页签 `weight(1f)` 铺满整行且不限制单行 | 加到第三个页签时文字被挤成两行 |

## 二、改后结构

```
ui/tools/
  DnsKit.kt            350 行  纯逻辑：RFC 1035 报文编解码 + UDP 查询
  NetToolboxKit.kt     432 行  纯逻辑：Ping 参数与统计 / HTTP 头与响应体 / 端口表 / HEX / JSON
  NetWidgets.kt        337 行  共用控件：页签条 / 胶囊 / 统计条 / 结果框 / 进度 / 动作行
  NetToolboxScreen.kt  364 行  入口 + Ping 面板 + 存盘与数值输入公共件
  NetDnsPane.kt        337 行  DNS 面板
  NetHttpPane.kt       500 行  HTTP 面板
  NetPortPane.kt       339 行  端口面板
```

依赖方向单向：面板 → 控件 → Kit。Kit 与 DnsKit 不依赖 Compose / Android，可直接 JVM 单测。

## 三、逐项修复

**🔴1 响应体编码** —— 新增 `NetToolboxKit.decodeBodySmart`：先用 `looksBinary`（NUL 或控制字符 > 5%）
判二进制，二进制走 `hexDump`；文本交给已有的 `TextEncodings.decode`（UTF-8 / GBK / Big5 / UTF-16 / UTF-32，
带 BOM 识别）。返回 `BodyText(text, charset, binary)`，`charset` 显示在结果工具行。

**🔴2 Ping 总超时** —— 新增 `autoDeadlineSec(count) = count + 2`（夹取 3–120）。UI 上「总超时」默认跟随次数，
用户手改后停止跟随并给「恢复自动」按钮。次数 20 → 22 秒。

**🔴3 受限请求头** —— 新增 `splitHeaders`：`Host` / `Content-Length` / `Connection` / `Transfer-Encoding`
等由网络栈接管的头被剔除并**在状态行点名**（`已忽略受限头 Host/Content-Length`）；
展开请求头时实时提示哪些会被忽略（琥珀色）。`Content-Type` 单独交给 `RequestBody`，不算被拒。

**🟡4 Ping 统计** —— 新增 `PingStats`：逐行 `feed` 解析 `time=` 与 `N packets transmitted, M received`，
产出 收/发、丢包%、平均、最快、最慢、**抖动**（相邻 RTT 差值的平均绝对值）。UI 常驻统计条。

**🟡5 HEAD** —— `method == "HEAD"` 时跳过 `peekBody`，`HttpOutcome.bytes` 为空。

**🟡6/7 导出 + 新工具** —— 四个面板都接了 `saveToPane`（走 `container.uniqueChild` 的 `名称 (1).ext`
重名策略 + `VfsWriter`，失败时 `abort`）；新增 DNS 与端口两个面板。

**🟡8/9/10 结构** —— 拆成 7 个文件（见上）；共用控件抽到 `NetWidgets.kt`；页签条强制单行 + 省略；
动作行改 `NetActionRow`（横向可滚，窄屏下最后一个按钮不再被挤出屏幕）。

## 四、新增工具细节

**DNS 查询**：`DnsKit.buildQuery` 手拼报文（域名 label 编码、长度校验 63/253），`parseReply` 解析
带压缩指针的应答，压缩指针跳转上限 128 次（畸形/自指报文不死循环）。支持 10 种记录类型与任意服务器。
`normalizeQueryName` 把 `https://x/y` 剥成 `x`、把 IPv4 自动转 `in-addr.arpa`（PTR 最常被误用）。
「查全部」连查 A/AAAA/CNAME/MX/TXT/NS，任一失败不影响其它类型，最后统一汇总。

**端口探测**：`Semaphore` 控并发（默认 32，上限 128），逐条回调；开放端口读 banner（250ms 上限，
读不到不影响判定）。结果累积用 `Collections.synchronizedList` + 每 8 条刷一次 UI ——
旧思路（每条结果重建列表 + 重排）在 2048 端口时是 O(n² log n)，末尾会连续掉帧。

## 五、有意保留

- 端口探测不做 1–65535 全扫：`parsePorts` 单次上限 2048 个端口，超出部分静默截断（手机全扫会发热且被
  网络设备限速，真需要全扫应该用专门的工具）。
- DNS 只走 UDP：TCP 回退（应答 TC=1 时）没做，界面会明确提示「应答被截断」而不是假装成功。
- HTTP 响应体上限仍是 8MB（`peekBody`），超出在状态行与正文尾部双重标注。
- 「美化 JSON」对 > 512KB 的响应不提供：缩进要遍历全串，放在组合期会掉帧。

## 六、下一批可做

- DNS over TCP（TC=1 时自动重试）与 DoH。
- 端口探测的 UDP 模式（DNS 53 / SNMP 161 这类只有 UDP 的服务现在探不到）。
- HTTP 请求历史可回填整套请求（方法 + URL + 头 + 体），现在只回填 URL。
- `LocalClipboardManager` 在 Compose 新版已废弃（`LocalClipboard` 支持 suspend），全仓 6 处待统一替换。

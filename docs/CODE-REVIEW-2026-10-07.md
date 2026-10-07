# PanelFM 全仓代码复审（code-review-and-quality · 五轴 · 2026-10-07 夜）

> **范围**：全仓（`app` + `core/*`，约 3.5 万行 Kotlin）按 **正确性 / 可读性 / 架构 / 安全 / 性能** 五轴复审。
> **基线**：`main` @ `b6a0607`（v1.10.0，已发版；CI run 75 ✅ / Release v1.10.0 已带本次修复清单）。
> **方法**：逐轴抽查高风险路径 + 全仓 grep 取证 + **变异测试验证回归用例**（见 §2）；
> **未跑真机**（沙箱无设备）。
> **产物**：修复 4 处（F17–F20，随 v1.10.1 发布）+ 7 例新回归测试 + 本文档。

---

## 0. 结论摘要

| 类别 | 结果 |
|---|---|
| 修复（随 v1.10.1） | **F17** 远程路径 `+` 被解码成空格（正确性）；**F18** `forgetScroll` 死代码（可读性）；**F19** 播放器状态级位置刷新缺口（正确性·小）；**F20** WebView 显式加固 + 安全关键路径解析**零测试**缺口 |
| 回归测试 | 新增 `RemoteHttpPathTest` **7 例**（穿越拒绝 / 编码穿越 / `+` 语义 / 规整）；v1.10.0 的 `ExplicitTargetsTest` 经**变异测试**确认能抓回归 |
| 核对无问题 | 9 组（§3），含 zip-slip 防线、原子提交、断点续传 `.part` 语义、口令保险箱、日志无敏感信息 |
| 结构债（未改） | 3 个超大文件（§4）；安全 S1–S9 设计取舍项仍开放 |

---

## 1. 修复清单（证据链）

### F17（P2 · 正确性）远程管理：URL 路径里的 `+` 被解码成空格

- **现象**：文件名含 `+`（如 `a+b.txt`）时，浏览器访问 `/a+b.txt` 实际请求的是 `/a b.txt` ——
  若两个文件同时存在会**读错文件**，否则 404。
- **根因**：`RemoteHttpServer.handle` 用 `URLDecoder.decode(rawPath)`；`URLDecoder` 是**表单**语义，
  `+` = 空格（路径语义里 `+` 是普通字符）。
- **修法**：`decodeRemotePath()` 先把 `+` 转义为 `%2B` 再解码；函数提为顶层 `internal` 便于单测。
- **回归**：`RemoteHttpPathTest` 3 例覆盖解码语义（含 `%20` / `%2B` / 坏编码回退）。

### F18（可读性 · 死代码）`BrowserController.forgetScroll` 零引用

- 全仓 0 调用（v1.9.x 刷新语义改为「保持滚动位置」后遗留）。**删除**。
- `ScrollMemory.forget` 保留：它是带单测（`ScrollMemoryTest`）的通用组件 API，与 `remember/recall` 成对。

### F19（P3 · 正确性）播放器：状态级变化时位置不刷新

- 上一版把「250ms 全程轮询」改为 `Player.Listener` + 仅播放中 500ms 轮询后，
  READY / ENDED / seek 完成等**非播放中**的瞬间，`positionMs` 会落后半拍（进度条观感）。
- **修法**：`onPlaybackStateChanged` 里补一次 `positionMs` 刷新（一行，不动轮询结构）。

### F20（P3 · 安全加固 + 测试缺口）

- `OfficeScreen` WebView：已有 `allowFileAccess=false` / 无 JS 桥；**再显式**关掉
  `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs`（纵深防御，页面全走拦截器同源 https）。
- `RemoteHttpServer` 的**安全关键**路径规整（`..` 拒绝）此前**没有任何单测** → 补齐 4 例：
  直接穿越 / 编码穿越（`%2e%2e`）/ 根内回退 / 重复斜杠与点段规整。

---

## 2. 变异测试（验证回归用例真的能抓回归）

对 v1.10.0 新增的 `explicitTargets` 做变异：把 `override ?: fallback()` 改成
`override?.takeIf { it.isNotEmpty() } ?: fallback()`（即「空显式目标回退整个目录」的回归形态）。

- 结果：`ExplicitTargetsTest > 显式目标为空列表时不得回退成整个目录` **FAILED** ✅ → 用例有效；
- 随后从备份还原（`git diff` 为空，确认无残留）。

> 结论：v1.9.1 那类「参数加了、函数体忘了用」的静默回归，从此有测试可拦。

---

## 3. 核对无问题（防重复劳动）

| # | 路径 | 要点 | 结论 |
|---|---|---|---|
| 1 | **zip-slip 防线**（解压 / 重写压缩包） | `ArchiveVfs.normalize` 拒绝绝对路径与 `..`；`ZipEditor.safeEntryName` 同样拒绝并处理 `\`、NUL；`ArchiveVfsTest` 已有危险条目用例 | ✅ |
| 2 | **原子提交**（本地保存 / 压缩包回写） | `LocalVfs.commit` 用 `ATOMIC_MOVE`，不支持时退化为「删目标+改名」且删除失败即报错；不再有「先删后改」窗口 | ✅ |
| 3 | **断点续传 / 取消** | 可续传目标保留 `.part` + 断点记录；不可续传目标 `abort()` 清半成品；取消不误报失败 | ✅ |
| 4 | **口令保险箱** | Android Keystore AES-256-GCM + 每条独立 IV；`put` 失败返回 false（UI 会提示）；`get` 异常返回 null 不崩 UI | ✅ |
| 5 | **日志与密钥** | 全仓无 `GlobalScope` / `Thread.sleep`；日志无口令 / token / 凭据；仓库无密钥材料（`key.properties`/`*.jks` 均被忽略） | ✅ |
| 6 | **远程 HTTP 其余面** | 越界拒绝 + HTML 转义 + 响应头注入过滤 + 并发上限 + socket 超时 + 只读 | ✅（S1 的「无鉴权/绑全接口」为设计取舍，仍开放） |
| 7 | **Office WebView** | 全离线（拦截器只服务 `/doc/current` 与 office 资产）、无 JS 桥、非 http(s) 请求交回 WebView（v1.8.1 教训不回退） | ✅ |
| 8 | **预览失败态家族** | 图片 / 文本 / PDF / APK / Office 均有 ErrorState 路径；字体失败态已于 v1.10.0 补齐 | ✅ |
| 9 | **传输并发限流** | `TransferEngine` 原子占额度、支持运行中调整；worker 崩溃只记日志不拖垮队列 | ✅ |

---

## 4. 结构债（本轮不改，记录待办）

| 文件 | 行数 | 建议 |
|---|---|---|
| `ui/browser/BrowserController.kt` | 2297 | 后续按「传输 / 搜索 / 压缩包 / 常用操作」拆分（有 330+ 单测兜底再动） |
| `ui/browser/DualPaneScreen.kt` | 2277 | 按「顶栏 / 底栏 / 对话框编排 / 辅助组件」拆分 |
| `ui/preview/MediaScreen.kt` | 1883 | 把 `VfsDataSource` 一族抽到独立文件（纯搬移，可先做） |

> 原则（复审结论）：**先补回归测试再拆**，拆分为纯搬移、单独提交；不在功能批次里混重组。

---

## 5. 未覆盖（如实）

- **真机**：媒体手势、远程管理实机访问、WebView 渲染 —— 沙箱无设备；
- **协议层重跑**：SFTP / FTP / SMB / S3 / WebDAV 的网络与错误路径（前有专项审计，本轮 grep + 静态核对）；
- **安全审计 S1–S9**：设计取舍项（远程鉴权 / 签名分离 / 供应链 / 信任自签等），见 `SECURITY-REVIEW-2026-10-06.md` §6。

---

## 6. 复核命令（复制即用）

```bash
cd /workspace/panelfm-android
B=b6a0607   # 复审基线（v1.10.0）

# F17：旧语义（表单解码会把 + 变空格）与修复后语义
python3 - <<'PY'
from urllib.parse import unquote_plus, unquote
print('旧:', unquote_plus('/a+b.txt'))   # -> /a b.txt（错）
print('新:', unquote('/a+b.txt'))        # -> /a+b.txt（对）
PY
./gradlew :app:testDebugUnitTest --tests '*RemoteHttpPathTest'

# F18：死代码已删除
grep -rn "forgetScroll" app/src || echo "0 引用 ✅"

# F19/F20：改动点直读
grep -n "onPlaybackStateChanged" -A 6 app/src/main/kotlin/com/u707t/panelfm/ui/preview/MediaScreen.kt | head -12
grep -n "allowUniversalAccessFromFileURLs" app/src/main/kotlin/com/u707t/panelfm/ui/preview/OfficeScreen.kt

# 变异测试（可选复跑）：破坏 explicitTargets 后跑用例应 FAILED
# sed -i 's/override ?: fallback()/override?.takeIf { it.isNotEmpty() } ?: fallback()/' \
#   app/src/main/kotlin/com/u707t/panelfm/ui/browser/BrowserModels.kt
# ./gradlew :app:testDebugUnitTest --tests '*ExplicitTargetsTest'   # 应失败
```

*复审人：AI（只读审计 + 本地单测/构建；修复随 v1.10.1 发布，未跑真机）*

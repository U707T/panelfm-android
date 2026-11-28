# v2.5.12 「定位此文件」实现与修复记录（2026-11-28）

> 前置记录：`docs/AUDIT-2026-11-27-FIXES-v2.5.10.md`（43 条正式问题）、
> `docs/AUDIT-2026-11-28-FIXES-v2.5.11.md`（两项入口补齐）。
> 本文记录新功能「定位此文件」及其实现后审计的全部发现与修复。

## 范围

用户需求（仿 MT 管理器）：其他应用通过「用其他应用打开 / 分享」把文件交给 PanelFM 时，
定位到该文件在设备上的真实位置。版本 `2.5.12`（versionCode 135）。

## 功能实现

### 定位优先（`ACTION_VIEW`）

其他应用「用其他应用打开」→ 先尝试把 `content://` 反查成真实路径：

1. `DocumentsContract.getDocumentId` → `raw:` / `primary:` / `home:` / `XXXX-XXXX:` 四类 document id；
2. `MediaStore._data` 列（需「所有文件访问」，Manifest 已有）；
3. 都不成立 → 放弃，降级为复制。

反查成功且在可浏览卷内 → 双窗格跳到所在目录并选中原文件，**不复制**。
受保护路径（`Android/data`、`Android/obb`、`/data`、`/system`）与查不到路径的，
仍复制到 `cache/incoming/` 再走近原有预览链路。

### 分享面板（`ACTION_SEND` / `ACTION_SEND_MULTIPLE`）

Manifest 新增两个 filter（只接 `content://`）。多文件时列出「收到 N 个文件」清单，
可逐项定位 / 打开；失败的项单独列出原因，不吞掉。

### 其他入口

- 预览页菜单新增「定位到文件」（任何本地文件都能跳回列表并选中）。
- 设置新增「外部打开时定位原文件」，关掉后完全保持旧行为。

## 新增/改动的代码落点

| 文件 | 职责 |
|---|---|
| `app/.../open/ContentPathRules.kt`（新） | 纯函数：document id → 路径、规范化、可见性、路径 → 卷 |
| `app/.../open/ContentPathResolver.kt`（新） | `DocumentsContract` / `MediaStore` 反查，任何失败只降级 |
| `app/.../open/IncomingState.kt`（新） | 状态机（含 `Located` / `Batch`），不依赖 Android 类型 |
| `app/.../open/LocatePolicy.kt`（新） | `decideLocate` / `batchResultOf` 判定 |
| `app/.../open/IncomingFileReceiver.kt` | SEND 入口、定位优先、批量复制、`preferLocate` 开关 |
| `app/.../MainActivity.kt` | SEND 接线 + 设置就绪门控 |
| `app/.../ui/AppRoot.kt` | `Located` 定位、`Batch` 选择框、`locateInBrowser`、未保存编辑守卫 |
| `app/.../ui/preview/PreviewScreen.kt` | 「定位到文件」菜单（含 `onBack()` 回列表） |
| `app/.../ui/settings/SettingsScreen.kt` | 「外部打开时定位原文件」开关；`SettingsSwitchRow` 加可选 `subtitle` |
| `core/data/.../PrefsStore.kt` | `locateIncomingOriginal`（默认 true）+ setter + 映射 |
| `app/src/main/AndroidManifest.xml` | `ACTION_SEND` / `ACTION_SEND_MULTIPLE` filter |

## 实现后审计（`audit-panelfm-20261127/audit-locate.md`）与修复

审计查出 9 项，全部在提交 `38c8d17` 修复：

### 编译阻断（2 项，已确认会让 `:app:compileDebugKotlin` 失败）

1. `IncomingFileReceiver` 的 `Result.onFailure` lambda 参数是 `Throwable`，传给
   `friendlyMessage(Exception)` 类型不匹配 → 签名放宽为 `friendlyMessage(Throwable)`。
2. `PreviewScreen` 调用跨包的 `internal fun BrowserController.revealIn` 但未 import →
   补 `import com.u707t.panelfm.ui.browser.revealIn`。

### 行为与安全（4 项）

3. `ContentPathResolver` 先调 `DocumentsContract.isDocumentUri(null, uri)` →
   该方法内部 `context.getPackageManager()` 对 null 必然 NPE，被 `runCatching` 吞掉后
   **document id 反查在生产中从未执行**（等于功能主路径全废）→ 改为直接
   `runCatching { getDocumentId(uri) }`，不需要 Context，语义等价。
4. `copyForBatch` 漏掉 `copy()` 的空间预检、逐块取消、连续零读保护 →
   分享多个大文件会写满应用私有目录，点取消也要等整项写完 → 四项保护全部补齐。
   同时把批量任务登记进 `active`，否则 `cancel()` 对批量路径完全失效。
5. `handleSend` 忽略 `resumePersisted`，配置变化重建会整批重复复制（产生 `a (1).pdf`）→
   用「同一批 URI 指纹」做进程内幂等去重。
6. `AppRoot` 的 `Batch` 单文件快捷路径跳过了「预览页可能有未保存修改」的确认，
   `stack = listOf(Screen.Browser)` 会把编辑页移出组合 → 复用 `pendingLocate` 守卫。

### 应修（3 项）

7. `preferLocate` 初值 `true` 在设置流首读前存在窗口，用户「关掉开关 + 冷启动」会被误定位 →
   初值改 `false`（保守 = 旧行为），并由 `AppContainer.settingsReady` 门控
   `MainActivity` 的 intent 处理（1.5 s 超时兜底，不把用户卡在启动画面）。
8. 预览页「定位到文件」只改 browser 状态不离开预览页，用户看不到结果 →
   定位后 `onBack()` 回列表。
9. `.part` 临时名用 `generation.get()` 而非本项 gen，并发/重建会撞名 →
   `copyForBatch` 接受 `gen` 参数并使用它。

## 安全边界（document id 来自外部应用，按不可信输入处理）

- `normalizeAbsolutePath` 消掉 `..`，**爬出 `/` 直接拒绝**。
- `primary:` / `home:` / 卷 id 解析后必须仍在**所属卷内**（`confined()`）——
  否则 `primary:../../data/x` 会变成 `/storage/data/x`，借 document id 跳到别的卷。
  此漏洞在实现过程中由单测发现并修复。
- 任何反查异常只记日志并降级，**绝不影响收文件本身**。

## 验证

- 纯函数层：`ContentPathRulesTest` / `ContentLocateDecisionTest` / `LocatePolicyTest`
  **36 项通过**（脚本 `audit-panelfm-20261127/run-locate-tests.ps1`）。
  覆盖越界拒绝、卷内上跳允许、`vol:` 编码与 `LocalVfs.absolutePath` 互逆、
  受保护目录降级、开关关闭降级、批量失败项保留、保守初值。
- PSI 语法检查：改动/新增 Kotlin 文件 0 错误；`git diff --check` 干净。
- 分支 CI `workflow_dispatch` run
  [46153283305](https://github.com/U707T/panelfm-android/actions/runs/46153283305)，
  SHA `511bd864e8a7d2b5f64de33a8658d400c5017b94`：`test` / `preview` / `android` 成功
  （`release` 在 dispatch 下按配置跳过，签名三 ABI + 16KB 校验通过）。
- 正式 main push run
  [46154142275](https://github.com/U707T/panelfm-android/actions/runs/46154142275)，
  同 SHA：`test` / `preview` / `android` / `release` **四个 job 全部成功**。
- Release [v2.5.12](https://github.com/U707T/panelfm-android/releases/tag/v2.5.12) 非预发布，
  tag 与 Release 均指向 `511bd864e8a7d2b5f64de33a8658d400c5017b94`。三 ABI 附件（GitHub API）：

  | 附件 | 字节数 | SHA-256 |
  |---|---:|---|
  | `PanelFM-release-arm64-v8a.apk` | 23,437,470 | `426b11022dad27d6b8626e911081fc0a1bd765f48551034b655c553580f6fca7` |
  | `PanelFM-release-armeabi-v7a.apk` | 23,435,546 | `c1ede1975185612c236c25e7af8f25e30c2a063bf98108fffc45405a28461368` |
  | `PanelFM-release-x86_64.apk` | 23,436,528 | `f4cc8c3d9638032ccb8f2793defefb0bd4ef089f2b77e7128e71cd703c16deda` |

## 验证边界

- **未做真机验证**。以下必须设备确认，CI 只证明编译、单测与打包链通过：
  - 文件管理器 / DocumentsUI 交来 `primary:` 文件能否定位（验证审计第 3 项已修）；
  - 微信 / QQ 分享（`Android/data` 内）是否落到「接收文件」副本；
  - 分享 3 个大文件时旋转屏幕是否仍产生双份副本（验证第 5 项）；
  - 分享大文件时点取消是否立即停止（验证第 4 项）；
  - 多选属性弹窗排版、书签路径粘贴后的跳转手感。
- `MediaStore._data` 在部分 ROM 上可能被拒绝，已按「查不到就降级」处理，未逐机型验证。
- 未做依赖漏洞专项；GitHub 仍提示默认分支 56 项 Dependabot 告警。

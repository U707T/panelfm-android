# 用其他应用打开 / 分享（ACTION_VIEW / ACTION_SEND）

> 状态：已实现（v2.4.3，2026-11-16）；v2.5.12 起增加「定位原文件」与分享面板入口。
> 目标：其他应用点「打开方式」或「分享」时能看到 **PanelFM**，选中后**定位到文件本身**。

---

## 1. 范围

### 做

- `ACTION_VIEW`（打开方式）+ `ACTION_SEND` / `ACTION_SEND_MULTIPLE`（分享面板），只接 **`content://`**。
- **定位优先**：先尝试把 `content://` 反查成真实路径，能定位就直接在双窗格跳到所在目录并选中原文件，**不复制**。
- 反查不到（微信等只给数字 id）或路径不可读（`Android/data`、`obb`）→ 复制到 `cache/incoming/`，再按类型分派：
  图片 / 音视频 / PDF / Office / Markdown / SQLite / 字体 / 文本编辑器走现有预览；
  **压缩包**回双窗格进入包内浏览，**EPUB** 进阅读器，**APK** 进 APK 信息。
- 多文件：列出「收到 N 个文件」，可逐项定位 / 打开；失败的项单独列出原因。
- 预览菜单「定位到文件」：任何本地文件都能跳回列表并选中。
- 复制进度、取消、2 GB 上限、缓存 7 天 / 512 MB 清理；预览菜单「保存到当前窗格」。
- 设置「外部打开时定位原文件」：关掉后完全保持旧行为（总是复制）。

### 不做（明确边界）

| 项 | 原因 / 后续 |
|---|---|
| `file://` | Android 7+ 已禁止；且允许构造私有路径。只接 `content://` |
| `ContentVfs` 直接流式读 | 要改图片/视频/PDF/Office/编辑器五条链路；大文件场景再评估 |
| 反查 `msf:` 等数字文档 id | Downloads provider 不暴露真实路径；已有「下载」卷可直接浏览，不做特判 |
| 进程被杀后恢复本次 intent | `onCreate` 只在 `savedInstanceState == null` 处理，避免配置变化重复复制 |

---

## 2. 链路

```
其他 App
  └─ ACTION_VIEW / ACTION_SEND(_MULTIPLE)(content://…, FLAG_GRANT_READ_URI_PERMISSION)
       └─ MainActivity.onCreate / onNewIntent
            └─ AppContainer.incoming.handle(intent)
                 └─ IncomingFileReceiver
                      ├─ ContentPathResolver  ← 新增：反查真实路径
                      │    ├─ DocumentsContract document id（raw:/primary:/home:/卷id）
                      │    └─ MediaStore _data
                      ├─ 能定位 → IncomingState.Located → 双窗格 revealIn（选中原文件）
                      └─ 不能定位 → 复制到 cache/incoming/<sanitized-name>
                           └─ IncomingState.Ready / Batch
                                └─ AppRoot: 按类型分派（压缩包 / EPUB / 其余预览）
```

### 为什么保留「复制」这条路

- `content://` 的读授权绑定在接收 Activity 上；`ContentResolver` 也不保证随机读。
- 微信等 App 把文件放在 `Android/data/<pkg>/`，Android 11+ 即使有「所有文件访问」也读不到 ——
  系统层面就不存在可定位的真实路径，只能给副本。
- 复制后就是一个普通本地文件，`local://incoming/...` 走 `LocalVfs`，现有预览全部原样复用。

---

## 3. 代码落点

| 文件 | 职责 |
|---|---|
| `app/src/main/AndroidManifest.xml` | `MainActivity` 的 `ACTION_VIEW` / `ACTION_SEND` / `ACTION_SEND_MULTIPLE` filter（只接 `content`） |
| `app/.../open/ContentPathRules.kt` | **纯函数**：document id → 路径、规范化、可见性、路径 → 卷 |
| `app/.../open/ContentPathResolver.kt` | 用 `DocumentsContract` / `MediaStore` 反查（任何失败只降级） |
| `app/.../open/IncomingState.kt` | 状态机（含 `Located` / `Batch`），不依赖 Android 类型 |
| `app/.../open/LocatePolicy.kt` | `decideLocate` / `batchResultOf`：定位还是复制的判定 |
| `app/.../open/IncomingFileReceiver.kt` | intent 解析、定位或复制、进度/取消、多文件 |
| `app/.../open/IncomingNames.kt` | 文件名清洗、扩展名补全、同名追加 ` (1)` |
| `app/.../open/IncomingCache.kt` | 7 天 / 512 MB / `.part` 残片清理 |
| `app/.../open/ExternalOpen.kt` | 应用内「外部应用 / 打开方式」排除 PanelFM 自身 |
| `app/.../MainActivity.kt` | `onCreate`（仅首次）/ `onNewIntent` 接线 |
| `app/.../ui/AppRoot.kt` | 复制进度对话框；`Located` 定位、`Batch` 选择框、按类型分派 |
| `app/.../ui/preview/PreviewScreen.kt` | 「保存到当前窗格」+「定位到文件」菜单 |
| `app/.../ui/settings/SettingsScreen.kt` | 「外部打开时定位原文件」开关 |
| `core/data/.../PrefsStore.kt` | `locateIncomingOriginal`（默认 true） |
| `core/vfs-local/.../LocalVolumes.kt` | `AUTHORITY_INCOMING`（「接收文件」卷） |

---

## 4. 定位的判定与安全边界

反查优先级：document id（`raw:` / `primary:` / `home:` / `XXXX-XXXX:`）→ `MediaStore._data` → 放弃。

| 结果 | 行为 |
|---|---|
| 可浏览路径（`/storage/emulated/0/**`、`/storage/XXXX-XXXX/**`） | 定位原文件，不复制 |
| 受保护（`Android/data`、`Android/obb`、`/data`、`/system`…） | 复制副本 + 定位副本 |
| 拿不到路径（数字 id、provider 拒绝、`_data` 不可读） | 复制副本 + 预览（旧行为） |

安全边界（document id 来自**外部应用**，必须按不可信输入处理）：

- `normalizeAbsolutePath` 消掉 `..`，**爬出 `/` 直接拒绝**。
- `primary:` / `home:` / 卷 id 解析后必须仍在**所属卷内**（`confined()`）——
  否则 `primary:../../data/x` 会变成 `/storage/data/x`，借 document id 跳到别的卷。
- 任何反查异常只记日志并降级，**绝不影响收文件本身**。

其余边界同旧版：文件名清洗、2 GB 上限、空间预检、不覆盖、`.part` 后 rename、缓存私有。

---

## 5. 验证

### 本地（无 Android 环境，纯函数层）

- `ContentPathRulesTest` / `ContentLocateDecisionTest` / `LocatePolicyTest`：**35 项通过**
  （脚本 `audit-panelfm-20261127/run-locate-tests.ps1`）。
- 覆盖：越界拒绝、卷内上跳允许、`vol:` 编码与 `LocalVfs.absolutePath` 互为反函数、
  受保护目录降级、开关关闭降级、批量失败项保留。
- 改动/新增 Kotlin 文件 PSI 语法检查 0 错误；`git diff --check` 干净。
- **未做** Android 编译（本机无 SDK），以 CI 为门禁。

### 待真机

- [ ] 文件管理器 / 浏览器下载的文件「打开方式」选 PanelFM → 定位到原文件所在目录并选中。
- [ ] 微信 / QQ 分享文件 → 出现在分享面板；收到后定位到「接收文件」副本（原路径不可读）。
- [ ] 多选分享 3 个文件 → 列出清单，逐项可定位；含 1 个不可读文件时该项失败原因可见。
- [ ] 设置关掉「外部打开时定位原文件」→ 行为回到「复制 + 预览」。
- [ ] 压缩包 / EPUB / APK 各一个：仍按类型走包内浏览 / 阅读器 / 信息卡片。
- [ ] 冷启动与 `onNewIntent` 各一次；复制中取消不留 `.part`。

---

## 6. 后续

1. `ContentVfs`：大视频/大文件流式播放，不再复制。
2. 批量「全部保存到当前窗格」。
3. 反查 `msf:` 数字 id（需要 Downloads provider 特殊 API，收益低）。

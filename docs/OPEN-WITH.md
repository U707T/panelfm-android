# 用其他应用打开（ACTION_VIEW）

> 状态：已实现（v2.4.3，2026-11-16）。
> 目标：其他应用点「打开方式」时能看到 **PanelFM**，选中后直接进入内置预览。

---

## 1. 范围

### 做

- `ACTION_VIEW` + `content://` + **单文件**。
- 复制到 `cache/incoming/`，再按类型分派：图片 / 音视频 / PDF / Office / Markdown / SQLite / 字体 / 文本编辑器走现有预览；
  **压缩包**（zip / 7z / rar / tar 系）回到双窗格进入包内浏览，**EPUB** 直接进阅读器，**APK** 进 APK 信息。
- 复制进度、取消、2 GB 上限、缓存 7 天 / 512 MB 清理。
- 预览菜单「保存到当前窗格」：把缓存副本交给传输引擎，存到当前焦点窗格的目录。
- 接收目录同时作为本地卷 **「接收文件」** 出现在主页「本地」与侧栏，用户可再次打开、复制、删除。
- 冷启动（`onCreate`）与已运行（`onNewIntent`）两种入口。

### 不做（v1 明确边界）

| 项 | 原因 / 后续 |
|---|---|
| `ACTION_SEND` / `ACTION_SEND_MULTIPLE`（分享面板） | 需要「接收列表」UI 与多文件落盘策略，单独一版做 |
| `file://` | Android 7+ 已禁止；且允许构造私有路径。只接 `content://` |
| `ContentVfs` 直接流式读 | 要改图片/视频/PDF/Office/编辑器五条链路；大文件场景再评估 |
| 「另存为…」可选目标目录 | v1 只做「保存到当前窗格」；选目录的完整另存为后续再加 |
| 进程被杀后恢复本次 intent | `onCreate` 只在 `savedInstanceState == null` 处理，避免配置变化重复复制 |

---

## 2. 链路

```
其他 App
  └─ ACTION_VIEW(content://…, FLAG_GRANT_READ_URI_PERMISSION)
       └─ MainActivity.onCreate / onNewIntent
            └─ AppContainer.incoming.handle(intent)
                 └─ IncomingFileReceiver
                      ├─ ContentResolver 查询 DISPLAY_NAME / SIZE / MIME
                      ├─ 复制到 cache/incoming/<sanitized-name>
                      └─ IncomingState.Ready(local://incoming/…)
                           └─ AppRoot: 按类型分派
                                ├─ 压缩包 → 双窗格 openArchiveInPane
                                ├─ EPUB   → PreviewMode.EPUB
                                └─ 其余   → PreviewMode.AUTO
                                     └─ 现有 PreviewScreen / MediaScreen / EditorScreen …
```

### 为什么复制，而不是新增 `content://` VFS

- `content://` 的读授权绑定在接收 Activity 上；`ContentResolver` 也不保证随机读。
- 直接接进 VFS 要在图片、视频、PDF、Office、编辑器五条链路各写一套 content 适配。
- 复制后就是一个普通本地文件，`local://incoming/...` 走 `LocalVfs`，现有预览全部原样复用。

代价：大文件要等复制。v1 用 2 GB 上限 + 进度 + 取消把边界说清楚；真要播 4 GB 视频再上 `ContentVfs`。

---

## 3. 代码落点

| 文件 | 职责 |
|---|---|
| `app/src/main/AndroidManifest.xml` | `MainActivity` 增加 `ACTION_VIEW` + `content`（带 `*/*` 与纯 scheme 两个 filter） |
| `app/.../open/IncomingFileReceiver.kt` | intent 解析、ContentResolver 查询、复制、进度/取消、代际号 |
| `app/.../open/IncomingNames.kt` | 文件名清洗、扩展名补全、同名追加 ` (1)` |
| `app/.../open/IncomingCache.kt` | 7 天 / 512 MB / `.part` 残片清理 |
| `app/.../open/ExternalOpen.kt` | 应用内「外部应用 / 打开方式」排除 PanelFM 自身 |
| `app/.../MainActivity.kt` | `onCreate`（仅首次）/ `onNewIntent` 接线 |
| `app/.../ui/AppRoot.kt` | 复制进度对话框；`Ready` 按类型分派；避免和「恢复上次预览」叠页 |
| `app/.../ui/preview/PreviewScreen.kt` | 缓存副本的「保存到当前窗格」菜单 |
| `core/vfs-local/.../LocalVolumes.kt` | 新增 `AUTHORITY_INCOMING`（「接收文件」卷，只暴露 `cacheDir/incoming`） |
| `core/vfs-local/.../LocalVfs.kt` | `incoming` → `cacheDir/incoming` 的 `absolutePath` / `uriOf` 映射 |
| `core/common/.../AppDirs.kt` | 新增 `incomingDir` 并在 `ensure()` 中创建 |

---

## 4. 安全与边界

- **只接 `content://`**：不解析 `file://`，避免被构造私有路径。
- **文件名不可信**：只取最后一段、清洗控制字符与 `<>:"|?*`、去掉尾部 `.`、保留扩展名截断到 120 字符、`..` 回退时间戳。
- **大小与空间**：2 GB 硬上限；已知大小时先查 `usableSpace`（预留 64 MB）；未知大小时边写边查。
- **缓存容量**：7 天 / 512 MB，最旧优先；**单文件超过 512 MB 时保留最新一份**，避免删掉刚收到的文件。
- **不覆盖**：同名追加 ` (1)`、` (2)`。
- **授权生命周期**：复制在收到 intent 后立刻开始；Activity 被销毁导致授权失效时，复制失败并给明确文案，不留半截正式文件（先写 `.part`，成功才 rename）。
- **缓存私有**：`cache/incoming` 在应用私有目录；`file_paths.xml` 已有 `<cache-path>`，后续「分享/外部应用」可直接用。
- **导出组件**：`MainActivity` 本来就 `exported=true`（launcher）；新增 filter 只接受 `content://`，不执行外部传入的 Intent/路径。
- **应用内自环**：`ExternalOpen.selfExclusion` 用 `EXTRA_EXCLUDE_COMPONENTS` 把 PanelFM 从「外部应用」选择器里排除。

---

## 5. 验证

### 已跑（沙箱）

- `:app:testDebugUnitTest`：**404 例**全绿（新增 `IncomingNamesTest` 6 例、`IncomingCacheTest` 5 例）。
- 全仓 `testDebugUnitTest`：**802 例**全绿。
- `:core:vfs-local:testDebugUnitTest`：**9 例**全绿（新增 `LocalVfsIncomingAuthorityTest` 3 例）。
- `:app:compileDebugKotlin`、`:app:assembleDebug`、`:app:lintDebug`：通过（0 error）。
- 合并后的 Manifest 已确认包含两个 `ACTION_VIEW` filter。

### 待真机

- [ ] 文件管理器 / 微信 / QQ 下载的文件 →「打开方式」能看到 PanelFM；PDF、图片、txt、mp4、apk 各一个，预览类型正确。
- [ ] zip / 7z / rar 各一个：进入包内浏览，而不是文本编辑器。
- [ ] EPUB：直接进阅读器；APK：APK 信息页。
- [ ] PanelFM 未运行（冷启动）与已在后台（`onNewIntent`）各走一次。
- [ ] 复制中按返回/取消：对话框消失，`cache/incoming` 不残留 `.part`。
- [ ] 大文件（> 2 GB）提示明确；缓存空间不足提示明确。
- [ ] 同名文件连续打开两次：生成 `a (1).pdf`，不覆盖。
- [ ] 从预览返回：回到进入前的页面（浏览器 / 首页 / 设置）。
- [ ] 杀进程后重开：不会因为旧 VIEW intent 再次复制/弹预览。
- [ ] 打开缓存文件编辑并保存：只改副本；来源 App 的文件不变。
- [ ] 预览菜单「保存到当前窗格」：副本出现在焦点窗格目录；压缩包窗格 / 只读窗格给明确提示。
- [ ] 主页「本地」与侧栏出现「接收文件」卷；进入能看到刚打开的文件。

---

## 6. 后续

1. `ACTION_SEND` / 多文件：接收列表 + 批量「保存到当前窗格」。
2. 预览菜单「另存为…」：可选目标目录（当前只支持保存到焦点窗格）。
3. `ContentVfs`：大视频/大文件流式播放，不再复制。

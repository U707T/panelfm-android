# v2.5.11 审计收尾记录（2026-11-28）

> 承接 `docs/AUDIT-2026-11-27-FIXES-v2.5.10.md`。v2.5.10 已修完 43 条正式问题；
> 本文记录审计报告里最后两项「成本很小的入口补齐」，其余内容不重复。

## 范围

审计报告 `最值得补齐的入口（可选）` 两节，共 2 项。版本 `2.5.11`（versionCode 134）。

## 修复内容

### 顶栏「属性」支持多选

- 位置：`app/src/main/kotlin/com/u707t/panelfm/ui/browser/BrowserActionBars.kt`、`BrowserTopBar.kt`。
- 问题：顶栏 `属性` 按钮 `enabled = picked.size == 1`，多选时禁用；已有的
  `MultiPropertiesDialog` 只能从长按菜单进入，选 20 个文件想看合计大小必须逐个点。
- 修复：`TopActionItems.onProperties` 由 `(FileMetadata) -> Unit` 改为
  `(List<FileMetadata>) -> Unit`，按钮改为 `picked.isNotEmpty()` 并直接调用
  `controller.showProperties(picked)`。`showProperties(List)` 本就存在且会在
  `size <= 1` 时退回单文件对话框，因此不需要新的状态或弹层。
- 行为：多选时给出「项目数（文件 / 文件夹）· 目录 · 总大小」，明确标注
  「不含 N 个文件夹」「另 N 项大小未知」，与长按菜单入口完全一致（复用同一实现）。

### 书签「复制路径」复制可用路径

- 位置：`app/src/main/kotlin/com/u707t/panelfm/ui/browser/BookmarkPanel.kt`。
- 问题：书签列表与「最近」列表的 `复制路径` 复制的是 `bookmarkRowSubtitle` 的**展示串**
  （形如 `dav@我的 NAS /Vedios`），既不是文件系统路径也不是可解析 URI，粘贴后无法跳转。
- 修复：新增纯函数 `bookmarkCopyText(uri, pathOf)`：
  - 本地 / 压缩包 → `pathOf(uri)`（真实文件系统路径，与列表显示同源，由 `pathDisplayOf` 提供）；
  - 网络 → `uri.toString()`（完整 `VfsUri`，保留 `?c=<连接号>`）。
  两处菜单（书签行、「最近」行）都改用它，展示串 `bookmarkRowSubtitle` 保持原样用于列表副标题。
- 行为：网络位置复制得到 `dav://nas/Vedios?c=7`，粘回路径输入框可还原到同一连接同一目录；
  同主机多账号不会因丢失连接号跳到别的会话。

## 验证

- 新增回归 `app/src/test/kotlin/com/u707t/panelfm/ui/browser/BookmarkPanelTest.kt`：
  本地复制为真实路径、网络复制为可还原 URI、网络复制与展示串不同、两个不同连接号互不串台。
- 生产纯函数隔离探针 [`probes/BookmarkCopyProbe.kt`](../../audit-panelfm-20261127/probes/BookmarkCopyProbe.kt)：
  原样提取 `bookmarkCopyText` / `bookmarkRowSubtitle`，配合真实 `VfsUri` 执行，输出
  `BOOKMARK_COPY_PROBE=PASS local=/storage/emulated/0/Download network=dav://nas/Vedios?c=7`。
  （`BookmarkPanel.kt` 含 Compose 代码，无法在纯 JVM 编译；探针只证明函数契约，
  Compose 接线与 Android 模块编译由 CI 覆盖。）
- 本地实跑：预览安全/兼容测试 4 项通过；项目纯 JVM 回归 15 项通过；`git diff --check` 干净；
  4 个改动/新增 Kotlin 文件 PSI 语法检查 0 错误。
- CI：分支 `workflow_dispatch` run
  [46140900296](https://github.com/U707T/panelfm-android/actions/runs/46140900296)，SHA
  `283e6193ac6b27e64d521d0b46d20c6a8ac36413`，`test` / `preview` / `android` 成功
  （`release` 在 dispatch 下按配置跳过）。正式 main push run
  [46141405456](https://github.com/U707T/panelfm-android/actions/runs/46141405456)，同 SHA，
  `test` / `preview` / `android` / `release` 四个 job 全部成功。
- Release [v2.5.11](https://github.com/U707T/panelfm-android/releases/tag/v2.5.11) 非预发布，
  tag 与 Release 均指向 `283e6193ac6b27e64d521d0b46d20c6a8ac36413`。三 ABI 附件（GitHub API）：

  | 附件 | 字节数 | SHA-256 |
  |---|---:|---|
  | `PanelFM-release-arm64-v8a.apk` | 23,420,986 | `65231e807bb487a838a05606b6a775c6e5f7978325af86cf4f997f890cd3c27f` |
  | `PanelFM-release-armeabi-v7a.apk` | 23,419,062 | `2efa8c0b42d22eb0aa94bc711afe11c861f7eda2bbe6e9314b6aa70b556a7016` |
  | `PanelFM-release-x86_64.apk` | 23,420,044 | `3bbd6bd3dca5cdb4acc1a16c4f5522e5734579da6090812084ece2b9f13c91b6` |

## 验证边界

- 未做 Android 真机验证：多选属性弹窗的实际排版、书签路径粘贴到输入框后的跳转手感
  都需要设备确认。CI 只证明编译、单测与打包链通过。
- 顶栏「属性」在多选时的对话框内容沿用既有 `MultiPropertiesDialog`；
  其「文件夹不递归统计、大小未知项不参与合计」的口径未在本版改动。

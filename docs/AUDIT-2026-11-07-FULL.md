# 全量审计（2026-11-07 · v2.2.4 → v2.2.5）

> 范围：全仓 14 个模块 / 186 个主源文件 / 44,044 行（另有 103 个测试文件 / 11,632 行）。
> 方法：先分诊（🔴 3 / 🟡 7 / 🟢 4），再按用户要求一次性给全景；本轮深读约 70% 行数，
> 其余在文末「未覆盖」里明确列出，**不把没读的当已审**。
> 结论：2 条 🔴 都不是“结构烂”，而是**失败路径上的顺序**——覆盖传输先删旧文件、
> 交换文件名回滚顺序写反。两条都只在异常时触发，触发时用户会以为文件丢了。

## 分诊表

| 模块 | 等级 | 依据（文件/行数/结构特征） |
|---|---|---|
| ui/browser | 🔴 | 36 文件 / 10,910 行；1 个控制器类 + 5 个扩展文件共享同一份 PaneState；52 处 ≥6 层缩进 |
| core/vfs-archive | 🔴 | 6 文件 / 2,155 行；`ArchiveVfs.kt` 1,058 行单类承担 6 种格式 + 索引缓存 + 增量写 |
| core/transfer | 🔴 | 6 文件 / 1,415 行；`TransferTask.kt` 840 行，`running()` 单函数 244 行 |
| ui/preview | 🟡 | 22 文件 / 6,655 行；视频链路 v2.2.4 刚收口，本批只做回归 |
| ui/tools | 🟡 | 9 文件 / 3,399 行 |
| ui/editor | 🟡 | 13 文件 / 2,846 行；`EditorScreen` 891 行 |
| ui/connections | 🟡 | 7 文件 / 1,833 行 |
| core/vfs 网络源 | 🟡 | s3 1,091 / webdav 851 / sftp 780 / ftp 629 / smb 601 |
| core/data + core/ui | 🟡 | data 1,123（`PrefsStore` 503）；ui 3,231（图标/组件表） |
| app 外壳 | 🟡 | `AppContainer` 474 + `AppRoot` 381 + `HomeScreen` 553 等 |
| core/common + vfs-api + vfs-local + model | 🟢 | 单文件最大 293 行，职责单一 |

## 🔴 1 覆盖传输先删旧文件（已修）

- 位置：`core/transfer/.../TransferTask.kt:575-576`（`resolveDest` 的 OVERWRITE 分支）
- 原实现：`if (existing.isDirectory || resumable == NONE) deleteForOverwrite(...)`。
  FTP / WebDAV / S3 都是 `resumable = NONE`，于是覆盖前先把旧文件删掉。
- 为什么错：这三种协议的 `openWrite` **本来就是临时区 + 原子落位**
  （`FtpVfs.kt:524` `.part`+RNTO、`WebDavVfs.kt:486` `.part`+MOVE、`S3Vfs.kt:378` multipart），
  失败时旧文件本来可以安然无恙。预删把「新旧都没了」的窗口重新打开 ——
  `FtpVfs.commit` 的注释甚至明写了「不做先删旧再重试」，引擎在上一层把它做掉了。
- 修法：只有 `existing.isDirectory`（文件覆盖目录，commit 无法替换）才预删。
- 回归：`不可续传目标：覆盖失败后旧目标文件仍在（不再预删）`（替换了原来钉「预删」语义的用例）。

## 🔴 2 交换文件名回滚顺序（已修）

- 位置：`app/.../BrowserControllerFileOps.kt:97-102`
- 原实现：第三步失败后 `rename(targetA, b.uri)` → `rename(tmp, a.uri)`。
  `targetA` 是刚失败的目标（不存在），`a.uri` 已被 b 占着（`LocalVfs.rename:190` 拒绝覆盖）——
  两次回滚必然都失败，a 留在 `.panelfm.swap.*`。
- 修法：先 `rename(targetB, b.uri)` 腾出 a 的原名，再 `rename(tmp, a.uri)`。
  失败文案补「若出现 .panelfm.swap.* 文件，改回原名即可」。

## 🟡 修复清单

| # | 问题 | 位置 | 修法 |
|---|---|---|---|
| 3 | 暂停的任务占用并发位（并发 2 时暂停两个就锁死队列） | `TransferEngine.kt:104`、`TransferTask.pause()` | `checkpoint` 抛 `PauseSignal` → `run()` 返回释放槽位；`resume()` 重新入队，从断点续传 |
| 4 | 选择集与列表脱节（「已选 5」实际只操作 1 个） | `BrowserModels.kt:117/129`、`BrowserTopBar.kt:195`、`load`/`setSearch`/`setFilter` | 新增 `PaneState.withSelectionPruned(items)`，三处入口统一夹紧（锚点一起收） |
| 5 | 过滤条件跨目录/跨标签/跨存储粘住 | `BrowserControllerNav.kt`（open/switchTab/closeTab） | 换目录、切标签、关标签时 `clearedFilter()` 并提示 |
| 6 | 批量重命名静默半成功（只报「完成 N/M」） | `BrowserDialogHost.kt:542` | 预检目标是否存在（冲突整批不执行并列名）；失败项带原因 |
| 7 | `.zst` 两套分类漂移 | `MimeTypes.kt:86` vs `ArchiveVfs.kt:125` | 从 `Kind.ARCHIVE` 移出；新增 `ArchiveKindConsistencyTest` 守住两份表 |

## 🔵 修复清单

- 快路径取消丢摘要 → 任务行 `已取消（已完成 N 项）`（`TransferTask.kt:326`）。
- 「解压到单独的文件夹」吞 mkdir 失败 → 显式提示（`BrowserControllerArchive.kt:191`）。
- 文件对比允许同一文件两次 → `distinctBy`（`BrowserControllerNav.kt:337`）。
- 删除无调用者的同步查库版 `defaultOpenMode()`。

## 跨模块观察

1. **VFS 顺序读桥接有 4 份**：`PreviewStreams.kt:41`、`MediaDataSource.kt:269`、
   `EpubScreen.kt:260`、`DiffScreens.kt:323`。取消/超时语义已经不完全一致。
   **本轮未动**：这不是缺陷，是重复；抽公共适配器会同时动四条预览链路，没有真机验证不值得。
2. **压缩包分类两份注册表**（已按 🟡7 收敛，并用测试钉住）。

## 未决风险（未修，记录在案）

- **S3 SigV4 签名**未按 AWS 规范逐字节核对（沙箱无网络、无官方向量），现有单测只用自造向量。
  需一次真实 S3/MinIO 联调才能收口。
- **`ResumeEntry.validator` 用 mtime 字符串**判断源是否变化：同一秒内改写、或 VFS 返回 -1 时
  仍有理论窗口（有 `total` 与 `.part` 长度双重校验兜底）。
- **等待冲突**的任务仍占用并发位（暂停已修，冲突未修）：对话框挂太久会挡住后续任务，
  用户可「取消」解除。彻底修法要持久化 waiter，风险高于收益。

## 未覆盖（明确没读）

- `core/vfs-smb/SmbVfs.kt`（572 行）只读了 rename 片段；
- `ui/browser` 的 `SideDrawer`(529)、`SearchDialog`、`Widgets`、`FolderDiff`、`TypeActions`、`Dialogs*`（除删除/批量重命名）；
- `ui/tools/TextToolboxScreen`(397)、`NetDnsPane`、`DiffScreens` 只看了桥接层；
- `ui/connections/ConnectionFormSections.kt`(451)、`ui/home/HomeScreen.kt`(553)、`ui/settings`；
- `core/ui` 的 `MtWidgets`(601) / `MtIcons`(686)；`androidTest`（本仓 0 个）、CI/Gradle 配置。

## 验证

- `./gradlew testDebugUnitTest --offline`：**721 例全绿**（app 365 + core 356；新增 6 例，
  改写 3 例旧语义用例）。
- `./gradlew :app:lintDebug --offline`：**Error 0**。
- 仍未做真机验证：沙箱无 `/dev/kvm`、`adb` 起不来。建议真机重点验：
  ① 覆盖传输中途断网/取消 → 目标旧文件仍在；
  ② 暂停两个任务后新建任务能跑；暂停后取消要落到「已取消」；
  ③ 过滤后换目录 → 条件已清。

## 附：v2.2.6（本轮修复的回归）

暂停让出槽位后，`cancel()` 仍按「run() 会在 checkpoint 收尾」处理，而任务已经不在 `run()` 里 ——
状态停在「正在取消操作…」。修法：`yieldedOnPause` 为真时直接落 `Cancelled`，并让 `run()` 的
暂停收尾在写状态前后双检 `gate.isCancelled`。回归 1 例。


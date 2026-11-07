# 本轮修复：core/vfs-archive（2026-10-21 · 未发版）

> 范围：`ArchiveVfs.kt` · 测试 `ArchiveVfsTest.kt`
> 验证：`./gradlew testDebugUnitTest --offline`（全模块）→ **597 例全绿**，archive 23 例（新增 3 例）。

## 修复清单

| # | 问题 | 修复 |
|---|---|---|
| 🟡1 | `list()` 合成目录仍对 `items` 线性查重（旧注释声称已修 O(n²)，实际只改了 `stat` 的 `dirPaths`） | 一次性 `HashSet` 去重 + 合并；删掉事后的 `distinctBy` |
| 🟡2 | TAR / 7z 重开条目只按 `name` 匹配：同名条目读到第一份内容；找不到时返回 EOF 流，解出空文件 | 匹配 `name + size`；找不到 `close()` 后抛 `VfsException.NotFound`（与 CPIO / AR / RAR 分支对齐） |
| 🟡3 | `buildIndex()` 全量扫描不可取消：大 tar.gz 建索引期间返回 / 切后台都停不下来 | `buildIndex` 改 `suspend`，各格式条目循环内 `currentCoroutineContext().ensureActive()` |

## 回归

- `tar 同名条目读到索引保留的最后一份`：旧实现读 `first`，现在读 `second`。
- `条目在挂载后消失时报 NotFound 而不是空文件`：挂载后原地重写压缩包再读，断言 `VfsException.NotFound`。
- `大量合成目录不重复且完整`：300 个合成目录，数量与唯一性同时断言。

## 记录未动

- 🔵 `readFullyAt` 每次从条目起点重新解压（目前只有测试调用；接入随机读前需要流缓存或明确禁用）。

## 下一批

`ui/browser/Dialogs.kt`（1,023 行 / 13 个对话框）：属性页同步取 VFS 数据、压缩/解压参数状态、密码
与格式切换的残留、输入对话框的重复实现。

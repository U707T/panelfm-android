# .preserved — 清理时抢救出来的未合并工作

本目录**不参与构建**，仅存放从被清理的临时位置抢救出来的补丁/说明。

## unmerged-batch2-fastpath-fallback.patch

来源：`/workspace/tmpwork/panelfm-batch2`（git worktree @ `f65f506`）中**未提交、也从未进入 main** 的改动。
该 worktree 于 2026-10-05 清理时发现，内容已抢救为补丁。

内容（3 个文件，+268 / -39）：

- `core/transfer/TransferTask.kt` — 服务端快路径失败时**降级到流式慢路径**
- `core/transfer/src/test/.../FakeVfs.kt` — 新增 `failServerSideCopy` / `failRename` 开关
- `core/transfer/src/test/.../TransferRegressionTest.kt` — 新增 4 项回归（20 用例全绿，main 为 16）

为什么重要：`VirtualFileSystem.serverSideCopy` 的接口注释写明
「不支持返回 false（**引擎降级为流式泵**）」，而 main 的 `TransferTask.kt:123` 把 `false` 直接判为
`failed += FailedItem(src, "服务端操作失败")` —— 违反接口契约。
`WebDavVfs.serverSideCopy()` 返回 `r.isSuccessful`，因此「WebDAV 复制到已有同名目标」会整批失败而非降级。

应用方式（在仓库根目录）：

```bash
git apply .preserved/unmerged-batch2-fastpath-fallback.patch
./gradlew :core:transfer:testDebugUnitTest --offline --console=plain
```

应用与否由项目负责人决定；详见 `docs/AUDIT-2026-10-05-CODE-TRUTH.md` §7 与 §8 批 F。

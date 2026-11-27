# v2.5.10 全量审计修复记录（2026-11-27）

> 对应完整审计报告：工作区 `audit-panelfm-20261127/PanelFM-全量审计报告-2026-11-27.md`（仓库外存档）。
> 本文只记录「改了什么、怎么验证、还有什么没验证」，避免 Release 说明与提交历史承载过多细节。

## 范围

按「文件保全 → 传输与中断恢复 → 数据完整性 → 交互与展示 → 性能与资源」修复，共 40 项
（高 8 / 中 21 / 低 11）。版本号 `2.5.10`（versionCode 133，main 连续迭代）。

## 修复摘要

### 文件保全与删除

- 重命名/解压/回收站识别「同一文件」与本地路径别名（`FileIdentity.kt`、`LocalVfs` 的
  `VfsFileIdentityResolver`）：源与目标指向同一实体时不再删除源。
- 创建压缩包前检查目标是否已存在（含同目录解压重名），避免无声覆盖；`ArchiveCompressor`
  统一走 `uniqueChild` 冲突命名。
- 回收站跨卷清理失败时保留可恢复副本并如实上报（`TrashService.moveToTrashDetailed`）；
  压缩包创建/解压失败路径补齐取消响应与资源释放。

### 传输（core/transfer）

- 覆盖已存在目标时快路径不再预删：冲突判断、覆盖、目录合并统一交给慢路径，
  同类型文件覆盖由临时文件 + `commit` 原子替换，服务端失败不再丢旧文件且冲突只问一次。
- 同目标目录的任务按目标实体串行（`DestinationDirectoryLocks`，每个引擎一份），
  避免两个并发任务共享固定 `.part` 名互相截断；互不相关的目标仍可并发。
- 多源 MOVE 暂停恢复：记录已成功搬走的源并重新规划剩余项，不再因 `NotFound` 整单失败。
- 暂停/取消立即关闭活动源流以打断阻塞读；HASH 校验与删源前补 checkpoint，
  取消可及时终止且不会提前删源。
- 部分失败保留明细与总数，任务以「完成（含失败项）」收尾：不自动消失，
  全部为「已复制但删源失败」时也提供重试入口（删除失败项单独补删源）。

### 连接、协议与远端

- S3：跨桶路由、分片完成签名、Unicode 路径编码；Range 读取委托到 `S3Range`，
  HTTP 调用集中到 `S3Calls` 并显式设置空闲超时。
- WebDAV：特殊字符路径编码、Range 偏移校验；`DavCalls` 统一 401/超时错误映射。
- SFTP/SMB/FTP：创建目录失败如实反馈、不再无谓递归建父目录；删除目录不跟随链接；
  FTPS 校验逻辑抽成可测接口（`TlsEndpointCheck`）。
- HTTP `DavHttp`：读写设置连接/读取空闲超时，避免协议卡死占用传输槽。

### 归档与预览

- ZIP 添加/完整性检查：遍历安全、取消响应、内容校验；7z 保留空目录；
  `ArchiveTraversal`/`ResourceOwnership` 收口路径边界与资源释放。
- 合并 APK：资源表保持不压缩并对齐（`ApkResourceAlignmentTest`），坏归档失败路径释放句柄。
- 预览预算：`PreviewLimits` 统一文本/图片/PDF 上限，`copyPreviewBounded` 与
  `PreviewLimitedInputStream` 防止越界读取；SQLite 查询格式校验（`SqliteFormats`）。
- 长图缩略图按像素与字节预算缓存；不完整属性统计明确提示而不是显示错误总数。

### 浏览、搜索与连接编辑

- 选择目标目录时保留原始操作源（修复「另存/解压到」用错源）；压缩包内上级返回宿主目录；
  递归搜索按项数与深度裁剪，隐藏高级条件不再被丢弃。
- 连接编辑表单：证书选项、WebDAV 特殊字符路径、清空密码均按表单实际值生效
  （`ConnectionEditState`/`WebDavUrl` 回归测试）。
- 文件选择弹窗、缩略图来源选择等交互补齐状态保持与取消路径。

### 缓存与资源

- 归档挂载复用需源版本（大小/mtime/etag）匹配；未知版本使用唯一键，坏下载不会被当成缓存命中。
- 下载先写唯一临时文件再原子改名（`writeAtomically`），并发/取消不会互相截断。
- 被替换的归档实例不立即关闭（后台读者可能仍持有），容器退出时统一回收。

## 验证

- 本地隔离编译：`core/transfer` 生产源码 + 全部测试（FakeVfs）编译通过。
- `core/transfer` 回归 51 用例全绿（含新增：同目标锁互斥、快路径覆盖失败保留旧目标、
  多源 MOVE 暂停恢复、HASH 取消不删源、部分失败重试入口）。
- 其余模块本地实跑：preview 64 / archive 122 / protocol 78 / ui-pure 6 / pure-jvm 15 /
  cache 5 / MtRowGesture 15 = 305 用例全绿（脚本与日志见审计目录）。
- PSI 语法检查：86 个改动/新增 Kotlin 文件 0 错误。
- GitHub Actions 首次全量 `workflow_dispatch`（run `46013665854`，SHA `965c80f`）：preview job 通过；`testDebugUnitTest` 在 `:app:compileDebugKotlin` 失败，发现回收站返回类型可见性与任务状态智能转换两处问题，已在 `bd5d708` 修复。第二次运行（run `46017452680`）主源码编译通过，但 `ThumbSourceTest` 因缺少 `assertTrue` 导入在测试编译失败，已在 `8b1b112` 补齐。两轮均跳过 android/release，不能视作门禁通过；最终提交仍须重新全量 CI。

## 已知限制（不在本版处理）

- Windows 工作区无 Android SDK，本地无法执行 Gradle/`testDebugUnitTest` 与整包构建；
  以 CI 为最终门禁。
- wuxianpi 仅覆盖控制通道；知乎/哔哩哔哩只有仓库级检查，无设备实测。
- 已替换的归档实例保留到容器关闭（为避免打断后台读者）；口令重试频繁时内存占用会随之增长。
- 版本不可靠（mtime ≤ 0 且无 etag）的源每次预览生成新物化文件，仅靠 3 天 sweep 清理。

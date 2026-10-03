# PanelFM（Android 原生 · Kotlin）

MT 管理器风格的**双窗格文件管理器**，Android 原生实现（Kotlin + Jetpack Compose）。
**只做「文件管理 + 预览」这一半**，不含任何逆向工程功能（不做 DEX / Arsc / APK 编辑）。

策划与架构文档：[`docs/策划.md`](docs/策划.md)（含完整接口草图、协议要点、里程碑与风险对策）

## 已完成（M0–M6，UI 对齐 MT 管理器）

- **双列浏览**：左右窗格完全独立——各自标签页、前进/后退历史栈、排序（名称/大小/时间/类型）、
  隐藏文件开关、焦点态、目录统计与可用空间；分隔条点击切换焦点。
- **底部命令栏**：`← → ＋ ⇄ ↑`（MT 布局）。`⇄` 菜单：复制到对面 / 移动到对面 / 同步路径 / 交换窗格 / 全选。
- **跨窗格操作**：目标恒为另一窗格当前目录（**不弹目标选择框**）；执行前**两侧路径栏高亮 + 中央文案**；
  移动带二次确认（项数、体积、是否跨存储中转）。
- **任务引擎**：队列 + 并发（1–4，可设）、进度/速率/剩余时间、暂停/继续/取消/全部暂停、
  冲突策略（覆盖 / 跳过 / 保留两者 / 每次都问 + 全部应用）、断点续传（本地偏移写、HTTP Range 读）。
- **界面（对齐 MT 管理器）**：浅色/深色跟随系统 + 一键切换；主页「本地 / 网络 / 工具」三段**可折叠**，
  本地项带**占用百分比条**（`xxx已用，yyy可用`）；双列页顶部 = ≡ + 路径 + `文件夹 / 文件 / 储存` + ⋮，
  底部 `← → ＋ ⇄ ↑`；⋮ 菜单 = 刷新 / 输入路径 / 搜索 / 全选 / 过滤（按类型）/ 排序方式 / 隐藏文件 / 添加书签 /
  设为首页 / 交换窗口 / 传输任务 / 设置 / 退出；≡ 菜单 = 快速位置 + 书签 + 局域网扫描。
- **协议**：
  | 协议 | 实现 | 关键能力 |
  |---|---|---|
  | 本地 | `java.nio` + `Os.stat` | 偏移读写、原子改名（`.part`）、POSIX 权限、容量、SAF 预留 |
  | WebDAV | 自研 OkHttp | PROPFIND / MOVE / COPY / Range 读 / 管道流式 PUT / 自研 307-308 保方法 / 自签信任 / 自定义 UA |
  | FTP · FTPS | commons-net | MLSD 优先 + LIST 回退、REST 断点续传、显式 AUTH TLS、隐式 990、PBSZ/PROT P、主动/被动 |
  | **SFTP** | **Apache MINA SSHD** | 密码 / 私钥（OpenSSH·PEM，ed25519·ecdsa·rsa）、**chacha20-poly1305 / curve25519**、**跳板机 ProxyJump**、主机指纹 TOFU、**双向偏移续传**、chmod、符号链接 |
  | **SMB2/3** | **SMBJ** | 自动协商 3.1.1→3.0.2→2.1、NTLMv2（域/工作组）、共享访问、**偏移读写**（断点续传 + 局域网流媒体）、服务端 rename、共享容量 |
  | **S3 兼容** | **自研 SigV4**（OkHttp） | AWS S3 / R2 / COS / OSS / MinIO：ListBuckets 一键选择、ListObjectsV2 分页、path-style、Range 读、**Multipart 8MB 分片**、服务端 CopyObject、自动 Content-Type、自定义下载域名；**签名与 AWS 官方测试向量逐字节一致** |
- **局域网扫描**：并发 TCP 探测 + banner 识别（SSH/FTP/SMB/WebDAV 端口），命中即可一键建连接。
- **预览**：文本（编码自动识别 UTF-8/GBK/UTF-16+BOM，可切换 Hex/图片）、图片、Hex（只读，前 8 KB 窗口）、
  外部应用打开（FileProvider）。
- **权限**：`MANAGE_EXTERNAL_STORAGE` 引导、**Android 17 `ACCESS_LOCAL_NETWORK` 局域网授权**（未授权会直接超时）、
  通知与前台服务声明。
- **持久化**：SQLite（连接配置 / 口令 Keystore AES-GCM 加密 / 续传状态 / 路径历史）+ DataStore（偏好）。

## 待办（按策划文档里程碑）

| 里程碑 | 内容 |
|---|---|
| ~~M4~~ | ~~SFTP~~ ✅ 已完成（含跳板机 + 局域网扫描 + 双向偏移续传） |
| ~~M5~~ | ~~SMB2/3~~ ✅ 已完成（SMBJ，DFS 关闭避免 JNA 依赖） |
| ~~M6~~ | ~~S3 兼容对象存储~~ ✅ 已完成（自研 SigV4 + Multipart + 自定义下载域名） |
| M7 | 文本编辑器（**可编辑保存**、大文件窗口化、语法高亮、查找替换）、字体预览、媒体播放（Media3 + VfsDataSource） |
| M8 | 压缩包（zip/7z/tar）挂载、解压、压缩（**不含 APK 内部浏览**；Hex 仅保留只读查看，不做编辑） |
| M9 | 缩略图管线、拖拽落点语义（目录=移动 / 文件行=复制）、目录差异对比、回收站、通知栏进度、S3 分片续传落库 |
| M10 | 性能基线（Macrobenchmark）、无障碍、错误文案统一、发布硬化 |

## 工程结构

```
panelfm-android/
├─ app/                      UI（Compose）+ 应用容器（手工 DI）
│  └─ ui/{home,browser,tasks,settings,connections,preview}
├─ core/
│  ├─ common/                格式化 / 编码识别 / 调度器 / 目录
│  ├─ model/                 ConnectionConfig / SortSpec / ConflictPolicy
│  ├─ vfs-api/               VirtualFileSystem / VfsUri / VfsReader·Writer / 能力位 / 会话注册表
│  ├─ vfs-local/             本地文件系统（偏移读写 + 原子改名 + POSIX 权限）
│  ├─ vfs-webdav/            自研 WebDAV（PROPFIND / MOVE / COPY / Range / 管道流式 PUT / 307-308）
│  ├─ vfs-ftp/               FTP / FTPS（commons-net）
│  ├─ transfer/              计划器（快路径判定）+ 任务引擎 + 暂停闸门 + 续传
│  ├─ data/                  SQLite / Keystore 口令箱 / DataStore 偏好 / 续传 DAO
│  └─ ui/                    主题 / 文件图标 / 通用组件
└─ .github/workflows/ci.yml  测试 + 构建 3 个 ABI APK + 版本号变化自动 Release
```

## 构建

```bash
# 本地（需要 JDK 21 / Android SDK 37）
./gradlew testDebugUnitTest          # 单元测试
./gradlew assembleDebug              # 调试包
./gradlew assembleRelease            # 分 ABI 的 release 包（app/build/outputs/apk/release/）
```

CI（push 到 `main`/`dev`）：单元测试 → `assembleDebug assembleRelease` → 上传 `PanelFM-*.apk`；
当 `app/build.gradle.kts` 的 `versionName` 没有对应 tag 时自动创建 Release。
签名走仓库 secrets（`KEYSTORE` / `STORE_PASSWORD` / `KEY_PASSWORD` / `KEY_ALIAS`），缺失时回退 debug 签名。

## 免责声明

与 MT 管理器（作者 Lin Jin Bin）无任何关联，仅参考其**交互布局**；
本项目不包含、也不会加入任何逆向工程功能。

# PanelFM（Android 原生 · Kotlin）

MT 管理器风格的**双窗格文件管理器**，Android 原生实现（Kotlin + Jetpack Compose）。
**只做「文件管理 + 预览」这一半**，不含任何逆向工程功能（不做 DEX / Arsc / APK 编辑）。

策划与架构文档：[`docs/策划.md`](docs/策划.md)（含完整接口草图、协议要点、里程碑与风险对策）

## 已完成（M0–M8；交互按 MT 官方手册复刻）

- **双列浏览**：左右窗格完全独立——各自标签页、前进/后退历史栈、排序（名称/大小/时间/类型）、
  隐藏文件开关、焦点态、目录统计与可用空间；分隔条点击切换焦点。
- **底部命令栏**：`← → ＋ ⇄ ↑`（MT 布局）。`⇄` 菜单：复制到对面 / 移动到对面 / 同步路径 / 交换窗格 / 全选。
- **跨窗格操作**：目标恒为另一窗格当前目录（**不弹目标选择框**）；执行前**两侧路径栏高亮 + 中央文案**；
  移动带二次确认（项数、体积、是否跨存储中转）。
- **任务引擎**：队列 + 并发（1–4，可设）、进度/速率/剩余时间、暂停/继续/取消/全部暂停、
  冲突策略（覆盖 / 跳过 / 保留两者 / 每次都问 + 全部应用）、断点续传（本地偏移写、HTTP Range 读）。
- **界面与交互（按 MT 官方手册 + 截图复刻）**
  - **打开即是双列**（首屏直接是左右两个文件列表窗口），权限用弹窗补齐（所有文件访问 / 局域网访问 / 通知）
  - 顶部：≡ + 路径（过长中间省略）+ `文件夹 / 文件 / 储存` + ⋮；底部：`← → ＋ ⇄ ↑`
  - 列表首行 `..`；行高固定；**左右滑动任意文件即进入多选**；多选下底栏变为 `全选 / 反选 / 类选 / 复制到对面 / 取消`
  - **长按 ⇄ = 过滤**（支持 `文本` / `!文本` 否定 / `/正则` / `!/正则`，与 MT 一致）；**长按 ↑ = 输入路径跳转**；
    **底栏上滑 = 书签**；长按 ＋ = 新建文件
  - 长按文件 → **MT 动作菜单**：复制 ->（●）· 移动 ->（●）· 删除 · 重命名 · 工具 · 压缩 · 属性 · 分享 · 打开方式… · 添加书签；
    `●` 表示**长按可触发单窗口操作**（目标仍在本窗格内）
  - ⋮ 菜单与 MT 一致：刷新 / 输入路径 / 搜索·过滤 / 全选 / 过滤 / 排序方式 / 隐藏文件 / 添加书签 / 设为首页 / 交换窗口 / 主页 / 传输任务 / 设置 / 退出
  - **同步**：点击同步后另一窗格跟随；可用后退回到原路径；本窗格在压缩包内时，另一窗格定位到压缩包所在目录
  - 主页：`本地 / 网络 / 工具` 三段可折叠，本地项带**占用百分比条**；工具 = 回收站 / 远程管理 / 已安装应用 / 文本编辑器 / 终端模拟器 / 局域网扫描 / 书签 / 任务 / 设置
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
- **压缩包**：zip / jar / 7z / tar / tar.gz **挂载为只读 VFS** —— 直接进入压缩包逐层浏览、预览里面的文件、
  `⇄` 复制到对面窗格即**解压**；`⇄ → 压缩到对面（zip）` 把选中项打成 zip 写到另一个窗格（支持压缩到网络位置）。
    （按需求**不包含 APK 内部浏览**；Hex 仅保留只读查看，不做编辑。）
- **工具**：回收站（本地删除可还原）、远程管理（内置只读 HTTP 服务，电脑浏览器直接浏览/下载任意窗格目录）、
  已安装应用（列表 + 一键导出 APK 到当前窗格）、文本编辑器（可编辑保存）、终端模拟器（免 root sh）。
- **文本编辑器（M7）**：编码识别（UTF-8/GBK/UTF-16+BOM）并回写同编码、字号缩放、查找 / 全部替换、
  行数与字符统计、未保存提示、> 2 MB 自动只读、保存后**保留原权限**（本地/SFTP/FTP 支持 chmod 时）。
- **媒体播放（M7）**：Media3 / ExoPlayer + **统一 VFS 数据源** —— 本地 / SFTP / WebDAV / SMB / S3 上的
  音频视频都能直接播放与拖动进度（局域网 SMB 视频流媒体）。
- **缩略图（M9）**：本地图片内存 LRU + 磁盘缓存；网络图片按设置加载（默认仅 Wi-Fi 且 < 3 MB）；
  **快速滚动时跳过加载**（MT 同款手感）。
- **前台服务通知（M9）**：传输运行时通知栏显示进度/速率/当前文件，结束自动退出。
- **目录对比（M9）**：`⇄ → 比较两个目录`，统计相同/不同/仅左/仅右，支持「仅复制左侧」「只复制较新」。
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
| M7 | 🚧 已完成：可编辑保存 + 查找替换 + 媒体播放；待做：语法高亮、大文件窗口化、字体预览 |
| M8 | 压缩包（zip/7z/tar）挂载、解压、压缩（**不含 APK 内部浏览**；Hex 仅保留只读查看，不做编辑） |
| M9 | 🚧 已完成：缩略图管线、目录差异对比、回收站、通知栏进度；待做：拖拽落点语义、S3 分片续传落库 |
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

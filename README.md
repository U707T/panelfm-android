# PanelFM · Android 原生双窗格文件管理器

MT 管理器风格的**双窗格文件管理器**，Kotlin + Jetpack Compose 原生实现。

只做「文件管理 + 查看预览」这一半：**不含任何逆向工程能力**（不做 DEX / Arsc / AXML 编辑、不签名、不加固、不注入）。

```
包名      com.u707t.panelfm
版本      2.1.1 (96)
minSdk    26（Android 8.0）
targetSdk 37
产物      PanelFM-release-{arm64-v8a, armeabi-v7a, x86_64}.apk
```

---

## 1. 功能

### 浏览与管理

- **双窗格**，可左右交换 / 独立切换目录 / 各自记忆滚动位置（换目录再回来停在原地）
- **多选交互复刻 MT**：长按 400ms 直接弹该项菜单（不进多选）；左右滑动进入多选；滑到另一项时**闭区间全选**
- 复制 / 移动 / 删除（**带回收站**）/ 重命名 / 批量重命名 / 新建 / 压缩 / 解压 / 修改权限
- 文件过滤（通配符）+ 搜索（通配符 / 正则 / 按名或内容 / 结果排序 / 相对路径展示）
- 传输任务独立页：进度、暂停、取消、冲突逐个确认、断点续传
- 书签（长按拖动排序）、路径历史、常用目录

### 存储与协议

| 类型 | 说明 |
|---|---|
| 本地 | 含外部存储与 SAF |
| WebDAV | 坚果云 / Nextcloud 等 |
| FTP | 含 FTPS |
| SFTP | 密钥与口令认证 |
| SMB | 局域网共享 |
| S3 | 自实现 SigV4 签名，兼容 MinIO / 阿里云 OSS 等 |
| 压缩包 | zip / tar / tar.gz / tar.bz2 / tar.xz / 7z / rar 只读挂载；zip 可读写编辑 |

### 预览与阅读（全部只读、不联网、不写回原文件）

| 后缀 | 能力 |
|---|---|
| 图片 | 缩放、拖动、多图切换 |
| 音视频 | 播放器含进度 / 音量 / 亮度手势、播放列表、随机、倍速、长按加速 |
| PDF | 分页浏览 |
| 字体 | ttf / otf 字形与信息 |
| SVG | 渲染预览 |
| Office | docx / xlsx / xls / pptx / ods / fods（WebView + 前端渲染库，单文件上限 16 MB） |
| Markdown / CSV | 与 Office 同一页面渲染（marked + DOMPurify 清洗） |
| EPUB | 只读阅读 |
| SQLite | 表列表 + 前 200 行浏览（只读，不提供 SQL 控制台） |
| APK | 包名 / 版本 / 权限 / 签名摘要 |
| 压缩包 | 列表浏览 + 解压 |
| 文本 | sora-editor，22 种语言 TextMate 语法着色 |

### 文本编辑器

基于 **sora-editor**（LGPL-2.1），非自研输入控件 —— 大文件不再「点一下卡一下」。

- 22 种语言语法着色，可手动切换语法
- 查找 / 替换（后台执行，底部查找条）
- 编码识别：UTF-8 / UTF-16 / UTF-32 / GBK / Big5，可转码另存
- 格式化、转到指定行、行操作全集
- 二进制安全校验（JSON / XML 等结构校验）
- 大文件兜底：自动流畅模式 / 打开前确认 / 打开上限

### 工具箱

- **网络**：Ping（IPv4/IPv6、结论行）、DNS（自拼 RFC 1035 报文，10 种记录类型，可指定服务器）、HTTP（自动识别 UTF-8/GBK/Big5/UTF-16，二进制自动切 HEX）
- **字符串**：摘要 / 编解码 / 大小写 / 去重排序 / HEX dump
- **文本对比**：行级差异、忽略空白/大小写、上下差异跳转、并排与统一视图
- **局域网扫描**：网段发现（本机 /24）与单机多端口探测同页 —— 端口支持快捷多选 / 预设 / `1-1024` 区间，
  带 banner 与耗时，点结果按端口预选协议直接建连接（v2.1.2 起端口探测并入此处）

---

## 2. 构建

```bash
# 环境：JDK 21、Android SDK（compileSdk 37）

./gradlew testDebugUnitTest --offline        # 全仓单测
./gradlew :app:compileDebugKotlin --offline  # 改 UI 时最快的语法检查
./gradlew assembleDebug --offline            # 本地出包（三 ABI）
```

签名：`keystore.properties` 缺失时自动回落 debug 签名；CI 上缺 release key 会**硬失败**而非静默降级。

**不本地出 release**：release 一律由 CI 构建并发布，见下。

## 3. 发版

```
改 app/build.gradle.kts 的 versionName（带 -rc 后缀 → prerelease）
→ CHANGELOG.md 顶部加对应版段
→ push main
```

push 即触发 `.github/workflows/ci.yml`：单测 → 三 ABI release 构建 → **16 KB 页大小硬校验** → 建 tag 与 Release，Release 备注由 CI 从 `CHANGELOG.md` 对应版段自动截取。

---

## 4. 工程结构

```
app/                       Compose UI + 业务编排（ui/browser · preview · editor · tools · settings …）
core/common/               格式化 / 编码识别 / 文本差异 / 校验等纯函数
core/model/                数据模型（连接 / 排序 / 操作）
core/vfs-api/              VFS 抽象（VirtualFileSystem / VfsUri / 能力位）
core/vfs-local/            本地
core/vfs-webdav/           WebDAV
core/vfs-ftp/              FTP / FTPS
core/vfs-sftp/             SFTP（含 SSH 密钥处理）
core/vfs-smb/              SMB
core/vfs-s3/               S3（自实现 SigV4）
core/vfs-archive/          压缩包（读挂载 + zip 编辑 + 加密 zip）
core/transfer/             任务引擎（进度 / 暂停 / 冲突 / 续传）
core/data/                 SQLite + DataStore（连接 / 书签 / 偏好 / 密钥）
core/ui/                   UI 基座（MtSpec / MtIcons / MtWidgets / 主题 / 文件图标 / 手势）
```

规模（不含测试）：14 个模块、约 **180** 个 Kotlin 文件、**4.2 万**行。

## 5. 代码质量现状

本节数据来自 2026-10-08 起的全量重审（`docs/AUDIT-2026-10-08-REAUDIT.md`）与后续批次。

- **单测**：96 个测试文件、**624** 个 `@Test`
- **巨型文件已清零**：重审时 `BrowserController.kt`（2,289 行）与 `DualPaneScreen.kt`（2,277 行）是两座大山，现已拆成 6 + 8 个文件；`MediaScreen` 1,886 → 655 行；`EditorScreen` 1,064 → 512 行。目前全仓仅 `ArchiveVfs.kt`（1,058 行）略超 1,000 行
- **重审收口**：第 1–10 批共修掉 105 条问题（101 修 + 4 记录在案），含多条静默坏数据路径
- 审计文档按模块留档：`AUDIT-2026-10-08-REAUDIT.md`（全量总文档）、`-10-11-CHANGESET`、`-10-19-STABILITY`、`-10-21-*`、`-10-23-NETTOOLBOX`、`-10-26-PORTSCAN`

设计说明见：
- [`docs/SELECTION-MODEL.md`](docs/SELECTION-MODEL.md) —— 选择 / 多选交互模型（唯一设计说明）
- [`docs/EDITOR-ENGINE.md`](docs/EDITOR-ENGINE.md) —— 编辑器架构与维护
- [`docs/OFFICE-PREVIEW.md`](docs/OFFICE-PREVIEW.md) —— Office / Markdown / CSV 预览
- [`docs/READERS.md`](docs/READERS.md) —— SQLite 与 EPUB 阅读器

## 6. 与 MT 管理器的关系

只对齐**交互与观感**：布局结构、尺寸色值、图标 pathData、手势阈值、交互文案。

**不搬功能**：不含 Dex / Arsc / AXML 编辑、签名、加固、插件、账号 VIP、Root / Shizuku 注入。
回收站是 PanelFM 自加能力（MT 没有）。

## 7. 第三方组件

sora-editor（LGPL-2.1）、TextMate 语法（MIT）、docx-preview / SheetJS / pptx-renderer / marked / DOMPurify（宽松许可）。
来源、版本与许可证全文见 [`third_party/THIRD-PARTY-NOTICES.md`](third_party/THIRD-PARTY-NOTICES.md)。

## 8. 免责声明

仅供个人学习与自用。使用时请自行确认符合当地法律法规。
对 MT 管理器的对齐仅基于交互观察，与 MT 官方无任何关联。

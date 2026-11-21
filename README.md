# PanelFM · Android 原生双窗格文件管理器

MT 管理器风格的**双窗格文件管理器**，Kotlin + Jetpack Compose 原生实现。

只做「文件管理 + 查看预览」这一半：**不含逆向工程能力** —— 不提供 DEX / Arsc / AXML 编辑、不加固、不注入。
唯一碰「安装包内部」的功能是 **APKS / XAPK / APKM → APK**（MT 的「转为 APK」）：
它要改三处清单属性并用内置测试证书重签，边界与理由见 [`docs/APKS-TO-APK.md`](docs/APKS-TO-APK.md)。

```
包名      com.u707t.panelfm
版本      2.5.9-rc1 (132)
minSdk    26（Android 8.0）
targetSdk 37
产物      PanelFM-release-{arm64-v8a, armeabi-v7a, x86_64}.apk
```

---

## 1. 功能

### 浏览与管理

- **双窗格**，可左右交换 / 独立切换目录 / 各自记忆滚动位置（换目录再回来停在原地）
- **顶部路径（MT 观感）**：单行居中完整路径（中间省略）；点路径弹出层级菜单（祖先目录 / 跳转 / 复制 / 书签），长按复制完整路径；
  压缩包显示 `宿主路径/a.zip!/包内路径`，菜单里可逐级退出
- **6 套配色主题**：MT 蓝（默认）/ 墨笺 / 黛青 / 松烟 / 赭石 / Linear（中性灰 + 靛蓝，浅色模式是浅色顶栏）；设置里切换，顶栏、抽屉、分割线、菜单、路径面板一起换色；深色模式各有独立方案
- **浮层统一 Claude 几何 + 主题色**：路径面板 / ⋮ / 长按动作 / 各类下拉都从 `MaterialTheme.colorScheme` 取色（白卡 / 深色浮起层、`primary` 强调、`outlineVariant` 细边），16–20dp 圆角、18dp 淡图标、40dp 紧凑行
- **多选交互复刻 MT**：长按 400ms 直接弹该项菜单（不进多选）；左右滑动进入多选；滑到另一项时**闭区间全选**
- **列表显示文件大小（文件夹除外）**：副标题是 MT 的写法 —— 时间（或权限）+ 大小，例 `26-11-20 17:40  1.50 M`；
  **文件夹不带体积**（目录要递归统计，只在「属性」里看：子项合计 + 文件数 / 文件夹数），
  打开 `/sdcard` 这类大目录时没有任何后台递归统计
- **列表缩略图**：图片本地采样解码，远端图片受「连接开关 / 仅 Wi-Fi / 大小上限」控制；
  **视频取首帧**（本地直接读，网络按 Range 随机读解 moov，单张预算 8 MB；压缩包内不取帧）；
  **本地 APK 取包内应用图标**（自适应图标按系统可见区裁切，解析失败回退类型图标）
- 复制 / 移动 / 删除（**带回收站**）/ 重命名 / 批量重命名 / 新建 / 压缩 / 解压 / 修改权限
- **单击 APK 弹信息卡片**（MT 的「APK 信息」）：`apk` / `apks` / `xapk` / `apkm` 单击不再直接进预览页，
  而是弹卡片 —— 图标 + 名称 + 版本，包名 / 版本号 / 安装包大小 / 签名状态 / 加固状态 / 目标系统 /
  最低系统 / 已安装（含首次安装、最后更新、UID）/ 数据目录 / APK 路径；
  底部 `功能`（整页详情 · 提取图标 · 转为 APK · 校验值 · 属性 · 复制路径）· `查看`（包内浏览）· `安装`。
  详见 [`docs/APK-INFO.md`](docs/APK-INFO.md)
- **APKS / XAPK / APKM → APK**（长按「转为 APK」）：合并 base 与 ABI 分包、补清单、
  内置测试证书重签；包内已有 `universal.apk` / 匹配本机的 `standalones/` 时直接取出（保留官方签名）。
  高密度图片与其它语言不并（资源表不重写，见 [`docs/APKS-TO-APK.md`](docs/APKS-TO-APK.md)）
- 文件过滤（通配符）+ 搜索（通配符 / 正则 / 按名或内容 / 结果排序 / 相对路径展示）
- **系统「打开方式」**：其他应用的文件（`content://`）可直接交给 PanelFM 预览；
  先复制到应用缓存再走内置查看器，压缩包进包内浏览、EPUB 进阅读器，编辑只改副本，
  可保存到当前窗格（单文件、≤ 2 GB，见 [`docs/OPEN-WITH.md`](docs/OPEN-WITH.md)）
- 传输任务：开始传输自动弹出**带进度条的模态对话框**（暂停 / 取消 / 后台运行），
  关掉后底部留一行胶囊可随时点回；另有独立任务页（多任务总览、暂停全部、清空）
- 书签：底栏上滑拉出**底部抽屉**（「最近 / 书签」两个标签页，可上拉到全屏、⌄ 收起），
  长按拖动排序、长按弹行菜单（打开 / 复制路径 / 删除）；另有路径历史与常用目录

### 存储与协议

| 类型 | 说明 |
|---|---|
| 本地 | 外部存储直访与「接收文件」缓存卷；当前不提供 SAF 目录挂载 |
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
| 音视频 | 播放器含进度 / 音量 / 亮度手势、播放列表、随机、倍速、长按加速、自动后台播放（设置里开启，**默认关**） |
| PDF | 分页浏览 |
| 字体 | ttf / otf 字形与信息 |
| SVG | 渲染预览 |
| Office | docx / xlsx / xls / pptx / ods / fods（WebView + 前端渲染库，单文件上限 16 MB） |
| Markdown / CSV | 与 Office 同一页面渲染（marked + DOMPurify 清洗） |
| EPUB | 只读阅读 |
| SQLite | 表列表 + 前 200 行浏览（只读，不提供 SQL 控制台） |
| APK | 单击弹信息卡片（见 [`docs/APK-INFO.md`](docs/APK-INFO.md)）；整页详情含权限 / ABI / 安装位置 / 签名摘要 / MD5·SHA-256 |
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
- **已安装应用**：用户 / 系统两个分页、真实图标；点一行出应用信息卡片（更多 / 提取安装包），
  提取完成给「文件已保存到 …」+ 定位（直接回文件列表并选中导出的 APK）

---

## 2. 构建

```bash
# 环境：JDK 21、Android SDK（compileSdk 37）

./gradlew testDebugUnitTest --offline        # 全仓单测
./gradlew :app:compileDebugKotlin --offline  # 改 UI 时最快的语法检查
./gradlew assembleDebug --offline            # 本地出包（三 ABI）
```

签名：`key.properties` 缺失时自动回落 debug 签名；CI 上缺 release key 会**硬失败**而非静默降级。

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
core/apk/                  APKS/XAPK/APKM 转 APK（AXML 补丁 + zip 合并 + apksig 重签）
core/transfer/             任务引擎（进度 / 暂停 / 冲突 / 续传）
core/data/                 SQLite + DataStore（连接 / 书签 / 偏好 / 密钥）
core/ui/                   UI 基座（MtSpec / MtIcons / MtWidgets / 主题 / 文件图标 / 手势）
```

规模（不含测试）：15 个模块、**205** 个 Kotlin 文件、**4.83 万**行（2026-11-16 实测，v2.4.3）。

## 5. 代码质量现状

本节数据来自 2026-10-08 起的全量重审（归档于 `docs/archive/AUDIT-2026-10-08-REAUDIT.md`）与后续批次，
数字于 2026-11-16（v2.4.4）重新实测。

- **单测**：127 个测试文件、**876** 个 `@Test`（全模块 `testDebugUnitTest` 实际执行 876 例，2026-11-20 实测）
- **巨型文件**：全仓仅 `ArchiveVfs.kt`（1,058 行）超过 1,000 行。重审时的两座大山
  `BrowserController.kt`（2,289 行）与 `DualPaneScreen.kt`（2,277 行）已拆成 6 + 8 个文件。
  两个反弹项要说清楚：`MediaScreen` 655 → 986（v2.2.2–v2.2.4 播放链路增强）、
  `EditorScreen` 512 → 923（编辑器功能增加）、`TransferTask` 954、`PaneView` 850（快速滚动条）
  —— 都还没回到 1,000 行，但已不是「随便加个功能」的水平
- **重审收口**：第 1–10 批共修掉 105 条问题（101 修 + 4 记录在案），含多条静默坏数据路径
- 审计文档全部归档在 [`docs/archive/`](docs/archive/README.md)（含索引与「仍未修」清单）；
  新一轮全量审计写在本目录

设计说明见：
- [`docs/SELECTION-MODEL.md`](docs/SELECTION-MODEL.md) —— 选择 / 多选交互模型（唯一设计说明）
- [`docs/EDITOR-ENGINE.md`](docs/EDITOR-ENGINE.md) —— 编辑器架构与维护
- [`docs/OFFICE-PREVIEW.md`](docs/OFFICE-PREVIEW.md) —— Office / Markdown / CSV 预览
- [`docs/READERS.md`](docs/READERS.md) —— SQLite 与 EPUB 阅读器
- [`docs/OPEN-WITH.md`](docs/OPEN-WITH.md) —— 用其他应用打开（ACTION_VIEW）的链路、边界与验证
- [`docs/APKS-TO-APK.md`](docs/APKS-TO-APK.md) —— APKS / XAPK / APKM 转 APK（合并口径、清单补丁、签名、已知限制）
- [`docs/APK-INFO.md`](docs/APK-INFO.md) —— 单击 APK 的信息卡片（字段来源、签名方案与加固的判定边界）
- [`docs/AUDIT-2026-11-20-CHANGESET-v2.5.3.md`](docs/AUDIT-2026-11-20-CHANGESET-v2.5.3.md) —— 卡片排版 + 全仓复用收口（哪几处重复实现收成了一份）

## 6. 与 MT 管理器的关系

只对齐**交互与观感**：布局结构、尺寸色值、图标 pathData、手势阈值、交互文案。

**不搬功能**：不含 Dex / Arsc 编辑、AXML 编辑器、加固、插件、账号 VIP、Root / Shizuku 注入。
签名只保留一处 —— 「转 APK」后必须重签（内置测试证书，`docs/APKS-TO-APK.md` §5），不做独立签名工具。
回收站是 PanelFM 自加能力（MT 没有）。

## 7. 第三方组件

sora-editor（LGPL-2.1）、TextMate 语法（MIT）、docx-preview / SheetJS / pptx-renderer / marked / DOMPurify（宽松许可）。
来源、版本与许可证全文见 [`third_party/THIRD-PARTY-NOTICES.md`](third_party/THIRD-PARTY-NOTICES.md)。

## 8. 免责声明

仅供个人学习与自用。使用时请自行确认符合当地法律法规。
对 MT 管理器的对齐仅基于交互观察，与 MT 官方无任何关联。

---

## 本地轻量验证与 CI

Windows 本地可用隔离 JDK 21、Kotlin CLI 2.4.10 和 JUnit 4.13.2 执行不依赖 Android 的定向回归，不需要安装 Android SDK 或模拟器。Android 单元测试、Compose/Kotlin 编译、三 ABI APK 和 16KB 校验由 CI 执行；本地纯 JVM 通过不能替代 Android 平台验证。

前端预览测试使用 Node.js 24、pnpm 11.28.5，依赖固定在 `tools/preview-tests/pnpm-lock.yaml`：

```powershell
pnpm --dir tools/preview-tests install --frozen-lockfile --ignore-scripts
pnpm --dir tools/preview-tests test
```

测试执行实际内置 SheetJS / DOMPurify / marked 与查看器脚本，检查资产 SHA-256、表格危险链接、中文 CSV 与 Markdown 清洗。CI 的 `preview` job 也执行这些测试，并与 JVM 单测共同作为 APK 构建门禁。

- 工具链与下载缓存放仓库之外或已有忽略目录，默认保留；不提交 SDK、JDK、签名材料、本机路径或令牌。
- 完整 CI 可通过 `workflow_dispatch` 在修复分支运行；只有推送 main 才自动创建版本 Release。
- 真实权限/安装确认、长期后台服务、文件系统原子替换支持、各 WebView 排版仍需设备验收。

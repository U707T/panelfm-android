# APK 信息卡片（单击 apk / apks 弹出）

> 状态：已实现（v2.5.2，2026-11-20）。
> 目标：对齐 MT —— **单击**安装包看到「这是什么包、装没装、签名怎么签的」，而不是一头扎进预览页。

---

## 1. 范围

### 做

| 交互 | 行为 |
|---|---|
| 单击 `apk` / `apks` / `xapk` / `apkm` | 弹信息卡片（`ui/browser/DialogsApkInfo.kt`），**不进预览页、不挂压缩包** |
| 长按菜单 / 顶栏动作条「APK 信息」 | 同一个卡片（与单击一个入口） |
| 卡片 `查看` | 当压缩包打开，进包内浏览（v2.5.2 之前单击 `apk` 的默认行为） |
| 卡片 `安装` | 交给系统安装器；`.apks` 走 `PackageInstaller`（`installApk` / `installApkBundle`） |
| 卡片 `功能` | APK 详细信息整页 / 提取图标 / 转为 APK（分包包）/ 校验值 MD5·SHA-256 / 属性 / 复制路径 |
| 「打开方式」里的 `APK 详细信息` | 整页（权限清单 / ABI / 安装位置 / 可调试 / 签名摘要 / MD5·SHA-256） |

### 不做

| 项 | 原因 |
|---|---|
| 完整签名校验（`ApkVerifier`） | 要读全部条目算摘要，几百 MB 的包好几秒；而且**改一个字节就整体失败**（合并包 / 二次签名包全中）。要看有效性请「安装」——系统安装器会做真正的校验 |
| dex 级加固识别 | 本项目不解析 dex（README §6 边界）。只用条目名 + 壳入口类两条特征 |
| Root / Shizuku 安装 | 与「安装」一致：只做系统安装器这一档 |
| 远程 APK 的图标 | 图标要随机读包内 `resources.arsc`；远端等于整包下载，代价与收益不成比例（与列表缩略图同一结论） |

---

## 2. 字段来源

| 行 | 来源 | 备注 |
|---|---|---|
| 包名 / 版本号 / 目标系统 / 最低系统 | `PackageManager.getPackageArchiveInfo`（`PackageInfo` / `ApplicationInfo`） | 目标系统 = `targetSdkVersion`，最低系统 = `minSdkVersion`；版本名用 `AndroidApi.label()` 查表 |
| 安装包大小 | 列表 metadata 的 `size`（未知时退回落地文件长度） | 与列表同一口径 |
| 签名状态 | `ApkSignatures.detect()`（`core/apk`） | 见 §3 |
| 加固状态 | `ApkHardening.detect()`（`core/apk`） | 见 §4 |
| 已安装 / 首次安装 / 最后更新 / UID | `PackageManager.getPackageInfo(pkg, 0)` | 未安装则**不显示**这三行，只留「未安装」 |
| 数据目录 1 / 2 | 按包名拼：`/data/user/0/<pkg>`、`<外部存储>/Android/data/<pkg>` | 只是路径文本，不代表一定存在（Android 11+ 分区存储下目录可能还没建） |
| APK 路径 | 当前文件本身：本地用真实绝对路径，远端 / 包内用 VFS URI | **不是**安装位置 |
| 已安装路径 | 已安装包的 `ApplicationInfo.sourceDir` | 只在已安装时显示；MT 把这一项直接写在「APK 路径」里，这里拆成两行，避免「路径对不上文件」的困惑 |

---

## 3. 签名状态：只判方案，不判有效

`core/apk/ApkSignatureSchemes.kt` 只读 zip 结构：

- **V1**：`META-INF` 下同时存在 `.SF` 与 `.RSA` / `.DSA` / `.EC`；
- **V2 / V3 / V3.1**：中央目录前的 APK Signing Block 里有对应 ID 的键值对
  （`0x7109871a` / `0xf05368c0` / `0x1b93ad61`）；
- 支持 zip64（EOCD 里 CD 偏移写 `0xFFFFFFFF` 时去 zip64 记录取）。

显示成 MT 的写法：`V1 + V2 + V3` / `未签名`。

⚠️ **存在 ≠ 有效**：签名被破坏的包仍会显示 `V2`（与 MT 一致，MT 同样按结构显示）。
判定读不出来（不是 zip / 截断）时按「未签名」显示，不弹错误。

---

## 4. 加固状态：特征库匹配

`core/apk/ApkHardening.kt` 两条信号，都不碰 dex：

1. 包内条目名：`libjiagu*`（360）、`libshella-*` / `libtosprotection*`（腾讯）、
   `libSecShell` / `libDexHelper`（梆梆）、`libmobisec*`（阿里）、`libnesec`（网易易盾）、
   `libbaiduprotect`（百度）、`libchaosvmp` / `libddog`（娜迦）、`assets/ijiami*`（爱加密）…
2. `<application android:name>` 的壳入口类：`com.stub.StubApp`、`com.secneo.apkwrapper`、
   `com.tencent.StubShell`、`com.qihoo.util.StubApplication`、`s.h.e.l.l.` …

命中给 `厂商 加固`，都不中给「未检测到加固」。**这是特征匹配，不是通用判定**：
壳会改名、会清特征（漏报正常），个别正常应用也可能自带同名 `.so`（误报可能）。
证据（命中的条目名 / 类名）留在 `Hardening.evidence`，需要时可以在详情页显示。

---

## 5. 分包安装包（`.apks` / `.xapk` / `.apkm`）

卡片要的是「包的身份」，而 `PackageManager` 只接受 APK 文件路径，所以：

1. `ApkBundleReader.inspect()` 找出 base（或 `universal.apk` / `standalones/` 里第一份）；
2. 把那一份从外层 zip 解到 `cache/apk/base-<key>.apk`；
3. 再走与单 APK 完全相同的解析链路（包名 / 图标 / SDK / 签名 / 加固）。

缓存键 = 外层文件「绝对路径 + 大小 + 修改时间 + 条目名」的 SHA-256 前 16 位；
3 天没碰过的物化缓存会在下一次解析时清掉。

卡片顶部会多一行说明：**分包安装包（APKS）：信息取自 base.apk，安装请用「转为 APK」或系统安装器**。

---

## 6. 远端 / 包内文件

- `PackageManager` 只认文件路径 → 远端（WebDAV / FTP / SFTP / SMB / S3）与压缩包内的 APK
  先整包缓存到 `cache/apk/pkg-<key>-<size>.apk`，卡片上会显示「正在缓存到本地（182.71M）…」；
- 缓存命中（同 URI + 同大小）直接用，不再重复下载；
- 复制循环带 `ensureActive()`：**关掉卡片立刻停下**，不在后台白读到底；
- 安装仍然要求先复制到本地（与「分享 / 系统打开」同一口径）。

---

## 7. 真机验证清单

- [ ] 本地 `apk` 单击 → 卡片出图、14 行齐全、`功能 / 查看 / 安装` 三键可点；
- [ ] 已安装的包：已安装版本 / 首次安装 / 最后更新 / UID / 已安装路径 都有值；未安装的包这四项不出现；
- [ ] `.apks` 单击 → 卡片显示「信息取自 base.apk」，`查看` 进包内浏览、`功能 → 转为 APK` 可用；
- [ ] 远端 APK 单击 → 先出「正在缓存」文案，缓存完成后补齐字段；中途关掉卡片不残留 `.part`；
- [ ] 深色 / 浅色主题下标签与值不挤成两行以上；长路径最多两行且可长按选中复制。

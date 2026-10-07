# PanelFM 安全 / 隐私 / 供应链审查（2026-10-06）

> **换方向的说明**：本仓库已有两份审计 —— `AUDIT-2026-10-05-CODE-TRUTH.md`（正确性 / 死代码 / 测试有效性 + 依赖告警核查）
> 与 `AUDIT-UX-2026-10-05.md`（实用体验）。本轮**刻意走第三条轴**：
> **安全 · 隐私 · 供应链 · 发布可信度**（攻击面 / 密钥与凭据 / 网络服务 / TLS 信任模型 / Manifest 授权 /
> 文件暴露 / 备份与日志 / 依赖来源与发布流水线）。
>
> **不重复**：不重述 C1/C2/C3（加密压缩包）、R1–R5（设置项不生效）、U1–U20（体验）—— 未修的直接标「仍存在（前次已报）」，
> 只补前两份报告**没有覆盖**或**判断有误**的部分。
>
> 基线：`main` @ `2425982`（v1.3.11），工作树干净（`git status` 空、`git diff --check` 干净），ahead origin/main 2。
> 方法：只读源码 + 只读构建产物 + 实际跑测试/lint/签名报告。**未改动任何源码**（本文件是唯一新增物）。

---

## 0. 验证故事（先看这个）

| 项 | 命令 | 结果 |
|---|---|---|
| 单元测试（全量重跑） | `./gradlew testDebugUnitTest --offline --rerun-tasks` | **BUILD SUCCESSFUL**，274 tasks executed（非 up-to-date），无 failed |
| Lint | `./gradlew :app:lintDebug --offline` | **0 errors / 25 warnings / 35 hints**（较前次审计的 20 warnings 增加，见 §5） |
| 构建 | `./gradlew :app:assembleDebug --offline` | **BUILD SUCCESSFUL** |
| 签名口径 | `./gradlew :app:signingReport --offline` | debug **与** release 都是 `panelfm-release.jks`（alias `panelfm`），**同一个正式签名** |
| 工作树 | `git status --short` / `git diff --check` | 干净（本报告为唯一新增文件） |

**一句话结论**：这不是「不安全的应用」，而是**一个把「个人自用 + 局域网」当作安全边界的应用**。
它在**网络侧协议实现**上做得比多数同类 App 细（ZIP 路径穿越、HTML 转义、CRLF 过滤、主机指纹 TOFU、
跨主机剥离 Authorization、口令走 Keystore）——但**几处关键控制点与真实发布状态不一致**，
一旦用户把它当「能连公网的 NAS 管理器」用，就会踩到实证的漏洞或静默失效的防护。

**最需要先处理的四条（按可利用性 × 影响排序）**：

1. **S1 内置 HTTP 服务默认绑定 0.0.0.0 且零认证**（局域网任一设备可读任意已挂载存储）——前次审计只提到「无写超时」。
2. **S2 debug 与 release 用同一把正式私钥签名**（可实证的签名滥用放大器 + 本地 key.properties 与发布密钥同源）。
3. **S3 发布 APK 由「不可复现的镜像依赖 + 未校验的 wrapper」构建**（阿里云镜像优先 + 无依赖锁定/校验 + 无 gradle-wrapper 校验）。
4. **S4 「信任自签证书」= 全局 trust-all + hostnameVerifier 恒 true**（注释自承，但**无人改**；开在 WebDAV 上等于明文级降级）。

---

## 1. Critical —— 建议在下一个发布前处理

### S1. 「远程管理」= 内置 HTTP 服务绑定 `0.0.0.0`、零认证、零授权，可读**任意已挂载存储**

**代码事实**

- `app/.../tools/RemoteHttpServer.kt:38` `ServerSocket(0)` —— **不指定 bind 地址** → 监听 `0.0.0.0`（所有网卡，含 WiFi/热点/有线）。
- `:44` 用 LAN IPv4 拼 URL（`lanAddress()` 取第一个非 loopback 的 `Inet4Address`）。
- **整个 `handle()` 没有任何认证**：不校验来源 IP、不校验 `Referer`/`Origin`、不校验 token、不校验 `Host` 头。
- 请求行 `:92` `val rawPath = parts[1].substringBefore('?')` —— 只读第一行 + 吞掉请求头，**不看方法**；POST/PUT/DELETE 也只是按路径返回内容。
- `:110` `val target = if (safePath == "/") root else root.resolve(...)`，`root` = **启动服务那一刻的当前窗格目录**。

**为什么这条是 Critical 而不是「N2 的加强版」**

前次审计把 `RemoteHttpServer` 归到 Optional（N1 TOCTOU / N2 无写超时）。但评估这条要看**它能读到什么**：

- `root` 是「当前窗格目录」。用户在浏览器里可能停在 `local://root/`（整个 `/`）、`local://emulated/`（内部存储全量）、
  或某个 NAS 的根（`sftp://nas/home` 全部）。`root.resolve()` + `normalizePath()`（`:205`，已正确拒绝 `..` 逃逸）
  意味着**攻击者拿到的是整个 root 子树的读权限**。
- 暴露面不止私网：`ACCESS_LOCAL_NETWORK` 只管「本机作为客户端访问局域网」，**不限制本机作为服务端被谁连**。
  手机连上咖啡店 WiFi / 校园网 / 公司 VLAN / 开热点时，同网段任何设备都能扫到该随机端口。
- 服务端**没有 TLS**（明文 HTTP），路径与文件名在局域网上是裸奔的。

**用户可见后果（无需漏洞利用）**

同一 WiFi 下任何设备（另一台手机、笔记本、被入侵的 IoT）执行：

```bash
# 端口是随机的（ServerSocket(0)），但同网段扫一遍 32768-60999 即可
curl -s http://<手机IP>:<port>/            # 列出 root 目录
curl -sO http://<手机IP>:<port>/dcim/xxx.jpg
```

即：**无需口令即可批量下载该存储下的全部文件**（照片、文档、证书、SSH 私钥备份……）。用户界面文案是
「只读，仅在局域网内可访问」，**没有告知「同一网络里的任何人都能读」**，属于安全承诺与实现的落差。

**建议修法（按性价比排序，可分开做）**

1. **最小改动**：`start()` 里改为 `ServerSocket(0, 50, InetAddress.getByName(bindIp))`，**只绑定用户在界面上选定的网卡**；
   默认值仍可「局域网」，但必须让用户显式点一次，并在 URL 旁写明「同一网络内的任何设备都能访问」。
2. **加一层共享密钥**：生成 128-bit token，URL 变成 `http://ip:port/<token>/…`（或要求 `Authorization: Bearer`）。
   随机 token 长度足够即可挡住扫描类访问；同时把 `?` 之后的 query 直接拒绝。
3. **加基本卫生**（顺带修 N1/N2）：只接受 `GET`/`HEAD`（其它方法回 405）；Host 头白名单（本机 IP + 端口）；
   用 `getAndIncrement()` + 回退修并发上限 TOCTOU；设置 `SO_SNDTIMEO` 或写超时。
4. **把 `serve()` 提为可注入的纯函数并补测试**（前次审计 M4 已指出零覆盖：`normalizePath` 变异全绿）。
   抽 `internal fun normalizePath(raw: String): String?` + `internal fun isAllowedMethod(...)`，用 `MockWebServer` 风格断言即可。

---

### S2. Debug 与 Release 用**同一把正式私钥**签名，且本地 `key.properties` 指向发布密钥

**实证**

```
$ ./gradlew :app:signingReport --offline
Variant: debug     Config: release   Store: /workspace/panelfm-keys/panelfm-release.jks   Alias: panelfm
Variant: release   Config: release   Store: /workspace/panelfm-keys/panelfm-release.jks   Alias: panelfm
```

`app/build.gradle.kts:62-72`：

```kotlin
val stable = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
release { signingConfig = stable }
debug   { signingConfig = stable }   // ← debug 也吃 release 签名
```

**为什么这是 Critical 级的供应链问题**（不是「顺手」问题）

- `android:debuggable="true"` + **正式签名** 的组合，等于把「正式身份的 APK」交给可被任意调试/注入的产物。
  任何被安装过 debug 包的设备上，攻击者可用 `run-as` / JDWP 读取该 App 私有目录（含
  `files/panel.db`、`files/keys/*` 私钥、`/workspace` 侧的 Keystore 包装），并可直接改内存/落盘代码。
- Android 的签名身份（`sharedUserId` 时代）、`FileProvider` authority、`allowBackup` 白名单、
  以及**任何以「包名 + 签名」做信任判断的第三方组件**（如 Tasker / 自动化工具 / 某些 VPN 白名单）
  都会把 debug 包当成正式 App 接受。
- 反过来：一旦 debug 包外泄，**正式签名的「可信来源」就被污染**，而 release 与 debug 无法从签名上区分。

**同源风险（第二条）**：`key.properties` **未被 git 跟踪**（好），但它指向
`storeFile=/workspace/panelfm-keys/panelfm-release.jks` —— **本地开发机用的就是发布私钥**。
`git log --all -- key.properties local.properties` 为空、全历史里没有 `.jks/.keystore/.pem/.key`
（`git log --all --diff-filter=A --name-only | grep -Ei '\.(jks|keystore|p12|pem|key)$'` → 空），
所以**目前没有入库泄漏**；但「本地 = 发布密钥」意味着任意一次误把 `key.properties` 加进暂存区、
或把 `app/build/outputs` 目录打包分享，就会连带私钥密码。

**建议修法**

1. **拆开签名身份**：本地/CI 的 `assembleDebug` 一律用 **Android 默认 debug keystore**（或专用 `debug.jks`），
   **只有 `release` variant 才用 `release` 签名**。改动一行：
   `debug { signingConfig = signingConfigs.getByName("debug") }`（AGP 自带）。
2. CI 里已是「secrets 缺失 → 硬失败发布」的好设计（`app/build.gradle.kts:25-31` + `ci.yml`），保留；
   但**再把 debug job 与 release job 分开签名来源**，避免再退回 `stable`。
3. 本地开发建议改用一把**独立的自签测试密钥**（`panelfm-dev.jks`）；发布私钥只在离线/受控机器上出现。
4. 顺手把 `key.properties` 里的明文口令改成读环境变量（`System.getenv("PANELFM_STORE_PASSWORD") ?: props.getProperty(...)`），
   这样「配置里出现明文口令」这条就不会随文件被复制扩散。

---

### S3. 发布 APK 的**构建供应链不可复核**：镜像优先 + 无依赖锁定/校验 + wrapper 未校验

三件事叠加，导致「用户下载的 APK」与「仓库里的源码」之间**没有可验证的链条**：

**(a) 插件/依赖解析优先走第三方镜像**

`settings.gradle.kts:4-8` 与 `:22-26`：

```kotlin
pluginManagement { repositories {
    maven("https://maven.aliyun.com/repository/google")   // ← 第三方镜像，排第一
    maven("https://maven.aliyun.com/repository/central")
    google(); mavenCentral(); gradlePluginPortal()
} }
dependencyResolutionManagement { repositories { /* 同上，镜像优先 */ } }
```

排在 google()/mavenCentral() **之前** ⇒ Gradle 会**先从阿里云镜像取 artifact**。
官方仓库的 `sha1` 校验只对「来自该仓库」的下载生效；镜像是一个**独立信任域**，
其内容不参与 Gradle 的官方校验链。风险：镜像被投毒 / 被运营方替换 / 同步了错误产物时，
构建出的是「看起来一样、行为不同」的 APK，而仓库里看不到任何差异。

**(b) 没有任何依赖完整性锁定**

```
$ find . -name 'verification-metadata.xml' -o -name '*.lockfile' -o -name 'dependencies.lock'
（空）
```
⇒ 没有 Gradle **dependency verification**（SHA-256 白名单），也没有 dependency locking。
加上 `gradle.properties` 的 `org.gradle.caching=true`（构建缓存可跨机复用），
「同一次 commit 在不同机器/不同时间构建出不同字节」是默认状态。

**(c) gradle-wrapper.jar 未纳入校验**

```
$ grep -n distributionSha256Sum gradle/wrapper/gradle-wrapper.properties
（无）
```
`distributionUrl=https://services.gradle.org/distributions/gradle-9.7.0-bin.zip`
⇒ wrapper 校验和未固定，wrapper 自身被替换时无告警（CI 与本地都跑 `./gradlew`）。

**(d) CI 动作全是浮动 tag**

`ci.yml`：`actions/checkout@v4`、`setup-java@v4`、`gradle/actions/setup-gradle@v4`、
`upload-artifact@v4`、`download-artifact@v4` —— **全部未 pin 到 commit SHA**。
这是 GitHub 官方动作，风险相对低，但「发布 main 分支产物的流水线」按最佳实践应 pin SHA。

**建议修法（可拆成三个小 PR）**

1. **仓库顺序**：把 `google()/mavenCentral()` 放到镜像**之前**；镜像作为
   `content { includeGroupByRegex(...) }` 的**备选**（或在 `settings.gradle.kts` 里加注释说明「仅离线/内网时启用」，
   并用 `-PuseMirror` 开关控制），这样默认构建走官方源。
2. **加 dependency verification**：`./gradlew --write-verification-metadata sha256 help` 生成
   `gradle/verification-metadata.xml` 并提交；之后任何依赖字节变化都会**构建失败**（而不是静默接受）。
   这一步同时把「镜像投毒」从「无解」变成「可检测」。
3. **固定工具链**：`gradle-wrapper.properties` 加 `distributionSha256Sum=<官方 9.7.0-bin.zip 的 SHA-256>`；
   CI 动作改成 `uses: actions/checkout@<sha> # v4.2.2` 形式。
4. **发布产物可复核**：CI 已有「不改文件名就报错」「16KB 页对齐硬门槛」，很好；
   建议再加一步 `sha256sum out/*.apk > out/SHA256SUMS` 并作为 artifact / release 附件，
   让用户能自己核对下载的包。

---

### S4. 「信任自签证书」开关 = **全局 trust-all + hostnameVerifier 恒 true**，且注释自承「不该是默认」却无人改

**实证**：`core/vfs-api/.../TlsTrust.kt:19-31`

```kotlin
val trustManager = object : X509TrustManager {
    override fun checkClientTrusted(...) = Unit      // ← 空实现
    override fun checkServerTrusted(...) = Unit      // ← 空实现：任何证书链都通过
    override fun getAcceptedIssuers() = emptyArray()
}
val socketFactory by lazy { SSLContext.getInstance("TLS").init(null, arrayOf(trustManager), SecureRandom()).socketFactory }
```

接线上：`DavHttp.kt:116-121`（WebDAV/HTTPS）、`S3Client.kt:52-57`（S3）、`FtpVfs.kt:93`（FTPS）
三处都同时 `sslSocketFactory(TlsTrust...)` + `hostnameVerifier { _, _ -> true }`。

**为什么值得单独列为 Critical 级**（前次审计只记为 lint `TrustAllX509TrustManager` ×8）：

1. **它把 TLS 降级为「只防被动嗅探」**：主机名校验也关掉了 ⇒ 中间人只要有一个自签证书就能完整冒充服务器，
   **Basic 认证凭据（`DavHttp.kt:110` 明文 Basic）与 S3 AK/SK 签名流量**都可被解出/转发。
2. **开关的语义与用户理解不一致**：用户点的是「信任**自签**证书」，
   得到的却是「信任**所有**证书 + 不校验主机名」。自建 MinIO/Alist 的用户会长期开着它。
3. **文件自己写明了正确做法却没做**：`TlsTrust.kt:15-17` 注释
   「⚠️ 这是**全局放开校验**的实现……绝不能成为默认值。要收紧成「按连接固定指纹」需要另一套设计（见审计报告 §8）」。
   这是**已知技术债被注释合法化**的典型，且前次审计把它归为 Optional 就再无人碰。

**建议修法（不改架构也能立刻收窄）**

1. **只信该连接实际用的那张证书**：连接测试时把服务器证书（或其 SHA-256）存进
   `connection.options["pinnedCertSha256"]`，`X509TrustManager.checkServerTrusted` 里
   **只放行这一条链**（`chain[0].encoded` 的 SHA-256 比对）。UI 文案改成「记住此服务器的证书」。
2. **hostnameVerifier 不要恒 true**：只有证书与 pin 匹配时才 `true`；
   需要容忍 IP 直连域名不符时，单独做成一个更窄的选项（并在界面上写明）。
3. 过渡期至少：**改文案**（「信任所有证书（含主机名，请仅在自建服务器上使用）」）+ 首次开启时弹一次风险确认。
4. 顺手补测试：pinning 逻辑（错证书 → 拒绝；对证书 → 放行）是纯函数，容易测。

---

## 2. Required —— 建议在发布/合并前处理

### S5. `?c=<connectionId>` 一旦丢失，同主机多账号的隔离就退化（安全相关的正确性）

`VfsUri.sameMount()`（`core/vfs-api/.../VfsUri.kt:52-60`）在两侧都无 `c=` 时**返回 true**：

```kotlin
val a = VfsUris.connectionId(this); val b = VfsUris.connectionId(other)
return if (a != null || b != null) a == b else true
```

前次审计把 R5（`c=` 注入链路不完整）当「Required，方向上偏拒绝/跳过」，
但**安全侧有一个前次未点明的后果**：`isSameOrDescendant()` / `FileOperationPlanner.isInside()` 的误判
是「同一账号内的自我判断」，而 `BrowserController` / `TransferTask` 把 URI 写进**书签、最近路径、
path_history、断点续传记录**（`resume_entry.source/dest`、`bookmark.uri`、`path_history.uri`）时若不带 `c=`，
这些持久化记录就**只剩下 `scheme+authority+path`**。

⇒ 同一 NAS 上「管理员账号的连接」与「只读账号的连接」被合并成同一挂载点语义后，
**用户在 B 账号下打开的路径、产生的传输记录、书签，可以指向 A 账号的同名路径**，
而 UI 上没有任何提示（Pane 的 `label` 是 authority，不是连接名）。

**建议**：这不是「要不要顺手修」的问题，而是**数据归属边界**问题。修法与 R5 相同
（`AppContainer.uriForConnection()` 收敛注入点），但**验收标准要加一条**：
所有落库的 URI（bookmark / path_history / resume_entry）都必须可反查唯一连接；
对历史无 `c=` 的旧记录，反查歧义时**拒绝自动重连**并提示用户重新选择存储。

### S6. SFTP 主机指纹（TOFU）无任何可见入口，「忘记指纹」引导是死路

- `SftpSession.kt:206-220`：`known == null` → **直接 `store.trust(...)` 并放行**（首次连接零确认）；
  指纹变化时返回的文案是「请在连接设置里「忘记主机指纹」后重连」。
- 但全仓：`hostKeyDao` 只在 `AppContainer.kt:74,92` 出现（构造 + 注入工厂），
  `HostKeyDao.all()` / `forget()` **零 UI 调用**（`grep -rn 'hostKeyDao|忘记主机指纹' app core` → 只有上面那一句文案）。

**后果**：指纹变化（换了机器 / 上了新证书 / **真的被 MITM**）时，用户被指向一个**不存在的按钮**。
唯一出路是「删掉 App 数据」——代价太大，实际结果多半是用户放弃连接，或者误以为「这 App 坏了」。

TOFU 的「零确认」本身在个人自用场景可接受，但**必须给用户一条能自己纠偏的路**。
**建议**：连接编辑页（SFTP）加一块「已信任的主机指纹」列表 + 每项「忘记」按钮
（直接用已有的 `HostKeyDao.all()` / `forget()`），并在指纹变化错误文案里指向它。

### S7. `ME`/`QUERY_ALL_PACKAGES` 与「导出已安装应用」的组合：把私人文件管理器变成应用清单导出器

- `AndroidManifest.xml:25-27`：`QUERY_ALL_PACKAGES`（`tools:ignore="QueryAllPackagesPermission"`）。
- `ToolsScreens.kt` `AppsScreen` + `exportApk()`：列出全部已安装应用并按**包名/标签**导出 APK 到当前窗格。

**风险点不是「隐私统计」**（无第三方 SDK、无网络上报，这点做得对），而是**能力叠加**：
「枚举全部已安装应用 + 批量导出到任意可写位置（含已连接的远程存储）」在**设备被短暂接触**时，
可被用来把用户的应用清单与安装包整体拖走；且 `QUERY_ALL_PACKAGES` 在 Google Play 上属于敏感权限，
本项目自行分发尚可，但**若将来上架会被拒审**。

**建议**：至少把「导出已安装应用」放在明确的位置提示（当前目标是网络存储时弹一次确认）；
`QUERY_ALL_PACKAGES` 保留但补注释说明分发渠道约束（前次审计已指出该权限的注释过于简略）。

### S8. `RESULT` 侧：主机名/端口泄露与「无来源校验」的 FileProvider 组合（需实机验证）

- `file_paths.xml` 只有两条过宽的根：`<external-path path="." />` + `<cache-path path="." />`
  ⇒ FileProvider 可对**整个外部存储与 cache** 签发 URI（`DualPaneScreen.kt:1846/1879/1908/1974`、
  `PreviewScreen.kt:210`、`MediaScreen.kt:1037`）。
- 所有 `getUriForFile(...)` 调用都只带 `FLAG_GRANT_READ_URI_PERMISSION`，**没有 `ClipData`**（无从确认）；
  ACTION_VIEW/SEND 由用户主动选择目标，实际利用面窄。

**结论**：**不构成 Critical**（FileProvider 本身 `exported=false` + `grantUriPermissions=true`，
接收方需要显式授权）。但过宽的 path 根属于「最小权限」原则违例，建议按目录收窄成
`external-files-path` / `cache-path name="share" path="share/"`，
这样被恶意应用申请 URI 时的可及面被限制在「有意共享的目录」。

### S9. **明文流量是默认开着的**，且没有任何网络配置约束

`AndroidManifest.xml:39` `android:usesCleartextTraffic="true"`。
理由（连 http 的 NAS / Alist / MinIO）是真实的，但实现方式是**全应用、无差别放开**：

- `checkServerTrusted` 之外，**HTTP + Basic 认证**（`DavHttp.kt:110`）在局域网/公网是明文口令；
  FTP（非 FTPS）同样明文。
- 没有任何 `networkSecurityConfig` 去限定「仅私网 IP 允许明文」，也没有对公网明文给出警告。

**建议**：加 `res/xml/network_security_config.xml`，用
`<domain-config cleartextTrafficPermitted="true">` 只对**用户配置的连接主机**（或私网段）放开；
其余走 `cleartextTrafficPermitted="false"`。同时在连接编辑页对「http:// 公网地址」给出一次可见提示。

### S10. 备份/导出边界：`allowBackup=false` 正确，但**没有 `dataExtractionRules`**

Lint 本次新增的 `DataExtractionRules` 警告（`app/src/main/AndroidManifest.xml:31`）说得对：
Android 12+ 的**设备到设备迁移**与云备份走的是新机制。虽然 `allowBackup="false"` 在 12+ 上
对云备份仍生效，但**D2D 迁移的口径应以 `dataExtractionRules` 显式声明**，
否则「口令库（Keystore 密文）与 known_host 表**绝不应**跨设备迁移」这一意图只是隐式的。

**建议**：新增 `res/xml/data_extraction_rules.xml`（cloud-backup + device-transfer 全 exclude），
Manifest 加 `android:dataExtractionRules="@xml/data_extraction_rules"`。
一行配置换来「隐私边界显式化」，是这轮里成本最低的一条。

---

## 3. Optional / 与安全相关的结构性问题

- **O-S1 发布者的「来源可信」靠 CI 现成逻辑撑着**：`release` job 用 `versionName` 反推 tag，
  若 tag 已存在就**跳过发布**（`ci.yml:152-163`）。这意味着「改了源码但没改 versionName 的推送」**不会产出新包**——
  行为安全，但要确认 release notes 里没有旧包残留（建议 release 附件也带 SHA256SUMS，见 S3-4）。
- **O-S2 权限声明的注释过度乐观**：`LocalNetwork.kt:24-30` 自承 `legacyStorageGranted()` 不会被 UI 调用
  「保留它只是为了避免在 Manifest 常量缺失时 lint 报错」。**为过 lint 而留代码**是反模式（前次 N6），
  且它在安全审计里会误导读者以为「旧机型权限有处理」。建议删除或改成真实的权限请求入口。
- **O-S3 `PreviewPrefDao` / `HostKeyDao` / `ResumeDao` 都用裸 `insertWithOnConflict(..., 5)` 魔法数**：
  5 = `CONFLICT_REPLACE`。`ResumeDao` 的注释写了常量名，其它两处只写数字。
  与安全的关系：**「替换语义」在 `secret` 表上意味着口令覆盖是静默的**（`SecretStore.put` 已正确返回 bool 并让 UI 提示，好），
  但魔法数让后来者难以判断「这里是覆盖还是忽略冲突」。建议统一 `SQLiteDatabase.CONFLICT_REPLACE`。
- **O-S4 `key.properties` 里明文口令**（即便文件被 ignore）：建议读环境变量（见 S2-4）。
- **O-S5 没有 `SECURITY.md` / 漏洞反馈路径**（仓库根目录只有 `CHANGELOG.md`；`README.md` 不存在）。
  对一个会读任意路径、连任意主机的工具，建议加一段「安全模型与已知限制」（哪些是设计取舍、
  哪些是已知缺陷），这比任何一条代码修补都更能防止「用户误用」。
- **O-S6 `local.properties` / `key.properties` 已正确 ignore**（`git status --ignored` 确认），
  全历史无密钥（已核查）。**这一条是正面的**，记录在此防止后续回归。

---

## 4. 做得对的（别在重构里弄丢）

这一轮**明确核实**为有效的安全实现（不是复述前次报告）：

- **ZIP 路径穿越**：`ArchiveVfs.normalize()` 拒绝 `..`，且**有测试兜住**（前次 M1 变异实验：删掉即 1 failed）。
- **HTTP 服务的路径规整**：`RemoteHttpServer.normalizePath()`（`:205`）正确合并 `.`/`..`、越界返回 400 ——
  **逻辑是对的**（问题在零认证/零授权，见 S1）。HTML 转义（`:213`）与 `Content-Disposition` 的
  CRLF/引号过滤（`:158`）也在，文件名注入被挡住。
- **WebDAV 重定向**：跨主机**剥离 `Authorization`**（`DavHttp.kt:75`），307/308 与 WebDAV 非 GET 方法**保留方法与 body**，
  且这套策略**有 4 个单测**（`DavHttpTest`）。
- **口令存储**：`SecretStore` 用 Android Keystore 主密钥 AES-256-GCM + 每条独立 IV，
  密文入 SQLite；`put()` 返回成功与否并让 UI 据此拒绝保存（`ConnectionEditScreen.kt:282-296` 两条路径都处理了）。
  **不做假成功**，这是很多同类 App 的常见坑。
- **私钥导入**：应用私有目录 + `setReadable/Writable(false,false)` 收紧到 owner（`ConnectionEditScreen.kt:163-167`）。
- **SFTP TOFU**：首次记录、变化拒绝，并用 SHA-256 指纹（`SshKeys.fingerprint`，OpenSSH 同格式）。
- **日志**：`PanelApp.kt:16-17` 把 `Logx.enabled = BuildConfig.DEBUG` 且 release 只保留 WARN 以上 ——
  避免把路径/连接名/任务标题带进 logcat。前次审计说「注释里写的能关掉从未生效」，**现在已生效**。
- **依赖卫生**：`libs.versions.toml` 里 `bcprov` 显式约束到 1.85、`commons-lang3` 抬到 3.21.0 并**写了理由注释**；
  前次审计的「不只看报告、要扫 dex」的方法值得保留。
- **CI 硬门槛**：`.so` 必须 STORED + 16KB 对齐 + ELF LOAD ≥ 16KB，APK 缺失文件名直接失败（`ci.yml:74-127`）。

---

## 5. 与两份既有审计的对照（避免重复劳动）

| 既有条目 | 本轮处置 |
|---|---|
| C1/C2/C3（加密压缩包读写闭环） | **未复测**（v1.1.0 已修，超出本轮轴） |
| R1–R5（设置项不生效 / `c=` 隔离） | R5 的**安全后果**见本报告 S5；其余未复述 |
| N1/N2（`RemoteHttpServer` TOCTOU / 无写超时） | **升级为 S1 的一部分**：真实风险不是超时，而是零认证 + 绑 0.0.0.0 |
| M4（`RemoteHttpServer` 零测试） | 并入 S1 修法第 4 条 |
| `TrustAllX509TrustManager` ×8（前次记为 lint 项） | **升级为 S4**：给出「pin 单证书 + 不关主机名」的具体修法 |
| `DataExtractionRules`（前次记为「属提示」） | **升级为 S10**：明确要求显式声明 D2D/云备份边界 |
| §6 死代码清单 | 未复述；新增 1 条安全相关的死代码（`LocalNetwork.legacyStorageGranted` → O-S2） |
| UX 审计 U1–U20 | 不涉及（除 U11「远程管理 URL 不能复制」与本报告 S1 文案建议相关） |

**Lint 变化（本次实测 25 warnings，前次 20）**：新增 10 条 `IconLauncherShape`（`ic_launcher*.png` 满幅方形）
+ 2 条 `MonochromeLauncherIcon`（自适应图标缺 `<monochrome>`）+ 5 条 `IconDuplicates`
（`ic_launcher.png` 与 `ic_launcher_round.png` **内容完全相同**）——
这是 v1.3.9–v1.3.11 三轮图标改动引入的。真实后果两条：

1. 图标包/主题用 `<monochrome>` 层做单色取色时**没有可用层**，Android 16 QPR2+ 会退化成「给彩色图标染色」，
   观感不可控（这正是 v1.3.11 想解决的问题的另一半）。
2. `ic_launcher_round.png` 与 `ic_launcher.png` 逐字节相同 ⇒ 圆角图标在**圆形蒙版**的启动器上会被裁成方形。

**建议**：`mipmap-anydpi-v26/ic_launcher*.xml` 加 `<monochrome android:drawable="@mipmap/ic_launcher_monochrome" />`
（用 `tools/art/ic_launcher_glyph_108dp.png` 的单色版即可），并将 `ic_launcher_round` 从 anydpi 里去重。
另外 `mipmap-anydpi-v26` 目录在 `minSdk=26` 下是多余的（lint `ObsoleteSdkInt`），可合并到 `mipmap-anydpi`。

---

## 6. 建议的执行顺序（每批独立可回滚）

1. **批 S-1（发布安全，半天）**：S2 拆 debug/release 签名 + `key.properties` 读环境变量 + S10 `dataExtractionRules`。
   纯构建/配置改动，风险最低，**先把「正式身份被 debug 包污染」这个放大器关掉**。
2. **批 S-2（供应链，一天）**：S3 —— 镜像降为备选、生成并提交 `verification-metadata.xml`、
   固定 `distributionSha256Sum`、CI 动作 pin SHA、release 带 `SHA256SUMS`。
3. **批 S-3（服务面，一天）**：S1 —— 绑定地址可选 + 共享 token + 仅 GET/HEAD + TOCTOU/超时，
   并把 `normalizePath` 与「方法/头校验」提成纯函数补测试（同时关掉前次 M4）。
4. **批 S-4（TLS 信任模型，一到两天）**：S4 证书 pin + 不再恒 true 的 hostnameVerifier + 文案与首次确认；
   S9 的 `networkSecurityConfig` 可以并进这一批（同一主题：网络信任边界）。
5. **批 S-5（归属与可纠偏，一天）**：S5（`?c=` 落库口径与歧义拒绝）+ S6（主机指纹管理入口）。
6. **批 S-6（收口）**：S7 导出确认、S8 收窄 FileProvider 路径、O-S1–O-S6 的小项、§5 的图标项。

---

## 7. 复核用命令（每条结论都能重现）

```bash
# S1：服务绑定与认证
grep -n 'ServerSocket(' app/src/main/kotlin/com/u707t/panelfm/tools/RemoteHttpServer.kt
grep -cn 'Authorization\|token\|Referer\|Origin\|remoteAddress' app/src/main/kotlin/com/u707t/panelfm/tools/RemoteHttpServer.kt   # → 0

# S2：签名口径（实证）
./gradlew :app:signingReport --offline | grep -E 'Variant|Store|Alias'
grep -n 'signingConfig' app/build.gradle.kts

# S3：供应链
sed -n '1,30p' settings.gradle.kts
find . -name 'verification-metadata.xml' -o -name '*.lockfile'      # → 空
grep -n 'distributionSha256Sum' gradle/wrapper/gradle-wrapper.properties   # → 空
grep -n 'uses: .*@v' .github/workflows/ci.yml

# S4：TLS 信任
sed -n '19,36p' core/vfs-api/src/main/kotlin/com/u707t/panelfm/core/vfs/TlsTrust.kt
grep -n 'hostnameVerifier' core/vfs-webdav/src/main/kotlin/com/u707t/panelfm/core/vfs/webdav/DavHttp.kt \
  core/vfs-s3/src/main/kotlin/com/u707t/panelfm/core/vfs/s3/S3Client.kt

# S5：会话隔离退化
grep -n 'sameMount' -A6 core/vfs-api/src/main/kotlin/com/u707t/panelfm/core/vfs/VfsUri.kt

# S6：主机指纹无可达入口
grep -rn 'hostKeyDao\|忘记主机指纹' app core | grep -v test

# S9/S10：Manifest
grep -n 'usesCleartextTraffic\|allowBackup\|dataExtractionRules' app/src/main/AndroidManifest.xml
ls app/src/main/res/xml/          # → 仅 file_paths.xml

# 密钥与历史（正面结论）
git status --ignored --short | grep -E 'key.properties|local.properties'
git log --all --diff-filter=A --name-only --pretty=format: | grep -Ei '\.(jks|keystore|p12|pem)$'   # → 空
```

---

*审计人：AI（只读审计；`testDebugUnitTest --rerun-tasks` / `lintDebug` / `assembleDebug` / `signingReport` 均实际执行；未改动任何源码。）*

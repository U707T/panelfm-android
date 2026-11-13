# APKS / XAPK / APKM → APK（设计说明）

> 对应 MT 管理器的「转为 APK」（MT 2.8.4 起，2.16.6 补了「选择要合并的子 APK」）。
> 实现在 `core/apk`（纯 JVM，可单测），UI 在 `app/ui/browser/DialogsApkConvert.kt`。
> 本文只写**口径与边界**；代码结构见文末地图。

## 1. 输入与两条路径

三种外壳本质都是 zip，里面装若干 APK：

| 后缀 | 典型结构 |
|---|---|
| `.apks` | bundletool 产物：`splits/base-master.apk`、`splits/base-arm64_v8a.apk`、`splits/base-xxhdpi.apk`…（也可能是 `universal.apk` 或 `standalones/`） |
| `.xapk` | 根目录 `com.foo.apk` + `split_config.*.apk` + `manifest.json`、`icon.png` |
| `.apkm` | 根目录 `base.apk` + `split_config.*.apk` + `info.json`、`meta.sai_v2.json` |

转换走哪条路**由包内容决定**，不由后缀决定：

1. **直接取出**（最优）：包内有 `universal.apk`，或 `standalones/` 里有**与本机 ABI 匹配**的完整包
   → 原样拷出来。签名还是官方的，可以覆盖安装，也不用合并；
2. **合并 + 重签**：`base` + 选中的分包 → 打成一个 APK → 用内置测试证书重签。

## 2. 合并口径（哪些进、哪些不进）

| 条目 | 处理 | 原因 |
|---|---|---|
| base 的 `AndroidManifest.xml` | **换成打过补丁的** | 见 §3 |
| base 的其它条目 | 全部保留（旧签名文件除外） | — |
| 分包的 `lib/**/*.so` | 并入（可按 ABI 过滤） | 原生库与资源表无关，并进去就能用 |
| 分包的 `assets/**` | 并入（同名以 base 为准） | 同上 |
| 分包的 `classes*.dex` | 并入，改名接续 `classesN.dex` | 类加载器只从 APK 根上按 `classes*.dex` 顺序找 |
| 分包的 `res/**`、`resources.arsc` | **不并** | 见 §4 |
| 分包的 `AndroidManifest.xml` | **不并** | 见 §4（功能模块） |
| `META-INF/*.SF|*.RSA|*.DSA|*.EC`、`MANIFEST.MF` | 丢掉 | 改了 zip 内容，旧签名必然失效，留着只会让安装器报「签名无效」 |

同名条目一律以 base 为准（分包里的是同一份文件的重复拷贝）。

## 3. 清单补丁（为什么必须改清单）

分包里的 base APK 是「需要跟分包一起装」的形态，单独装会被系统拒。只改三处：

| 属性 | 改成 | 不改会怎样 |
|---|---|---|
| `android:isSplitRequired` | `false` | `INSTALL_FAILED_MISSING_SPLIT`（Android 会让它必须带分包） |
| `android:requiredSplitTypes` / `android:splitTypes` | 置空（XML 里等于没写） | Android 12+ 按分包类型校验，同样缺分包即失败 |
| `<uses-split android:name="…"/>` 元素 | 改名成 `x-uses-split`（不认识的标签，系统解析器整段跳过） | 上面声明了「依赖某个功能模块」，模块不存在 → 安装失败 |

做法是**最小 AXML 补丁**（`Axml.kt`）：定位属性 → 改类型化值 / 置空 / 元素改名。
只在需要追加新字符串（`x-uses-split`）时重建字符串池，其余字节原样保留；
不增删元素、不动资源表。「不改任何东西时序列化与原文件逐字节相同」有单测锁着。

## 4. 为什么不做资源表合并（已知限制）

密度 / 语言分包里装的是 `res/**` + 自己的 `resources.arsc`，运行时靠 `AssetManager.addApkAssets`
把多张资源表叠起来。**简单把文件拼进 base 的 zip 是没有用的** —— base 的资源表里没有这些条目的引用，
拼进去只是白占体积；要让它们生效必须重写 `resources.arsc`（新增 type/config 条目并调整所有偏移），
那是 aapt2 级别的工作量，且和本项目「不做 Arsc 编辑」的边界冲突。

所以当前口径是：

- **功能可用**：代码、原生库、assets 都在，App 能装能跑；
- **观感降级**：高密度图片回退到 base 的基础密度（列表图标略糊）；
- **多语言降级**：语言分包没并，UI 回退到 base 的默认语言。

> MT 能做得更完整（它自带 Arsc 编辑库）。这一条差距是**明确记录**的，不是漏做。

**功能模块（动态 feature）**默认**不合并**：模块清单里声明的 Activity / Service / Provider
没法并进 base 清单（要合并清单就得重排资源 ID），并了文件也起不了组件；而且并进来的 dex
可能与 App 自己的 SplitCompat 逻辑打架。默认只并 ABI 分包，勾选后可强行并文件。

## 5. 签名

改动过 zip 的 APK 必须重签，否则装不上。

- 用 **apksig**（Apache-2.0，纯 Java、零传递依赖，版本跟 AGP 走）签 **v2 + v3**；
  APK 自身 `minSdk < 24` 时补 v1（老系统只认 v1）。
- `.so` 按 **16 KB** 页对齐（Android 15 的 16 KB 页设备要求），并保持不压缩
  —— 仅当清单写了 `android:extractNativeLibs="false"`（分包包基本都是）。
- 证书是**首次转换时本地生成**的 RSA 2048 自签证书（30 年），存成
  `noBackupFilesDir/apk-signing/panelfm-test.p12`（应用私有、**不进云备份、不进仓库**）。
  用 bcprov 的 ASN.1 直出 X.509 —— 只为一张自签证书引 bcpkix（约 1 MB）不划算。
- 与 MT 的内置测试 key 同一性质：**签名与官方包不同**，覆盖安装前要先卸载官方版本。
  换设备 / 清数据后签名会变，已装的包需要卸载重装。

## 6. 代码地图

```
core/apk/
  Axml.kt            最小 AXML：读元素/属性、改布尔、置空属性、元素改名、字符串池重建
  ApkManifest.kt     ApkManifestInfo（包名/版本/minSdk/split/isSplitRequired…）+ 清单补丁
  ApkBundle.kt       解析 .apks/.xapk/.apkm：找 base、给分包分类（ABI/密度/语言/feature）、挑 standalone
  ApkPlan.kt         ConvertOptions / ConvertPlan：哪些分包要并、哪些跳过、为什么
  ApkMerger.kt       zip 层合并（.so 保持 STORED、dex 改名接续、同名以 base 为准）
  ApkSigning.kt      内置测试证书（生成/复用）+ apksig 重签
  ApksToApk.kt       inspect → plan → convert（临时目录 → 合并 → 重签 → 产物）
app/ui/browser/
  DialogsApkConvert.kt   对话框：解析结果 / ABI 二选一 / 进度 / 取消 / 写入目标目录
  TypeActions.kt         .apks/.xapk/.apkm 的「转为 APK」菜单项
```

## 7. 验证

单测（`core/apk`，16 例；`src/test/resources/manifest-*.bin` 是 `aapt2 link` 的真实产物，
保证解析器面对的是真实的 AXML 布局而不是手搓的玩具字节）：

- `AxmlTest`：真实 aapt2 产物 + 手搓 UTF-8 池；「不改即逐字节相同」；三处补丁；其它字段不丢；
- `ApkBundleTest`：base / ABI / 密度 / feature 分类、universal、standalones 按 ABI 挑、`.xapk` 扁平结构、
  同一分包在多个 ABI 变体里的副本去重（真实 `.apks` 里 `base-af.apk` / `base-af_2.apk` 内容相同）；
- `ApkConvertTest`：端到端合并 + **apksig 校验签名**（v2）+ `.so` 保持 STORED + 密度分包的 `res/`
  不进来 + 旧签名文件被丢掉 + ABI 白名单同时过滤 base 自带的多余 ABI + universal 直接取出逐字节相同
  + 签名材料复用。

真机待验（沙箱没有设备，`adb` 起不来）：

1. 从 APKPure 下个 `.xapk`（多 ABI）→ 长按 →「转为 APK」→ 选「仅本机 ABI」→ 产物安装成功；
2. 同样包选「全部 ABI」→ 产物体积更大、同样能装；
3. 用 PanelFM 自己「提取安装包」导出的 `.apks`（微信这类 split 应用）→ 转 APK → 安装成功；
4. `.apks` 带 `standalones/` 时提示「直接取出」，产物与官方签名一致（可覆盖安装）；
5. 转换中途点「取消转换」→ 不留下半个 APK。

## 8. 安全 / 合规

- 只处理**用户自己选中的本地文件**，不联网、不注入、不改目标应用的行为；
- 合并产物用内置测试证书签名，签名身份与原应用无关，**不要拿去二次分发**；
- 证书私钥始终在应用私有目录（`noBackupFilesDir`，云备份也带不走）；仓库里没有任何 keystore
  （`.gitignore` 也挡了 `*.p12`）。

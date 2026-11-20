package com.u707t.panelfm.core.apk

import java.io.File
import java.util.zip.ZipFile

/**
 * 加固（壳）检测结果。
 *
 * @param vendor 加固厂商（`360` / `腾讯` / `梆梆` …）
 * @param evidence 命中的证据（包内条目名或 Application 类名），给用户一个可复核的依据
 */
data class Hardening(val vendor: String, val evidence: String) {
    /** MT 的写法：`360 加固` */
    fun label(): String = "$vendor 加固"
}

/**
 * 加固检测（MT 的「加固状态：未检测到加固 / xx 加固」）。
 *
 * ⚠️ **这是特征库匹配，不是通用判定**：加固厂商会改名、会清特征，漏报是正常的；
 * 反过来，某些正常应用也可能自带同名 `.so`（比如游戏引擎），所以结果只作参考。
 * 与 MT 的差别：MT 会进一步扫 dex 里的壳入口类，本项目**不解析 dex**（README §6 的边界），
 * 只用两条**廉价且稳定**的信号：
 *
 *  1. 包内条目名：各家壳的 `lib/<abi>/libxxx.so`、`assets/xxx.dat`；
 *  2. `AndroidManifest` 里 `<application android:name>` 的壳入口类（`com.stub.StubApp` 等）。
 *
 * 两条都不碰 dex 内容，所以几万条目的包也是毫秒级。
 */
object ApkHardening {

    const val NONE_LABEL = "未检测到加固"

    /**
     * 条目名特征：`vendor` → 包内路径片段（大小写不敏感，包含即命中）。
     *
     * 只放**几乎不会误判**的私有库名；`libexec.so` 这种常见名字排在最后，因为它同时被
     * 爱加密和其它壳使用（命中时按「爱加密」报，并附上条目名让用户自己判断）。
     */
    private val ENTRY_RULES: List<Pair<String, List<String>>> = listOf(
        "360" to listOf("libjiagu", "assets/libjiagu"),
        "腾讯" to listOf(
            "libshella-", "libshellx-", "libshell-super", "libtosprotection", "libtup.so",
            "assets/0oo00l111l1l", "assets/tosversion",
        ),
        "梆梆" to listOf("libsecshell", "libdexhelper", "libsecexe", "libsecmain", "assets/secdata"),
        "阿里" to listOf("libmobisec", "assets/mobisec", "libaliprotect"),
        "网易易盾" to listOf("libnesec", "assets/nesec"),
        "百度" to listOf("libbaiduprotect", "baiduprotect"),
        "娜迦" to listOf("libchaosvmp", "libddog", "libfdog", "libnagain"),
        "顶象" to listOf("libx3g", "assets/x3g"),
        "瑞数" to listOf("librsprotect", "assets/rsprotect"),
        "爱加密" to listOf("libexecmain.so", "ijiami", "libexec.so"),
    )

    /** `<application android:name>` 的壳入口类（加固后真实 Application 会被换成这些） */
    private val APPLICATION_RULES: List<Pair<String, String>> = listOf(
        "360" to "com.stub.stubapp",
        "360" to "com.qihoo.util.stubapplication",
        "梆梆" to "com.secneo.apkwrapper",
        "腾讯" to "com.tencent.stubshell",
        "腾讯" to "com.shell.superapplication",
        "百度" to "com.baidu.protect",
        "阿里" to "com.ali.mobisecenhance",
        "网易易盾" to "com.netease.nis.wrapper",
        "爱加密" to "com.ijm.dataformat",
        "娜迦" to "s.h.e.l.l.",
    )

    /**
     * 检测（纯函数，便于单测）。
     *
     * @param entryNames 包内全部条目名（`ZipFile.entries()`）
     * @param applicationClass 清单里的 `<application android:name>`（可空）
     */
    fun detect(entryNames: Collection<String>, applicationClass: String? = null): Hardening? {
        val lowerNames = entryNames.map { it.lowercase(java.util.Locale.ROOT) }
        for ((vendor, fragments) in ENTRY_RULES) {
            for (fragment in fragments) {
                val hit = lowerNames.firstOrNull { it.contains(fragment) } ?: continue
                return Hardening(vendor, hit)
            }
        }
        val app = applicationClass?.takeIf { it.isNotBlank() } ?: return null
        val lowerApp = app.lowercase(java.util.Locale.ROOT)
        for ((vendor, stub) in APPLICATION_RULES) {
            if (lowerApp.startsWith(stub)) return Hardening(vendor, app)
        }
        return null
    }

    /** 直接从 APK 文件读（读不出来返回 null = 按「未检测到」显示，不阻断信息展示） */
    fun detect(file: File, applicationClass: String? = null): Hardening? = runCatching {
        ZipFile(file).use { zip ->
            detect(zip.entries().asSequence().map { it.name }.toList(), applicationClass)
        }
    }.getOrNull()
}

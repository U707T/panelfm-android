package com.u707t.panelfm.core.apk

/**
 * 从 `AndroidManifest.xml`（AXML）里读出来的、转 APK 需要的字段。
 *
 * @param splitName      `<manifest split="…">`；null / 空 = 基础包（base）
 * @param isFeatureSplit `android:isFeatureSplit="true"`（动态功能模块）
 * @param isSplitRequired `android:isSplitRequired="true"`（单独装 base 会被系统拒绝：INSTALL_FAILED_MISSING_SPLIT）
 * @param requiredSplitTypes / splitTypes  Android 12+ 的分包类型声明，同样会拦安装，合并后必须清空
 * @param extractNativeLibs `android:extractNativeLibs`：false 时 `.so` 必须**不压缩**且页对齐
 */
data class ApkManifestInfo(
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    val splitName: String?,
    val isFeatureSplit: Boolean,
    val isSplitRequired: Boolean,
    val requiredSplitTypes: String?,
    val splitTypes: String?,
    val extractNativeLibs: Boolean?,
    val usesSplits: List<String>,
) {
    /** 基础包（没有 split 属性） */
    val isBase: Boolean get() = splitName.isNullOrBlank()
}

object ApkManifestReader {

    fun parse(bytes: ByteArray): ApkManifestInfo {
        val xml = AxmlFile.parse(bytes)
        val elements = xml.elements()
        val manifest = elements.firstOrNull { it.name == "manifest" }
            ?: throw IllegalArgumentException("清单缺少 <manifest>")
        val usesSdk = elements.firstOrNull { it.name == "uses-sdk" }
        val application = elements.firstOrNull { it.name == "application" }
        return ApkManifestInfo(
            packageName = manifest.stringAttr("package"),
            versionName = manifest.stringAttr("versionName"),
            versionCode = manifest.intAttr("versionCode"),
            minSdk = usesSdk?.intAttr("minSdkVersion")?.toInt()
                ?: manifest.intAttr("minSdkVersion")?.toInt(),
            splitName = manifest.stringAttr("split"),
            isFeatureSplit = manifest.boolAttr("isFeatureSplit") == true,
            isSplitRequired = manifest.boolAttr("isSplitRequired") == true,
            requiredSplitTypes = manifest.stringAttr("requiredSplitTypes"),
            splitTypes = manifest.stringAttr("splitTypes"),
            extractNativeLibs = application?.boolAttr("extractNativeLibs"),
            usesSplits = elements.filter { it.name == "uses-split" }
                .mapNotNull { it.stringAttr("name") }
                .filter { it.isNotBlank() },
        )
    }
}

/**
 * 把「分包里的 base APK」改成「能单独安装的 APK」。
 *
 * 只做三件事，全部是清单层面的最小改动（不碰资源表、不增删元素）：
 *  1. `android:isSplitRequired="true"` → `false`；
 *  2. `android:requiredSplitTypes` / `android:splitTypes` → 置空（Android 12+ 会按它校验分包）；
 *  3. `<uses-split>` 改名成 `x-uses-split`（不认的标签，系统解析器整段跳过）
 *     —— 合并后这些分包已经不存在，留着会让安装直接失败。
 *
 * 签名的部分不在这一步（见 [ApkSignerService]）。
 */
object ApkManifestPatcher {

    data class PatchResult(val bytes: ByteArray, val changes: List<String>)

    fun patchForStandalone(bytes: ByteArray): PatchResult {
        val xml = AxmlFile.parse(bytes)
        val changes = LinkedHashSet<String>()
        for (element in xml.elements()) {
            for (attribute in element.attributes()) {
                when (attribute.name) {
                    "isSplitRequired" -> if (attribute.boolValue != false) {
                        attribute.setBoolean(false)
                        changes += "isSplitRequired=false"
                    }
                    "requiredSplitTypes", "splitTypes" -> if (attribute.dataType != 0) {
                        attribute.clearValue()
                        changes += "${attribute.name} 置空"
                    }
                }
            }
            if (element.name == "uses-split") {
                element.renameTo("x-uses-split")
                changes += "移除 uses-split 声明"
            }
        }
        return PatchResult(xml.toBytes(), changes.toList())
    }
}

package com.u707t.panelfm.core.common

/**
 * API level → Android 版本名（MT 的「目标系统 `Android 14 (API 34)`」写法）。
 *
 * 为什么不用 `Build.VERSION.RELEASE` 之类：这里描述的是**包里声明的** SDK 值
 * （`minSdkVersion` / `targetSdkVersion`），跟本机跑什么系统无关；
 * 系统只给 API level，版本名必须自己查表。
 *
 * 表只列本项目 minSdk 之后的版本；更老的包（minSdk 21 以下）少见但可能出现在
 * 别人的 APK 里 —— 未知 API 退化成 `API nn`，不猜。
 */
object AndroidApi {

    /** API → 版本名（`31` → `12`、`32` → `12L`：Android 12 的 Large screen 变体） */
    private val VERSION_NAMES: Map<Int, String> = mapOf(
        21 to "5.0",
        22 to "5.1",
        23 to "6.0",
        24 to "7.0",
        25 to "7.1",
        26 to "8.0",
        27 to "8.1",
        28 to "9",
        29 to "10",
        30 to "11",
        31 to "12",
        32 to "12L",
        33 to "13",
        34 to "14",
        35 to "15",
        36 to "16",
        37 to "17",
    )

    /** 版本名（`34` → `14`）；未知返回 null */
    fun versionName(api: Int): String? = VERSION_NAMES[api]

    /**
     * 展示文案：`Android 14 (API 34)`。
     *
     * @param api 0 / 负数（清单里没写、解析失败）→ `—`，不显示成 `API 0`
     */
    fun label(api: Int): String {
        if (api <= 0) return "—"
        val name = VERSION_NAMES[api] ?: return "API $api"
        return "Android $name (API $api)"
    }
}

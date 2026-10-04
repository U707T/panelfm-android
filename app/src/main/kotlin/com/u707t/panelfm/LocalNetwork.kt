package com.u707t.panelfm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 17 (API 37) 起访问局域网需要 ACCESS_LOCAL_NETWORK 运行时权限；
 * 未授权时所有局域网协议都会「连接超时」，因此必须在连接前检查并给出明确引导。
 */
object LocalNetwork {

    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    val required: Boolean get() = Build.VERSION.SDK_INT >= 37

    fun isGranted(context: Context): Boolean {
        if (!required) return true
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    fun isNeverAskAgain(context: Context): Boolean = false

    /** 兼容旧 API 的自检（避免 lint 报 Manifest 常量缺失） */
    fun legacyStorageGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /** 当前是否在 Wi-Fi / 有线网络下（用于「缩略图仅 Wi-Fi 加载」策略） */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}

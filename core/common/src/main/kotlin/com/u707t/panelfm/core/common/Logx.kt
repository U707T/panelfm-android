package com.u707t.panelfm.core.common

import android.util.Log

/** 轻量日志：tag 统一前缀，release 下可整体关闭。 */
object Logx {
    var enabled: Boolean = true
    var minLevel: Int = Log.DEBUG

    fun d(tag: String, msg: String) { if (enabled && minLevel <= Log.DEBUG) Log.d(T, "[$tag] $msg") }
    fun i(tag: String, msg: String) { if (enabled && minLevel <= Log.INFO) Log.i(T, "[$tag] $msg") }
    fun w(tag: String, msg: String, t: Throwable? = null) { if (enabled) Log.w(T, "[$tag] $msg", t) }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (enabled && minLevel <= Log.ERROR) Log.e(T, "[$tag] $msg", t)
    }

    private const val T = "PanelFM"
}

package com.u707t.panelfm

import android.app.Application
import com.u707t.panelfm.core.common.Logx

class PanelApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // release 只保留 WARN 以上：DEBUG 日志会记录路径、连接名、任务标题等，
        // 既造成噪音也可能把用户隐私带进 logcat（旧实现 Logx.enabled 恒为 true，
        // 注释里写的「release 可整体关闭」从未生效）。
        Logx.enabled = BuildConfig.DEBUG
        Logx.minLevel = if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.WARN
        val start = System.currentTimeMillis()
        container = AppContainer(this)
        Logx.i("PanelApp", "init in ${System.currentTimeMillis() - start}ms")
    }

    override fun onTerminate() {
        container.close()
        super.onTerminate()
    }
}

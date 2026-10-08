package com.u707t.panelfm

import android.app.Application
import com.u707t.panelfm.core.common.Logx

class PanelApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // release 只保留 WARN 以上（DEBUG/INFO 会记录路径、连接名、任务标题等，不进 logcat）。
        // 过滤统一交给 minLevel —— 旧实现 `enabled = BuildConfig.DEBUG` 在 release 连 WARN/ERROR
        // 一起关掉（「WARN 以上保留」的意图空转）；同时 Logx.w 补上 minLevel 检查，四个等级同语义。
        // 需要 release 全静默时，改这一处 `Logx.enabled = false` 即可。
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

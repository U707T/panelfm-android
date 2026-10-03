package com.u707t.panelfm

import android.app.Application
import com.u707t.panelfm.core.common.Logx

class PanelApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val start = System.currentTimeMillis()
        container = AppContainer(this)
        Logx.i("PanelApp", "init in ${System.currentTimeMillis() - start}ms")
    }

    override fun onTerminate() {
        container.close()
        super.onTerminate()
    }
}

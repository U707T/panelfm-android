package com.u707t.panelfm.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.u707t.panelfm.core.common.Logx

/**
 * 传输前台服务（M9 完整实现通知栏进度）。
 * 当前职责：任务运行时保活，避免系统回收进程导致长传输中断。
 */
class TransferService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logx.i("TransferService", "onStartCommand ${intent?.action}")
        return START_STICKY
    }

    override fun onDestroy() {
        Logx.i("TransferService", "onDestroy")
        super.onDestroy()
    }
}

package com.u707t.panelfm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.u707t.panelfm.MainActivity
import com.u707t.panelfm.PanelApp
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.transfer.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 传输前台服务（M9）：
 *  - 有任务在跑时把 App 提升到前台（dataSync），通知栏显示进度/速率/当前文件
 *  - 任务全部结束或取消后自动降级退出
 *  - 纯本地实现，不引入 WorkManager / 任何统计 SDK
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val container = (application as PanelApp).container
        collectJob = scope.launch {
            // 用 taskEvents：任务状态（进度 / 暂停 / 冲突 / 完成）变化时都要刷新通知与前台状态
            container.engine.taskEvents.collectLatest { tasks ->
                val active = tasks.filter {
                    val s = it.state.value
                    s !is TaskState.Done && s !is TaskState.Cancelled && s !is TaskState.Failed
                }
                if (active.isEmpty()) {
                    stopForegroundCompat()
                    stopSelf()
                    return@collectLatest
                }
                val first = active.first()
                val state = first.state.value
                val text = when (state) {
                    is TaskState.Running ->
                        "${state.currentName} · ${Fmt.transferred(state.doneBytes, state.totalBytes)} · ${Fmt.speed(state.speedBps)}"
                    is TaskState.Paused -> "已暂停"
                    is TaskState.WaitingConflict -> "等待冲突处理"
                    else -> first.title
                }
                val progress = when (state) {
                    is TaskState.Running -> if (state.totalBytes > 0) ((state.doneBytes * 100) / state.totalBytes).toInt() else 0
                    else -> 0
                }
                val notification = buildNotification(
                    title = if (active.size > 1) "${first.title}（共 ${active.size} 个任务）" else first.title,
                    text = text,
                    progress = progress,
                    indeterminate = progress <= 0,
                )
                startForegroundSafe(notification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知渠道 + 立即进入前台，避免 Android 8+ 的 ANR/崩溃
        createChannel()
        startForegroundSafe(buildNotification("PanelFM", "传输任务准备中…", 0, indeterminate = true))
        return START_STICKY
    }

    private fun startForegroundSafe(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Logx.w("TransferService", "startForeground failed: ${it.message}") }
    }

    private fun stopForegroundCompat() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun buildNotification(title: String, text: String, progress: Int, indeterminate: Boolean): Notification {
        // 点通知回到 App（旧实现没有 contentIntent：点通知毫无反应，用户以为卡死）
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "传输任务", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "后台文件传输进度"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
        Logx.i("TransferService", "onDestroy")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "panelfm.transfer"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, TransferService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferService::class.java)) }
        }
    }
}

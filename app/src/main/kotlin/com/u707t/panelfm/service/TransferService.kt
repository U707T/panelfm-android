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
import kotlinx.coroutines.CoroutineExceptionHandler
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
enum class TransferForegroundStatus { Stopped, Starting, Running, Failed, TimedOut }

/** Service lifecycle is asynchronous; callers must observe this state instead of trusting startService(). */
object TransferForegroundState {
    private val mutableStatus = kotlinx.coroutines.flow.MutableStateFlow(TransferForegroundStatus.Stopped)
    val status: kotlinx.coroutines.flow.StateFlow<TransferForegroundStatus> = mutableStatus
    internal fun publish(value: TransferForegroundStatus) { mutableStatus.value = value }
}

class TransferService : Service() {

    /**
     * 服务级异常兜底：SupervisorJob 只隔离子协程，**不阻止**未捕获异常上抛到主线程默认处理器。
     * 这里是独立 scope（v2.0.19 只给 AppContainer 加过 handler），漏掉就是后台传输时崩溃/通知冻住。
     */
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Logx.e("TransferService", "uncaught in service scope: ${e.message}", e)
    }
    private val scope = CoroutineScope(SupervisorJob() + crashGuard + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    @Volatile private var timeoutHandled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val container = (application as PanelApp).container
        collectJob = scope.launch {
            // 用 taskEvents：任务状态（进度 / 暂停 / 冲突 / 完成）变化时都要刷新通知与前台状态
            container.engine.taskEvents.collectLatest { tasks ->
                val active = tasks.filter { needsTransferForeground(it.state.value) }
                if (active.isEmpty()) {
                    stopForegroundCompat()
                    TransferForegroundState.publish(TransferForegroundStatus.Stopped)
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
                    is TaskState.Cancelling -> "正在取消操作…"
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
        // 通知渠道 + 立即进入前台，避免 Android 8+ 的 ANR/崩溃。
        // ⚠️ startForeground 失败时不能「装作没事」继续运行：系统要求
        // startForegroundService 后 5 秒内必须进入前台，否则直接抛
        // ForegroundServiceDidNotStartInTimeException 崩掉进程。
        // 失败时主动 stopSelf()，让传输在应用进程内继续（数据不丢，只是没通知）。
        createChannel()
        val ok = startForegroundSafe(buildNotification("PanelFM", "传输任务准备中…", 0, indeterminate = true))
        if (!ok) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startForegroundSafe(notification: Notification): Boolean {
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Logx.w("TransferService", "startForeground failed: ${it.message}") }
            .isSuccess
        if (started) {
            if (!timeoutHandled) TransferForegroundState.publish(TransferForegroundStatus.Running)
        } else {
            TransferForegroundState.publish(TransferForegroundStatus.Failed)
            stopSelf()
        }
        return started
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

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int) {
        stopForTimeout(startId)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        if (fgsType and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0) {
            stopForTimeout(startId)
        }
    }

    private fun stopForTimeout(startId: Int) {
        if (timeoutHandled) return
        timeoutHandled = true
        TransferForegroundState.publish(TransferForegroundStatus.TimedOut)
        Logx.w("TransferService", "dataSync foreground-service time limit reached; cancelling active transfers")
        runCatching {
            (application as PanelApp).container.engine.tasks.value
                .filter { needsTransferForeground(it.state.value) }
                .forEach { it.cancel() }
        }.onFailure { Logx.e("TransferService", "cancel after FGS timeout failed: ${it.message}", it) }
        stopForegroundCompat()
        stopSelf(startId)
    }

    override fun onDestroy() {
        collectJob?.cancel()
        // 异常销毁 / 用户 stopService 时通知不一定被系统立刻清掉：显式移除
        stopForegroundCompat()
        scope.cancel()
        if (TransferForegroundState.status.value !in setOf(
                TransferForegroundStatus.Failed,
                TransferForegroundStatus.TimedOut,
            )
        ) TransferForegroundState.publish(TransferForegroundStatus.Stopped)
        Logx.i("TransferService", "onDestroy")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "panelfm.transfer"
        private const val NOTIFICATION_ID = 1001

        /** @return false = 没能进入前台（调用方必须提示：任务切后台可能被系统中断） */
        fun start(context: Context): Boolean = runCatching {
            TransferForegroundState.publish(TransferForegroundStatus.Starting)
            context.startForegroundService(Intent(context, TransferService::class.java))
            true // request accepted only; observe TransferForegroundState.status for Running/Failed.
        }.onFailure {
            TransferForegroundState.publish(TransferForegroundStatus.Failed)
            Logx.e("TransferService", "startForegroundService failed: ${it.message}", it)
        }.getOrDefault(false)

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferService::class.java)) }
        }
    }
}

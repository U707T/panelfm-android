package com.u707t.panelfm.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import com.u707t.panelfm.core.common.Logx

/** `PackageInstaller.Session.commit()` 的结果广播（发送端见 `installApkBundle`）。 */
const val ACTION_INSTALL_RESULT = "com.u707t.panelfm.INSTALL_RESULT"

/**
 * `PackageInstaller` 状态码 → 用户可读文案（纯函数，JVM 单测覆盖）。
 *
 * 为什么必须有这一层：`.apks` 安装的**唯一**结果出口就是 `commit(IntentSender)` 的那条广播。
 * 旧实现只 `commit` 不接收，普通应用必得的 `STATUS_PENDING_USER_ACTION` 无人处理，
 * 于是「会话建好、分包写完、什么界面都不弹」。
 */
object InstallStatus {

    /** 需要调用方 `startActivity(EXTRA_INTENT)` 拉起系统确认界面 */
    const val PENDING_USER_ACTION: Int = PackageInstaller.STATUS_PENDING_USER_ACTION
    const val SUCCESS: Int = PackageInstaller.STATUS_SUCCESS

    fun message(status: Int, detail: String?): String {
        val extra = detail?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
        return when (status) {
            PackageInstaller.STATUS_SUCCESS -> "安装完成"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "等待确认安装"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "安装已取消"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "安装被系统拒绝：请允许 PanelFM 安装应用" + extra
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "安装冲突：已装版本与安装包签名 / 包名不一致，需先卸载原应用" + extra
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "安装包与当前系统不兼容" + extra
            PackageInstaller.STATUS_FAILURE_INVALID -> "安装包无效（可能缺少 base 或分包不匹配）" + extra
            PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足，安装失败" + extra
            else -> "安装失败（status=$status）" + extra
        }
    }
}

/**
 * 接收安装会话的结果。
 *
 * - `STATUS_PENDING_USER_ACTION`：系统把「去确认」的 Intent 放在 `EXTRA_INTENT` 里，
 *   **必须由调用方拉起** —— 不处理就等于安装流程停在这里；
 * - 其余状态：用 Toast 报结果（含「签名不一致」这类必须说清楚的原因）。
 *
 * 注册方式：manifest（`exported=false`）。`PendingIntent` 是 PanelFM 自己创建的，
 * 广播以本应用身份发出，不需要导出。
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == InstallStatus.PENDING_USER_ACTION) {
            val confirm = confirmIntent(intent)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure {
                        Logx.w("InstallResult", "startActivity failed: ${it.message}", it)
                        toast(context, "无法打开系统安装界面：${it.message ?: "未知原因"}")
                    }
                return
            }
            toast(context, "系统安装界面不可用（缺少确认 Intent）")
            return
        }
        Logx.i("InstallResult", "status=$status detail=${detail.orEmpty()}")
        toast(context, InstallStatus.message(status, detail))
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun toast(context: Context, text: String) {
        runCatching { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}

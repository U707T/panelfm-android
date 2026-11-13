package com.u707t.panelfm.service

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `.apks` 安装结果文案（v2.4.1）。
 *
 * 回归点：旧实现只 `commit(IntentSender)`、不接收结果，`STATUS_PENDING_USER_ACTION`
 * 无人处理 → 点了「安装」什么都不弹。这里锁住「每个状态都有话说」，尤其是
 * 签名/包名冲突必须明确提示「先卸载原应用」，而不是笼统一句失败。
 */
class InstallStatusTest {

    @Test
    fun `成功与等待确认`() {
        assertEquals("安装完成", InstallStatus.message(InstallStatus.SUCCESS, null))
        assertEquals(
            "等待确认安装",
            InstallStatus.message(InstallStatus.PENDING_USER_ACTION, null),
        )
    }

    @Test
    fun `签名冲突要指名卸载`() {
        val text = InstallStatus.message(PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        assertTrue(text, text.contains("卸载"))
        assertTrue(text, text.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
    }

    @Test
    fun `未知状态也要带上原始码与原因`() {
        val text = InstallStatus.message(99, "boom")
        assertTrue(text, text.contains("99"))
        assertTrue(text, text.contains("boom"))
    }
}

package com.u707t.panelfm.ui.connections

import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.vfs.s3.S3Config
import com.u707t.panelfm.core.vfs.webdav.DavConfig
import org.junit.Assert.*
import org.junit.Test

class ConnectionEditRegressionTest {
    @Test fun disabledConnectionTrustOverridesEnabledGlobalDefault() {
        for (type in listOf(ConnectionType.WEBDAV, ConnectionType.S3, ConnectionType.FTPS)) {
            val existing = ConnectionConfig(type = type, name = "test", host = "host", port = 443)
            val state = ConnectionEditState(existing, "secret", null, null, null)
            state.trustSelfSigned = false
            val config = state.buildConfig()
            assertEquals("false", config.option(ConnectionConfig.OPT_TRUST_SELF_SIGNED))
            if (type == ConnectionType.WEBDAV) assertFalse(DavConfig.from(config, "secret", "test", true).trustSelfSigned)
            if (type == ConnectionType.S3) assertFalse(S3Config.from(config, "secret", true).trustSelfSigned)
            state.trustSelfSigned = true
            assertEquals("true", state.buildConfig().option(ConnectionConfig.OPT_TRUST_SELF_SIGNED))
        }
    }

    @Test fun clearingPasswordBuildsSameNullableSecretForTestingAndSaving() {
        val config = ConnectionConfig(id = 9, type = ConnectionType.WEBDAV, name = "test", host = "host")
        val state = ConnectionEditState(config, "saved-password", null, null, null)
        assertEquals("saved-password", state.buildSecret())
        state.password = ""
        assertNull(state.buildSecret())
    }
}

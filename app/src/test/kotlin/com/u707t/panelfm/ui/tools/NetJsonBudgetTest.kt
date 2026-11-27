package com.u707t.panelfm.ui.tools

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class NetJsonBudgetTest {
    @Test fun auditedDeepNestingReturnsNoPrettyView() {
        assertNull(NetToolboxKit.prettyJson("[".repeat(10000) + "0" + "]".repeat(10000)))
    }
    @Test fun outputExpansionAndLargeInputAreRejected() {
        assertNull(NetToolboxKit.prettyJson("[".repeat(64) + List(10000) { "0" }.joinToString(",") + "]".repeat(64)))
        assertNull(NetToolboxKit.prettyJson("[\"" + "a".repeat(512 * 1024) + "\"]"))
    }
    @Test fun cancellationEscapesRatherThanBecomingInvalidJson() {
        val failure = runCatching {
            NetToolboxKit.prettyJson("[1,2]") { throw CancellationException("leave page") }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }
}

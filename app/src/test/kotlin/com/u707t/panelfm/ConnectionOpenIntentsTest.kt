package com.u707t.panelfm

import org.junit.Assert.*
import org.junit.Test

class ConnectionOpenIntentsTest {
    @Test fun `late old credential attempt cannot replace a newer activation`() {
        val intents = ConnectionOpenIntents()
        val old = intents.begin(1)
        val fresh = intents.begin(1)
        val applied = mutableListOf<String>()
        assertTrue(intents.publish(fresh) { applied += "new" })
        assertFalse(intents.publish(old) { applied += "old" })
        assertEquals(listOf("new"), applied)
    }
    @Test fun `disconnect invalidates a slow attempt without affecting another account`() {
        val intents = ConnectionOpenIntents()
        val first = intents.begin(1)
        val other = intents.begin(2)
        intents.disconnect(1) { }
        assertFalse(intents.publish(first) { fail("disconnected attempt published") })
        assertTrue(intents.publish(other) { })
    }
    @Test fun `disconnect all invalidates existing tickets but permits a later reopen`() {
        val intents = ConnectionOpenIntents()
        val old = intents.begin(1)
        intents.disconnectAll { }
        assertFalse(intents.publish(old) { fail("old epoch published") })
        assertTrue(intents.publish(intents.begin(1)) { })
    }
}

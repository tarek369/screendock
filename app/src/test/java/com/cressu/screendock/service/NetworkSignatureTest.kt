package com.cressu.screendock.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NetworkSignatureTest {
    @Test fun `order independent`() =
        assertEquals(NetworkWatcher.signature(listOf("10.0.0.2,fe80::1", "192.168.1.5")),
            NetworkWatcher.signature(listOf("192.168.1.5", "fe80::1,10.0.0.2")))
    @Test fun `ip change differs`() =
        assertNotEquals(NetworkWatcher.signature(listOf("10.0.0.2")), NetworkWatcher.signature(listOf("10.0.0.3")))
    @Test fun `empty is empty`() = assertEquals("", NetworkWatcher.signature(emptyList()))
}

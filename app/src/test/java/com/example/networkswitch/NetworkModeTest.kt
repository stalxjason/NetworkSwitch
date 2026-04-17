package com.example.networkswitch

import org.junit.Assert.*
import org.junit.Test

class NetworkModeTest {

    @Test
    fun `LTE has correct telephonyType`() {
        assertEquals(21, NetworkMode.LTE.telephonyType)
    }

    @Test
    fun `NR_5G has correct telephonyType`() {
        assertEquals(33, NetworkMode.NR_5G.telephonyType)
    }

    @Test
    fun `fromTelephonyType returns LTE for type 21`() {
        assertEquals(NetworkMode.LTE, NetworkMode.fromTelephonyType(21))
    }

    @Test
    fun `fromTelephonyType returns NR_5G for type 33`() {
        assertEquals(NetworkMode.NR_5G, NetworkMode.fromTelephonyType(33))
    }

    @Test
    fun `fromTelephonyType returns null for unknown type`() {
        assertNull(NetworkMode.fromTelephonyType(99))
    }

    @Test
    fun `fromTelephonyType returns null for type 0`() {
        assertNull(NetworkMode.fromTelephonyType(0))
    }
}

package io.github.stalxjason.networkswitch

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

    // ── nrAllowedFromShellOutput ──

    @Test
    fun `binary mask string with NR bit returns true`() {
        assertEquals(true, NetworkMode.nrAllowedFromShellOutput("11000001000000000000"))
    }

    @Test
    fun `binary mask string without NR bit returns false`() {
        assertEquals(false, NetworkMode.nrAllowedFromShellOutput("01000001000000000000"))
    }

    @Test
    fun `binary mask with surrounding text parses`() {
        assertEquals(
            true,
            NetworkMode.nrAllowedFromShellOutput("allowed types for slot 0: 11000001000000000000")
        )
    }

    @Test
    fun `decimal bitmask with NR bit returns true`() {
        // NR|LTE = (1 shl 20) or (1 shl 13) = 1056768
        assertEquals(
            true,
            NetworkMode.nrAllowedFromShellOutput("ALLOWED_NETWORK_TYPES: slot 0 = 1056768")
        )
    }

    @Test
    fun `decimal bitmask LTE only returns false`() {
        assertEquals(
            false,
            NetworkMode.nrAllowedFromShellOutput("ALLOWED_NETWORK_TYPES: slot 0 = 8192")
        )
    }

    @Test
    fun `hex bitmask with NR bit returns true`() {
        assertEquals(true, NetworkMode.nrAllowedFromShellOutput("0x102000"))
    }

    @Test
    fun `garbage output returns null`() {
        assertNull(NetworkMode.nrAllowedFromShellOutput("Error: unknown command"))
        assertNull(NetworkMode.nrAllowedFromShellOutput(""))
    }
}

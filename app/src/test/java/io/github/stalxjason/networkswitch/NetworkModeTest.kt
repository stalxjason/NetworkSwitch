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

    @Test
    fun `error text with slot number is not mistaken for a mask`() {
        assertNull(NetworkMode.nrAllowedFromShellOutput("Failed to set slot 0"))
        assertNull(NetworkMode.nrAllowedFromShellOutput("usage: get-allowed-network-types-for-users"))
    }

    @Test
    fun `digit run longer than 20 bits is not truncated to a mask`() {
        assertNull(NetworkMode.nrAllowedFromShellOutput("110000010000000000001"))
    }

    @Test
    fun `small integers are skipped and the first real mask wins`() {
        assertEquals(false, NetworkMode.nrAllowedFromShellOutput("slot 0 allowed=8192 reason 3"))
    }

    @Test
    fun `trailing numbers do not shadow the mask`() {
        assertEquals(true, NetworkMode.nrAllowedFromShellOutput("slot 1 allowed=1056768 code 3"))
    }
}

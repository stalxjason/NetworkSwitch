package io.github.stalxjason.networkswitch

import org.junit.Assert.*
import org.junit.Test

class IpHelperTest {

    @Test
    fun `IpEntry mobile defaults`() {
        val entry = IpHelper.IpEntry(
            ifaceName = "rmnet_data3",
            ipv4 = "10.0.0.1",
            ipv6 = null,
            kind = IpHelper.Kind.MOBILE
        )
        assertEquals("rmnet_data3", entry.ifaceName)
        assertEquals("10.0.0.1", entry.ipv4)
        assertNull(entry.ipv6)
        assertEquals(IpHelper.Kind.MOBILE, entry.kind)
        assertNull(entry.simSlotIndex)
        assertNull(entry.simCarrier)
        assertNull(entry.simSubscriptionId)
        assertFalse(entry.isActiveData)
    }

    @Test
    fun `IpEntry with sim attribution`() {
        val entry = IpHelper.IpEntry(
            ifaceName = "rmnet_data3",
            ipv4 = "10.144.179.230",
            ipv6 = "240e::1",
            kind = IpHelper.Kind.MOBILE,
            simSlotIndex = 0,
            simCarrier = "中国电信",
            simSubscriptionId = 5,
            isActiveData = true
        )
        assertEquals(0, entry.simSlotIndex)
        assertEquals("中国电信", entry.simCarrier)
        assertEquals(5, entry.simSubscriptionId)
        assertTrue(entry.isActiveData)
    }

    @Test
    fun `IpEntry wifi is not mobile`() {
        val entry = IpHelper.IpEntry(
            ifaceName = "wlan0",
            ipv4 = "192.168.1.2",
            ipv6 = null,
            kind = IpHelper.Kind.WIFI
        )
        assertNotEquals(IpHelper.Kind.MOBILE, entry.kind)
        assertEquals(IpHelper.Kind.WIFI, entry.kind)
    }

    @Test
    fun `Kind covers all expected transports`() {
        assertEquals(
            listOf("MOBILE", "WIFI", "ETHERNET", "VPN"),
            IpHelper.Kind.entries.map { it.name }
        )
    }
}

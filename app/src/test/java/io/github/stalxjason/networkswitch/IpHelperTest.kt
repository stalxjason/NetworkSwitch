package io.github.stalxjason.networkswitch

import org.junit.Assert.*
import org.junit.Test

class IpHelperTest {

    @Test
    fun `getAllInterfaceIps returns non-null list`() {
        val list = IpHelper.getAllInterfaceIps()
        // 在 CI/JVM 环境中可能获取不到 IP，但不应该抛异常
        assertNotNull(list)
        assertTrue(list is List<*>)
    }

    @Test
    fun `InterfaceIp data class defaults`() {
        val ip = IpHelper.InterfaceIp("wlan0", null, null)
        assertEquals("wlan0", ip.ifaceName)
        assertNull(ip.ipv4)
        assertNull(ip.ipv6)
    }

    @Test
    fun `InterfaceIp data class with values`() {
        val ip = IpHelper.InterfaceIp("rmnet0", "10.0.0.1", "2408::1")
        assertEquals("rmnet0", ip.ifaceName)
        assertEquals("10.0.0.1", ip.ipv4)
        assertEquals("2408::1", ip.ipv6)
    }
}

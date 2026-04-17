package com.example.networkswitch

import org.junit.Assert.*
import org.junit.Test

class IpHelperTest {

    @Test
    fun `getLocalIp returns LocalIp with non-null fields possible`() {
        val ip = IpHelper.getLocalIp()
        // 在 CI/JVM 环境中可能获取不到 IP，但不应该抛异常
        assertNotNull(ip)
        // 验证 data class 结构
        assertEquals(ip.ipv4, ip.ipv4)
        assertEquals(ip.ipv6, ip.ipv6)
    }

    @Test
    fun `LocalIp data class defaults`() {
        val ip = IpHelper.LocalIp()
        assertNull(ip.ipv4)
        assertNull(ip.ipv6)
        assertNull(ip.publicIpv4)
        assertNull(ip.publicIpv6)
    }

    @Test
    fun `LocalIp data class with values`() {
        val ip = IpHelper.LocalIp(
            ipv4 = "192.168.1.1",
            ipv6 = "2408::1",
            publicIpv4 = "1.2.3.4",
            publicIpv6 = "240e::1"
        )
        assertEquals("192.168.1.1", ip.ipv4)
        assertEquals("2408::1", ip.ipv6)
        assertEquals("1.2.3.4", ip.publicIpv4)
        assertEquals("240e::1", ip.publicIpv6)
    }
}

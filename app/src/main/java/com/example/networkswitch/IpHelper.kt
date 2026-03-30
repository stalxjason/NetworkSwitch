package com.example.networkswitch

import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.URL

/**
 * 获取本机内网/外网 IP（IPv4 / IPv6）
 */
object IpHelper {

    data class LocalIp(
        val ipv4: String? = null,
        val ipv6: String? = null,
        val publicIpv4: String? = null,
        val publicIpv6: String? = null
    )

    /**
     * 遍历网络接口，取第一个有效的内网 IPv4 和 IPv6
     * 排除 lo / docker / vbox 等虚拟接口
     */
    fun getLocalIp(): LocalIp {
        var ipv4: String? = null
        var ipv6: String? = null

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()
                // 跳过常见虚拟接口
                if (name.startsWith("docker") || name.startsWith("vbox") ||
                    name.startsWith("virbr") || name.startsWith("br-") ||
                    name.startsWith("tun") || name.startsWith("tap")) continue

                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    when {
                        addr is Inet4Address && ipv4 == null -> {
                            ipv4 = addr.hostAddress
                        }
                        addr is Inet6Address && ipv6 == null -> {
                            val raw = addr.hostAddress ?: continue
                            // 跳过 link-local (fe80::)
                            if (raw.startsWith("fe80")) continue
                            ipv6 = raw
                        }
                    }
                }
                if (ipv4 != null && ipv6 != null) break
            }
        } catch (_: Exception) {}

        return LocalIp(ipv4, ipv6)
    }

    /**
     * 查询外网 IP（阻塞调用，请在后台线程执行）
     */
    fun getPublicIp(): Pair<String?, String?> {
        val v4 = fetchUrl("https://4.ipw.cn")
        val v6 = fetchUrl("https://6.ipw.cn")
        return v4 to v6
    }

    private fun fetchUrl(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().readText().trim()
            } else null
        } catch (_: Exception) { null }
    }
}

package io.github.stalxjason.networkswitch

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * 获取本机网络接口 IP 列表
 */
object IpHelper {

    data class InterfaceIp(
        val ifaceName: String,   // 接口名，如 rmnet0、wlan0
        val ipv4: String?,
        val ipv6: String?
    )

    /**
     * 获取所有活跃网络接口的 IP 列表
     * 排除 lo / docker / vbox 等虚拟接口
     * 排除 link-local IPv6 (fe80::)
     */
    fun getAllInterfaceIps(): List<InterfaceIp> {
        val result = mutableListOf<InterfaceIp>()

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()
                if (name.startsWith("docker") || name.startsWith("vbox") ||
                    name.startsWith("virbr") || name.startsWith("br-") ||
                    name.startsWith("tun") || name.startsWith("tap")) continue

                var ipv4: String? = null
                var ipv6: String? = null

                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    when {
                        addr is Inet4Address && ipv4 == null -> {
                            ipv4 = addr.hostAddress
                        }
                        addr is Inet6Address && ipv6 == null -> {
                            val raw = addr.hostAddress ?: continue
                            if (raw.startsWith("fe80")) continue
                            ipv6 = raw
                        }
                    }
                }

                // 至少有一个 IP 才添加
                if (ipv4 != null || ipv6 != null) {
                    result.add(InterfaceIp(iface.name, ipv4, ipv6))
                }
            }
        } catch (_: Exception) {}

        return result
    }

    /**
     * 并行查询外网 IPv4 和 IPv6（suspend 函数）
     */
    suspend fun getPublicIp(): Pair<String?, String?> = kotlinx.coroutines.coroutineScope {
        val v4Deferred = kotlinx.coroutines.async { fetchUrl("https://4.ipw.cn") }
        val v6Deferred = kotlinx.coroutines.async { fetchUrl("https://6.ipw.cn") }
        v4Deferred.await() to v6Deferred.await()
    }

    private suspend fun fetchUrl(url: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().readText().trim()
            } else null
        } catch (_: Exception) { null }
    }
}

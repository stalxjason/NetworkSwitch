package io.github.stalxjason.networkswitch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.URL

/**
 * 获取本机网络接口 IP 列表
 */
object IpHelper {

    data class InterfaceIp(
        val ifaceName: String,   // 接口名，如 rmnet0、wlan0
        val ipv4: String?,
        val ipv6: String?,
        val simLabel: String?    // 如 "SIM1"、"SIM2"、null（非移动网络接口）
    )

    /**
     * 获取所有活跃网络接口的 IP 列表
     * 排除 lo / docker / vbox 等虚拟接口
     * 排除 link-local IPv6 (fe80::)
     * rmnet 接口会尝试推断对应的 SIM 卡编号
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

                if (ipv4 != null || ipv6 != null) {
                    val simLabel = inferSimLabel(name)
                    result.add(InterfaceIp(iface.name, ipv4, ipv6, simLabel))
                }
            }
        } catch (_: Exception) {}

        return result
    }

    /**
     * 根据接口名推断 SIM 卡编号
     * rmnet_data0/rmnet0/ccmni0 -> SIM1
     * rmnet_data1/rmnet1/ccmni1 -> SIM2
     * 其他数字结尾的 rmnet 类接口也做类似推断
     */
    private fun inferSimLabel(name: String): String? {
        val rmnetPrefixes = listOf("rmnet_data", "rmnet", "ccmni", "pdp", "wwan")
        for (prefix in rmnetPrefixes) {
            if (name.startsWith(prefix)) {
                val suffix = name.removePrefix(prefix)
                val num = suffix.trimStart('_').toIntOrNull()
                if (num != null) {
                    return "SIM${num + 1}"
                }
            }
        }
        return null
    }

    /**
     * 并行查询外网 IPv4 和 IPv6（suspend 函数）
     */
    suspend fun getPublicIp(): Pair<String?, String?> = coroutineScope {
        val v4Deferred = async { fetchUrl("https://4.ipw.cn") }
        val v6Deferred = async { fetchUrl("https://6.ipw.cn") }
        v4Deferred.await() to v6Deferred.await()
    }

    private suspend fun fetchUrl(url: String): String? = withContext(Dispatchers.IO) {
        try {
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

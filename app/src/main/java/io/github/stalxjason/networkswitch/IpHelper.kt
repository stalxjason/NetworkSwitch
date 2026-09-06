package io.github.stalxjason.networkswitch

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TelephonyNetworkSpecifier
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.URL

/**
 * 获取本机网络的 IP 信息
 *
 * 通过 ConnectivityManager 的网络列表（而非原始网卡枚举）取数据，
 * 蜂窝网络用 NetworkCapabilities.networkSpecifier 里的 subscriptionId
 * 精确归属到 SIM 卡；每张 SIM 只保留一条（数据卡 ★ 的网络优先），
 * WLAN / 以太网 / VPN 单独展示。
 */
object IpHelper {

    enum class Kind { MOBILE, WIFI, ETHERNET, VPN }

    data class IpEntry(
        val ifaceName: String,
        val ipv4: String?,
        val ipv6: String?,
        val kind: Kind,
        val simSlotIndex: Int? = null,        // 0-based，null 表示无法归属
        val simCarrier: String? = null,
        val simSubscriptionId: Int? = null,
        val isActiveData: Boolean = false     // 属于默认数据卡的网络
    )

    @SuppressLint("MissingPermission")
    fun getAllIpEntries(context: Context): List<IpEntry> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()

        // subscriptionId → (slotIndex, carrierName)
        val subMap = mutableMapOf<Int, Pair<Int, String?>>()
        try {
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as? SubscriptionManager
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            subManager?.activeSubscriptionInfoList?.forEach { sub ->
                val carrier = try {
                    tm?.createForSubscriptionId(sub.subscriptionId)
                        ?.networkOperatorName?.takeIf { it.isNotBlank() }
                } catch (_: Exception) { null }
                    ?: sub.carrierName?.toString()?.takeIf { it.isNotBlank() }
                subMap[sub.subscriptionId] = sub.simSlotIndex to carrier
            }
        } catch (_: Exception) {}

        val activeNet = try { cm.activeNetwork } catch (_: Exception) { null }
        val defaultDataSubId = try {
            SubscriptionManager.getDefaultDataSubscriptionId()
        } catch (_: Exception) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }

        val mobileEntries = mutableListOf<IpEntry>()
        val otherEntries = mutableListOf<IpEntry>()

        for (net in runCatching { cm.allNetworks }.getOrNull() ?: emptyArray()) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            val lp = cm.getLinkProperties(net) ?: continue
            val iface = lp.interfaceName ?: continue

            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Kind.VPN
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Kind.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Kind.ETHERNET
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Kind.MOBILE
                else -> continue
            }

            // 过滤 IMS 等无互联网能力的蜂窝网络
            if (kind == Kind.MOBILE &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) continue

            var ipv4: String? = null
            var ipv6: String? = null
            for (la in lp.linkAddresses) {
                val addr = la.address
                when {
                    addr is Inet4Address && ipv4 == null -> ipv4 = addr.hostAddress
                    addr is Inet6Address && ipv6 == null -> {
                        val raw = addr.hostAddress ?: continue
                        if (raw.startsWith("fe80")) continue
                        ipv6 = raw
                    }
                }
            }
            if (ipv4 == null && ipv6 == null) continue

            // 每条承载的 IPv4 / IPv6 都保留，展示层按需拼装
            var slot: Int? = null
            var carrier: String? = null
            var subId: Int? = null
            if (kind == Kind.MOBILE) {
                val specSubId = (caps.networkSpecifier as? TelephonyNetworkSpecifier)?.subscriptionId
                if (specSubId != null && specSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    subId = specSubId
                    subMap[specSubId]?.let { slot = it.first; carrier = it.second }
                }
            }

            // ★ = 属于默认数据卡的网络（不能用 activeNetwork 判断：
            // 开 VPN 时默认网络是 tun0，蜂窝网络会被误判为非活跃）
            val isActiveData = if (subId != null && defaultDataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                subId == defaultDataSubId
            } else {
                net == activeNet && kind == Kind.MOBILE
            }

            val entry = IpEntry(
                ifaceName = iface,
                ipv4 = ipv4,
                ipv6 = ipv6,
                kind = kind,
                simSlotIndex = slot,
                simCarrier = carrier,
                simSubscriptionId = subId,
                isActiveData = isActiveData
            )
            if (kind == Kind.MOBILE) mobileEntries.add(entry) else otherEntries.add(entry)
        }

        // 去掉重复展示的兜底逻辑：每条承载单独展示
        val kindOrder = mapOf(Kind.MOBILE to 0, Kind.WIFI to 1, Kind.ETHERNET to 2, Kind.VPN to 3)
        return (mobileEntries + otherEntries).sortedWith(
            compareBy(
                { if (it.isActiveData) 0 else 1 },
                { it.simSlotIndex ?: 9 },
                { kindOrder[it.kind] ?: 9 }
            )
        )
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

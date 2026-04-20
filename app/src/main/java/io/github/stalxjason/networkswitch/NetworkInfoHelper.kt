package io.github.stalxjason.networkswitch

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * 获取移动网络信号强度、运营商信息（支持双卡）
 */
object NetworkInfoHelper {

    data class SimInfo(
        val slotIndex: Int,          // 0-based slot，0=SIM1，1=SIM2
        val carrierName: String?,    // 运营商名称
        val networkTypeName: String?,// 网络类型，如 LTE、5G NR
        val isDataSim: Boolean       // 是否是当前数据卡
    )

    data class SignalInfo(
        val sims: List<SimInfo>,         // 所有已插卡槽信息
        val signalStrengthDbm: Int?,     // 当前数据卡信号强度 dBm
        val signalLevel: Int,            // 当前数据卡信号格数 0-4
        val activeIfaceName: String?     // 当前数据移动网络接口名（如 rmnet_data1）
    )

    @SuppressLint("MissingPermission")
    fun getSignalInfo(context: Context): SignalInfo {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return SignalInfo(emptyList(), null, 0, null)

        val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as? SubscriptionManager
            ?: return SignalInfo(emptyList(), null, 0, null)

        // 当前数据卡 subscriptionId
        val activeDataSubId = try {
            SubscriptionManager.getDefaultDataSubscriptionId()
        } catch (_: Exception) { SubscriptionManager.INVALID_SUBSCRIPTION_ID }

        // 读取所有已插 SIM
        val sims = mutableListOf<SimInfo>()
        try {
            val subs = subManager.activeSubscriptionInfoList ?: emptyList()
            for (sub in subs) {
                val slotIdx = sub.simSlotIndex
                val tmForSub = tm.createForSubscriptionId(sub.subscriptionId)
                val carrier = tmForSub.networkOperatorName?.takeIf { it.isNotBlank() }
                    ?: sub.carrierName?.toString()?.takeIf { it.isNotBlank() }
                var netType: String? = null
                try {
                    netType = getNetworkTypeName(tmForSub.dataNetworkType)
                } catch (_: SecurityException) {}
                sims.add(SimInfo(
                    slotIndex = slotIdx,
                    carrierName = carrier,
                    networkTypeName = netType,
                    isDataSim = (sub.subscriptionId == activeDataSubId)
                ))
            }
        } catch (_: Exception) {}

        // 如果 SubscriptionManager 没数据，降级到单卡
        if (sims.isEmpty()) {
            val carrier = tm.networkOperatorName?.takeIf { it.isNotBlank() }
            var netType: String? = null
            try { netType = getNetworkTypeName(tm.dataNetworkType) } catch (_: SecurityException) {}
            sims.add(SimInfo(0, carrier, netType, true))
        }

        // 信号强度取当前数据卡
        var dbm: Int? = null
        var level = 0
        try {
            val ss = tm.signalStrength
            if (ss != null) {
                for (cs in ss.cellSignalStrengths) {
                    dbm = cs.dbm
                    level = cs.level
                    break
                }
            }
        } catch (_: SecurityException) {}
        catch (_: Exception) {}

        // 获取当前活跃移动网络的接口名
        val activeIface = getActiveMobileIfaceName(context)

        return SignalInfo(sims, dbm, level, activeIface)
    }

    /** 获取当前活跃移动网络的接口名（如 rmnet_data1） */
    private fun getActiveMobileIfaceName(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val networks = cm.allNetworks
            for (net in networks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue
                val lp = cm.getLinkProperties(net) ?: continue
                val iface = lp.interfaceName ?: continue
                return iface
            }
            null
        } catch (_: Exception) { null }
    }

    private fun getNetworkTypeName(type: Int): String? = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
        TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        else -> null
    }
}

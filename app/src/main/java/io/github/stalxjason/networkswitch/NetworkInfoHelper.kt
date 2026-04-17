package io.github.stalxjason.networkswitch

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.TelephonyManager

/**
 * 获取移动网络信号强度和运营商信息
 */
object NetworkInfoHelper {

    data class SignalInfo(
        val carrierName: String?,        // 运营商名称
        val signalStrengthDbm: Int?,     // 信号强度 dBm（负值）
        val signalLevel: Int,            // 信号格数 0-4
        val networkTypeName: String?     // 网络类型，如 LTE、NR
    )

    /**
     * 同步获取当前信号信息
     */
    @SuppressLint("MissingPermission")
    fun getSignalInfo(context: Context): SignalInfo {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return SignalInfo(null, null, 0, null)

        val carrierName = tm.networkOperatorName?.takeIf { it.isNotBlank() }

        var networkTypeName: String? = null
        try {
            networkTypeName = getNetworkTypeName(tm.dataNetworkType)
        } catch (_: SecurityException) {}

        // SignalStrength 在 Android 12+ 可以通过 getCurrentSignalStrength 同步获取
        var dbm: Int? = null
        var level = 0
        try {
            val ss = tm.signalStrength ?: return SignalInfo(carrierName, null, 0, networkTypeName)
            for (i in 0 until ss.cellSignalStrengths.size) {
                val cs = ss.cellSignalStrengths[i]
                dbm = cs.dbm
                level = cs.level  // 0-4
                break
            }
        } catch (_: SecurityException) {}
        catch (_: Exception) {}

        return SignalInfo(carrierName, dbm, level, networkTypeName)
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

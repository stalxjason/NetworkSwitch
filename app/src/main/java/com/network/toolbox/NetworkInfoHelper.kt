package com.network.toolbox

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

object NetworkInfoHelper {

    private const val TAG = "NetworkToolbox"

    data class SimInfo(
        val slotIndex: Int,
        val carrierName: String?,
        val networkTypeName: String?,
        val isDataSim: Boolean,
        val signalDbm: Int?,
        val signalLevel: Int
    )

    @SuppressLint("MissingPermission")
    fun getSimInfo(context: Context): List<SimInfo> {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return emptyList()
        val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager ?: return emptyList()
        val activeDataSubId = try { SubscriptionManager.getDefaultDataSubscriptionId() } catch (_: Exception) { SubscriptionManager.INVALID_SUBSCRIPTION_ID }

        val sims = mutableListOf<SimInfo>()
        try {
            val subs = subManager.activeSubscriptionInfoList ?: emptyList()
            for (sub in subs) {
                val tmForSub = tm.createForSubscriptionId(sub.subscriptionId)
                val carrier = tmForSub.networkOperatorName?.takeIf { it.isNotBlank() } ?: sub.carrierName?.toString()?.takeIf { it.isNotBlank() }
                var netType: String? = null
                try { netType = getNetworkTypeName(tmForSub.dataNetworkType) } catch (e: SecurityException) { Log.w(TAG, "getDataNetworkType denied", e) }
                var dbm: Int? = null; var level = 0
                try {
                    val ss = tmForSub.signalStrength
                    if (ss != null) for (cs in ss.cellSignalStrengths) { dbm = cs.dbm; level = cs.level; break }
                } catch (e: Exception) { Log.w(TAG, "getSignalStrength failed", e) }
                sims.add(SimInfo(sub.simSlotIndex, carrier, netType, sub.subscriptionId == activeDataSubId, dbm, level))
            }
        } catch (e: Exception) { Log.w(TAG, "getSimInfo iteration failed", e) }

        if (sims.isEmpty()) {
            var netType: String? = null
            try { netType = getNetworkTypeName(tm.dataNetworkType) } catch (e: SecurityException) { Log.w(TAG, "getDataNetworkType denied (fallback)", e) }
            var dbm: Int? = null; var level = 0
            try {
                val ss = tm.signalStrength
                if (ss != null) for (cs in ss.cellSignalStrengths) { dbm = cs.dbm; level = cs.level; break }
            } catch (e: Exception) { Log.w(TAG, "getSignalStrength failed (fallback)", e) }
            sims.add(SimInfo(0, tm.networkOperatorName?.takeIf { it.isNotBlank() }, netType, true, dbm, level))
        }
        return sims
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

package io.github.stalxjason.networkswitch

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.CellSignalStrengthWcdma
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * 获取移动网络信号强度、运营商信息（支持双卡）
 */
object NetworkInfoHelper {

    data class SimInfo(
        val slotIndex: Int,           // 0-based slot，0=SIM1，1=SIM2
        val carrierName: String?,     // 运营商名称
        val networkTypeName: String?, // 网络类型，如 LTE、5G NR
        val isDataSim: Boolean,       // 是否是当前数据卡
        val signalDbm: Int?,          // 该卡信号强度 dBm（null 表示无法获取）
        val signalLevel: Int          // 该卡信号格数 0-4
    )

    data class SignalInfo(
        val sims: List<SimInfo>      // 所有已插卡槽信息
    ) {
        /** 当前数据卡信号格数（兜底用） */
        val signalLevel: Int get() = sims.find { it.isDataSim }?.signalLevel ?: 0
        /** 当前数据卡 dBm（兜底用） */
        val signalStrengthDbm: Int? get() = sims.find { it.isDataSim }?.signalDbm
    }

    @SuppressLint("MissingPermission")
    fun getSignalInfo(context: Context): SignalInfo {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return SignalInfo(emptyList())

        val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as? SubscriptionManager
            ?: return SignalInfo(emptyList())

        // 当前数据卡 subscriptionId
        val activeDataSubId = try {
            SubscriptionManager.getDefaultDataSubscriptionId()
        } catch (_: Exception) { SubscriptionManager.INVALID_SUBSCRIPTION_ID }

        // 读取所有已插 SIM，含独立信号强度
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
                    netType = getNetworkTypeName(context, tmForSub.dataNetworkType)
                } catch (_: SecurityException) {}

                val (dbm, level) = readCellSignal(tmForSub)

                sims.add(SimInfo(
                    slotIndex = slotIdx,
                    carrierName = carrier,
                    networkTypeName = netType,
                    isDataSim = (sub.subscriptionId == activeDataSubId),
                    signalDbm = dbm,
                    signalLevel = level
                ))
            }
        } catch (_: Exception) {}

        // 降级：SubscriptionManager 无数据时回退到全局单卡；
        // 槽位跟随默认数据卡动态解析，避免硬编码 slot 0 与实际不符
        if (sims.isEmpty()) {
            val carrier = tm.networkOperatorName?.takeIf { it.isNotBlank() }
            var netType: String? = null
            try { netType = getNetworkTypeName(context, tm.dataNetworkType) } catch (_: SecurityException) {}
            val (dbm, level) = readCellSignal(tm)
            sims.add(
                SimInfo(
                    slotIndex = NetworkModeHelper.dataSlotId(context),
                    carrierName = carrier,
                    networkTypeName = netType,
                    isDataSim = true,
                    signalDbm = dbm,
                    signalLevel = level
                )
            )
        }

        return SignalInfo(sims)
    }

    /**
     * 读本卡信号强度。不能无脑取 cellSignalStrengths 第一条——双频注册
     * （如 4G + 5G SA 共存）时列表顺序不保证，可能拿到与展示网络类型不符的那条。
     */
    private fun readCellSignal(tm: TelephonyManager): Pair<Int?, Int> {
        return try {
            val signalStrength = tm.signalStrength ?: return null to 0
            pickCellSignalStrength(signalStrength, tm.dataNetworkType)?.let { cs ->
                cs.dbm to cs.level
            } ?: null to 0
        } catch (_: SecurityException) {
            null to 0
        } catch (_: Exception) {
            null to 0
        }
    }

    /** 信号强度类型家族，对应 android.telephony.CellSignalStrength* 的各子类 */
    enum class SignalRat { NR, LTE, TDS, WCDMA, CDMA, GSM }

    /**
     * dataNetworkType → 强度类型家族；NETWORK_TYPE_UNKNOWN 等无 RAT 信息返回 null。
     * 保持纯映射，便于单测覆盖。
     */
    internal fun signalRatOf(dataNetworkType: Int): SignalRat? = when (dataNetworkType) {
        TelephonyManager.NETWORK_TYPE_NR -> SignalRat.NR
        TelephonyManager.NETWORK_TYPE_LTE,
        TelephonyManager.NETWORK_TYPE_IWLAN -> SignalRat.LTE
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> SignalRat.TDS
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSPAP -> SignalRat.WCDMA
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT -> SignalRat.CDMA
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_IDEN,
        TelephonyManager.NETWORK_TYPE_GSM -> SignalRat.GSM
        else -> null
    }

    /**
     * 按 RAT 选对应的 CellSignalStrength；家族为空或 RAT 未知时退回第一条。
     *
     * API 36 已移除 SignalStrength.get{Nr,Lte,...}CellSignalStrength()，
     * 各 RAT 强度改为从 cellSignalStrengths 里按类型取。
     */
    private fun pickCellSignalStrength(
        signalStrength: SignalStrength,
        dataNetworkType: Int
    ): CellSignalStrength? {
        val all = signalStrength.cellSignalStrengths
        val rat = signalRatOf(dataNetworkType) ?: return all.firstOrNull()
        val match = when (rat) {
            SignalRat.NR -> all.firstOrNull { it is CellSignalStrengthNr }
            SignalRat.LTE -> all.firstOrNull { it is CellSignalStrengthLte }
            SignalRat.TDS -> all.firstOrNull { it is CellSignalStrengthTdscdma }
            SignalRat.WCDMA -> all.firstOrNull { it is CellSignalStrengthWcdma }
            SignalRat.CDMA -> all.firstOrNull { it is CellSignalStrengthCdma }
            SignalRat.GSM -> all.firstOrNull { it is CellSignalStrengthGsm }
        }
        return match ?: all.firstOrNull()
    }

    private fun getNetworkTypeName(context: Context, type: Int): String? = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN, TelephonyManager.NETWORK_TYPE_GSM ->
            context.getString(R.string.net_2g)
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_TD_SCDMA ->
            context.getString(R.string.net_3g)
        TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN ->
            context.getString(R.string.net_lte)
        TelephonyManager.NETWORK_TYPE_NR -> context.getString(R.string.net_nr)
        else -> null
    }
}

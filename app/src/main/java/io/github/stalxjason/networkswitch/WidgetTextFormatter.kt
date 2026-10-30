package io.github.stalxjason.networkswitch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 桌面小组件文案拼装（两个组件共用）
 *
 * 所有跨进程 / binder 查询都在 IO 上下文里执行。
 * 附加信息（双卡明细、内网 IP）只在组件被拉高、真的能显示时才查——
 * 拖拽 resize 时每帧都查一遍是纯浪费。
 *
 * [compact] 用于 1 格宽的窄组件：运营商那一行只留运营商名，去掉「· 网络类型」——
 * 网络类型已经由上面的大字模式（5G / 4G）表达，窄宽度下重复只会被截断。
 */
object WidgetTextFormatter {

    data class Texts(
        val modeLabel: String,
        val carrierText: String,
        val signalText: String,
        val simsText: String = "",
        val ipText: String = ""
    )

    suspend fun load(context: Context, withExtras: Boolean, compact: Boolean = false): Texts =
        withContext(Dispatchers.IO) {
            val modeLabel = try {
                context.getString(NetworkModeHelper.getCurrentMode(context).labelRes)
            } catch (_: Exception) {
                ""
            }

            var carrierText = context.getString(R.string.widget_carrier_unknown)
            var signalText = ""
            var simsText = ""

            try {
                val sims = NetworkInfoHelper.getSignalInfo(context).sims
                val dataSim = sims.find { it.isDataSim }
                val carrier =
                    dataSim?.carrierName ?: context.getString(R.string.widget_carrier_unknown)
                val netType = dataSim?.networkTypeName
                carrierText = if (netType != null && !compact) {
                    context.getString(R.string.widget_carrier_net, carrier, netType)
                } else {
                    carrier
                }
                signalText = buildString {
                    val level = dataSim?.signalLevel ?: 0
                    val dbm = dataSim?.signalDbm
                    // 窄组件拆成两行：格数一行、dBm 一行，避免「-59 / dBm」被折在中间
                    val separator = if (compact) "\n"
                    else context.getString(R.string.signal_separator)
                    if (level > 0) append(context.getString(R.string.widget_signal_bars, level))
                    if (dbm != null) {
                        if (isNotEmpty()) append(separator)
                        append(context.getString(R.string.signal_dbm, dbm))
                    }
                    if (isEmpty()) append(context.getString(R.string.widget_signal_none))
                }

                if (withExtras) {
                    simsText = sims.sortedBy { it.slotIndex }.joinToString("\n") { sim ->
                        buildString {
                            append(context.getString(R.string.sim_label, sim.slotIndex + 1))
                            append(" ")
                            append(sim.carrierName ?: "")
                            if (sim.isDataSim) append(context.getString(R.string.data_sim_mark))
                            sim.networkTypeName?.let { append(" ").append(it) }
                            sim.signalDbm?.let {
                                append(context.getString(R.string.signal_separator))
                                append(context.getString(R.string.signal_dbm, it))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }

            val ipText = if (!withExtras) {
                ""
            } else {
                try {
                    IpHelper.getAllIpEntries(context).take(4).joinToString("\n") { entry ->
                        buildString {
                            append(ipKindLabel(context, entry))
                            append(context.getString(R.string.signal_separator))
                            append(entry.ipv4 ?: entry.ipv6 ?: "")
                        }
                    }
                } catch (_: Exception) {
                    ""
                }
            }

            Texts(modeLabel, carrierText, signalText, simsText, ipText)
        }

    /** 紧凑行前缀：移动卡显示 SIM 号（归属不到时退回网卡名），其余显示接口类型 */
    private fun ipKindLabel(context: Context, entry: IpHelper.IpEntry): String = when (entry.kind) {
        IpHelper.Kind.MOBILE -> entry.simSlotIndex?.let {
            context.getString(R.string.sim_label, it + 1)
        } ?: entry.ifaceName

        IpHelper.Kind.WIFI -> context.getString(R.string.ip_label_wlan)
        IpHelper.Kind.ETHERNET -> context.getString(R.string.ip_label_ethernet)
        IpHelper.Kind.VPN -> context.getString(R.string.ip_label_vpn)
    }
}

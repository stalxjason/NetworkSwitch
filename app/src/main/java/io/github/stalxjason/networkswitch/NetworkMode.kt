package io.github.stalxjason.networkswitch

/**
 * 网络模式枚举
 *
 * @param label 显示名称
 * @param telephonyType settings put global preferred_network_mode 的值
 * @param allowedTypesMask cmd phone set-allowed-network-types-for-users 的掩码串
 *        （20 位 0/1，对应网络类型 20..1，首位=NR(20)、第 8 位=LTE(13)，
 *         为 MacroDroid 宏在真机（HyperOS）上验证过的格式，勿改）
 */
enum class NetworkMode(val label: String, val telephonyType: Int, val allowedTypesMask: String) {
    LTE("4G", 21, "01000001000000000000"),
    NR_5G("5G", 33, "11000001000000000000");

    companion object {
        fun fromTelephonyType(type: Int): NetworkMode? =
            entries.find { it.telephonyType == type }

        /**
         * 从 `cmd phone get-allowed-network-types-for-users` 的输出判断 NR 是否放行。
         * 依次兼容：20 位 0/1 掩码串（首位=NR）、十进制/十六进制位掩码（bit20=NR）、
         * 文本类型名（如 "NR|LTE"）。全部不匹配返回 null。
         */
        fun nrAllowedFromShellOutput(output: String): Boolean? {
            Regex("[01]{20}").findAll(output).lastOrNull()?.value
                ?.let { return it[0] == '1' }

            Regex("0[xX][0-9a-fA-F]+|\\d+").findAll(output)
                .lastOrNull()?.value
                ?.let { token ->
                    val value = if (token.startsWith("0x") || token.startsWith("0X")) {
                        token.substring(2).toLongOrNull(16)
                    } else {
                        token.toLongOrNull()
                    }
                    if (value != null) return (value and (1L shl 20)) != 0L
                }

            val upper = output.uppercase()
            return when {
                Regex("\\bNR\\b").containsMatchIn(upper) -> true
                listOf("LTE", "WCDMA", "GSM", "CDMA", "TDSCDMA").any { upper.contains(it) } -> false
                else -> null
            }
        }
    }
}

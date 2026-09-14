package io.github.stalxjason.networkswitch

import androidx.annotation.StringRes

/**
 * 网络模式枚举
 *
 * @param labelRes 显示名称的字符串资源
 * @param descRes 「当前使用 X 网络」描述文案的字符串资源
 * @param telephonyType settings put global preferred_network_mode 的值
 * @param allowedTypesMask cmd phone set-allowed-network-types-for-users 的掩码串
 *       （20 位 0/1，对应网络类型 20..1，首位=NR(20)、第 8 位=LTE(13)，
 *         为 MacroDroid 宏在真机（HyperOS）上验证过的格式，勿改）
 */
enum class NetworkMode(
    @StringRes val labelRes: Int,
    @StringRes val descRes: Int,
    val telephonyType: Int,
    val allowedTypesMask: String
) {
    LTE(R.string.mode_4g, R.string.mode_desc_4g, 21, "01000001000000000000"),
    NR_5G(R.string.mode_5g, R.string.mode_desc_5g, 33, "11000001000000000000");

    companion object {
        // 位掩码的合理下限：至少含 LTE 位（bit13）。
        // 用于排除槽位号、错误码之类的小整数被误读成掩码。
        private const val MIN_MASK_VALUE = 1L shl 13

        // 20 位 0/1 掩码串；前后不得再跟 0/1，避免截断更长的数字串造成误判
        private val MASK_REGEX = Regex("(?<![01])[01]{20}(?![01])")
        private val NUMBER_TOKEN_REGEX = Regex("0[xX][0-9a-fA-F]+|\\d+")

        /**
         * 从 `cmd phone get-allowed-network-types-for-users` 的输出判断 NR 是否放行。
         * 依次兼容：20 位 0/1 掩码串（首位=NR）、十进制/十六进制位掩码（bit20=NR）、
         * 文本类型名（如 "NR|LTE"）。全部不匹配返回 null。
         *
         * 位掩码分支只认 >= 8192 的 token，取第一个命中的——
         * 否则「Failed to set slot 0」这类错误文本里的槽位号会被当成掩码判成 LTE。
         */
        fun nrAllowedFromShellOutput(output: String): Boolean? {
            MASK_REGEX.findAll(output).lastOrNull()?.value?.let { return it[0] == '1' }

            NUMBER_TOKEN_REGEX.findAll(output)
                .mapNotNull { match -> parseMaskValue(match.value) }
                .firstOrNull()
                ?.let { return (it and (1L shl 20)) != 0L }

            val upper = output.uppercase()
            return when {
                Regex("\\bNR\\b").containsMatchIn(upper) -> true
                listOf("LTE", "WCDMA", "GSM", "CDMA", "TDSCDMA").any { upper.contains(it) } -> false
                else -> null
            }
        }

        /** 解析十进制/十六进制 token；低于掩码量级（< 8192）按非掩码返回 null */
        private fun parseMaskValue(token: String): Long? {
            val value = if (token.startsWith("0x") || token.startsWith("0X")) {
                token.substring(2).toLongOrNull(16)
            } else {
                token.toLongOrNull()
            }
            return value?.takeIf { it >= MIN_MASK_VALUE }
        }
    }
}

package com.example.networkswitch

/**
 * 网络模式枚举
 *
 * @param label 显示名称
 * @param telephonyType settings put global preferred_network_mode 的值
 * @param allowedTypesMask cmd phone set-allowed-network-types-for-users 的位掩码
 */
enum class NetworkMode(val label: String, val telephonyType: Int, val allowedTypesMask: String) {
    LTE("4G", 21, "01000001000000000000"),
    NR_5G("5G", 33, "11000001000000000000");

    companion object {
        fun fromTelephonyType(type: Int): NetworkMode? =
            entries.find { it.telephonyType == type }
    }
}

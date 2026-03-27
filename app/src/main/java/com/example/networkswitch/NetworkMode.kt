package com.example.networkswitch

/**
 * 网络模式枚举
 */
enum class NetworkMode(val label: String, val telephonyType: Int) {
    LTE("4G", 21),                    // NETWORK_MODE_LTE_ONLY
    NR_5G("5G", 33);                  // NETWORK_MODE_NR_LTE_GSM_WCDMA

    companion object {
        fun fromTelephonyType(type: Int): NetworkMode? =
            entries.find { it.telephonyType == type }
    }
}

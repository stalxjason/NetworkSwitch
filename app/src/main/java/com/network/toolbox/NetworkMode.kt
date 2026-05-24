package com.network.toolbox

enum class NetworkMode(val label: String, val telephonyType: Int, val allowedTypesMask: String) {
    LTE("4G", 21, "01000001000000000000"),
    NR_5G("5G", 33, "11000001000000000000");

    companion object {
        fun fromTelephonyType(type: Int): NetworkMode? =
            entries.find { it.telephonyType == type }
    }
}

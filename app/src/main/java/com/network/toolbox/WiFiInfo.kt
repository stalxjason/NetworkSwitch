package com.network.toolbox

import android.annotation.SuppressLint
import android.util.Log
import android.net.wifi.WifiConfiguration

data class WiFiInfo(
    val ssid: String,
    val bssid: String,
    val password: String,
    val securityType: SecurityType,
    val isHidden: Boolean = false,
    val fqdn: String = "",
    val creator: String = "",
    val lastUpdate: Long = 0L,
    val networkId: Int = -1,
    val eapMethod: String = "",
    val identity: String = ""
) {
    val securityIcon: Int get() = securityType.iconRes
}

enum class SecurityType(val displayName: String, val iconRes: Int) {
    WPA3("WPA3", R.drawable.ic_security_high),
    WPA2("WPA2", R.drawable.ic_security_medium),
    WPA("WPA", R.drawable.ic_security_medium),
    WEP("WEP", R.drawable.ic_security_low),
    OPEN("开放网络", R.drawable.ic_security_low),
    UNKNOWN("未知", R.drawable.ic_security_low);

    companion object {
        fun fromString(security: String): SecurityType {
            val upper = security.uppercase()
            return when {
                upper.contains("SAE") || upper.contains("WPA3") -> WPA3
                upper.contains("WPA2") || upper.contains("PSK") -> WPA2
                upper.contains("WPA") -> WPA
                upper.contains("WEP") -> WEP
                upper.contains("NONE") || upper.contains("OPEN") || upper.contains("ESS") -> OPEN
                else -> UNKNOWN
            }
        }
    }
}

@Suppress("DEPRECATION")
fun WifiConfiguration.toWiFiInfo(): WiFiInfo {
    return WiFiInfo(
        ssid = SSID?.replace("\"", "")?.trim() ?: "",
        password = preSharedKey?.replace("\"", "")?.trim() ?: "",
        securityType = SecurityType.fromString(allowedKeyManagement.toString()),
        bssid = BSSID ?: "",
        isHidden = hiddenSSID,
        fqdn = FQDN ?: "",
        networkId = networkId,
        creator = getFieldValue(this, "creator") as? String ?: "",
        lastUpdate = getFieldValue(this, "lastUpdate") as? Long ?: 0L,
        eapMethod = getFieldValue(this, "eap")?.let { eap ->
            when ((eap as? Int) ?: 0) {
                1 -> "EAP-TLS"; 2 -> "EAP-TTLS"; 4 -> "PEAP"; 5 -> "EAP-FAST"
                6 -> "EAP-SIM"; 18 -> "EAP-AKA"; 25 -> "EAP-AKA'"; 26 -> "EAP-pwd"
                else -> ""
            }
        } ?: "",
        identity = getFieldValue(this, "identity") as? String ?: ""
    )
}

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
private fun getFieldValue(config: WifiConfiguration, fieldName: String): Any? {
    @Suppress("DEPRECATION")
    return try {
        val field = config::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.get(config)
    } catch (e: Exception) {
        Log.w("NetworkToolbox", "getFieldValue($fieldName) failed", e)
        null
    }
}

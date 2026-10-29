package io.github.stalxjason.networkswitch.wifi

import androidx.annotation.StringRes
import io.github.stalxjason.networkswitch.R

/**
 * 已保存网络的安全类型（只区分展示所需粒度）。
 *
 * 判定来自 `WifiConfiguration.allowedKeyManagement` 的位集合，
 * 具体位含义见 android.net.wifi.WifiConfiguration.KeyMgmt。
 */
enum class WifiSecurity(@StringRes val labelRes: Int) {
    OPEN(R.string.sec_open),
    WEP(R.string.sec_wep),
    WPA(R.string.sec_wpa),
    WPA3(R.string.sec_wpa3),
    OWE(R.string.sec_owe),
    ENTERPRISE(R.string.sec_8021x),
    OTHER(R.string.sec_other)
}

/**
 * 认证方式明细：一条配置可能同时命中多项（例如 SAE + Suite-B），
 * 展示时全部列出，比只显示一个粗分类更有信息量。
 */
enum class WifiAuthDetail(@StringRes val labelRes: Int) {
    SAE(R.string.auth_sae),
    SUITE_B_192(R.string.auth_suiteb),
    EAP(R.string.auth_eap),
    PSK(R.string.auth_psk),
    OWE(R.string.auth_owe),
    IEEE_8021X(R.string.auth_ieee8021x),
    WAPI_PSK(R.string.auth_wapi_psk),
    WAPI_CERT(R.string.auth_wapi_cert),
    DPP(R.string.auth_dpp),
    NONE(R.string.auth_none)
}

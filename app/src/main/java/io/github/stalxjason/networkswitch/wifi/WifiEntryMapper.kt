package io.github.stalxjason.networkswitch.wifi

import android.annotation.SuppressLint
import android.net.wifi.WifiConfiguration
import java.util.BitSet

/**
 * 把两路原始数据收敛成 [WifiEntry]：
 *  - Shizuku 通道拿到的是平台对象 [WifiConfiguration]
 *  - Root 通道拿到的是解析出来的 [WifiConfigStoreParser.RawNetwork]
 *
 * 两条路最终走同一套「密钥管理位 → 安全类型 / 认证明细」判定，
 * 保证同样一条网络无论从哪条通道读出来，展示都一致。
 *
 * 注意：`WifiConfiguration` 没有公开的频率字段、`WifiConfigStore.xml` 也不存频率，
 * 所以本应用拿不到「频段 / 信道」——这两个字段已从模型和 UI 里移除。
 */
internal object WifiEntryMapper {

    fun fromConfig(config: WifiConfiguration): WifiEntry? {
        val ssid = config.SSID?.stripQuotes()?.takeIf { it.isNotEmpty() } ?: return null
        val keyMask = config.allowedKeyManagement.toMask()
        val wepShared = runCatching {
            config.allowedAuthAlgorithms.get(WifiConfiguration.AuthAlgorithm.SHARED)
        }.getOrDefault(false)

        return WifiEntry(
            ssid = ssid,
            passphrase = readPassphrase(config),
            security = classify(keyMask, wepShared),
            bssid = config.BSSID?.takeIf { it.isNotBlank() && it != NULL_TOKEN },
            hidden = config.hiddenSSID,
            authDetails = authDetailsOf(keyMask)
        )
    }

    /**
     * Root 通道没有 AuthAlgorithm 信息，因此无法区分 WEP：
     * 该分支只用于兜底，WEP 网络会落到 OPEN（已废弃的加密方式，可接受）。
     */
    fun fromRaw(raw: WifiConfigStoreParser.RawNetwork): WifiEntry? {
        if (raw.ssid.isEmpty()) return null
        return WifiEntry(
            ssid = raw.ssid,
            passphrase = raw.passphrase,
            security = classify(raw.keyMgmtBits, wepShared = false),
            bssid = raw.bssid,
            hidden = raw.hidden,
            authDetails = authDetailsOf(raw.keyMgmtBits)
        )
    }

    // ── 判定 ──────────────────────────────────────────────────────────────

    /** 判定顺序即优先级：SAE 的配置通常同时带 WPA_PSK 位，必须先认 SAE */
    private fun classify(keyMask: Int, wepShared: Boolean): WifiSecurity = when {
        keyMask.has(KeyMgmt.SAE) -> WifiSecurity.WPA3
        keyMask.has(KeyMgmt.WPA_EAP) || keyMask.has(KeyMgmt.IEEE8021X) -> WifiSecurity.ENTERPRISE
        keyMask.has(KeyMgmt.OWE) -> WifiSecurity.OWE
        keyMask.has(KeyMgmt.WPA_PSK) -> WifiSecurity.WPA
        keyMask.has(KeyMgmt.NONE) -> if (wepShared) WifiSecurity.WEP else WifiSecurity.OPEN
        else -> WifiSecurity.OTHER
    }

    /** 明细按固定顺序输出，避免同一网络两次读取时顺序抖动 */
    private fun authDetailsOf(keyMask: Int): List<WifiAuthDetail> = buildList {
        if (keyMask.has(KeyMgmt.SAE)) add(WifiAuthDetail.SAE)
        if (keyMask.has(KeyMgmt.SUITE_B_192)) add(WifiAuthDetail.SUITE_B_192)
        if (keyMask.has(KeyMgmt.WPA_EAP)) add(WifiAuthDetail.EAP)
        if (keyMask.has(KeyMgmt.WPA_PSK)) add(WifiAuthDetail.PSK)
        if (keyMask.has(KeyMgmt.OWE)) add(WifiAuthDetail.OWE)
        if (keyMask.has(KeyMgmt.IEEE8021X)) add(WifiAuthDetail.IEEE_8021X)
        if (keyMask.has(KeyMgmt.WAPI_PSK)) add(WifiAuthDetail.WAPI_PSK)
        if (keyMask.has(KeyMgmt.WAPI_CERT)) add(WifiAuthDetail.WAPI_CERT)
        if (keyMask.has(KeyMgmt.DPP)) add(WifiAuthDetail.DPP)
        if (isEmpty() && keyMask.has(KeyMgmt.NONE)) add(WifiAuthDetail.NONE)
    }

    // ── 字段读取 ──────────────────────────────────────────────────────────

    private fun readPassphrase(config: WifiConfiguration): String? {
        config.preSharedKey?.takeIf { it.isNotBlank() }?.let { return it.stripQuotes() }
        val wep = config.wepKeys.orEmpty()
            .filterNotNull()
            .filter { it.isNotBlank() }
            .map { it.stripQuotes() }
        return wep.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private fun BitSet.toMask(): Int {
        var mask = 0
        var bit = nextSetBit(0)
        while (bit >= 0 && bit < Int.SIZE_BITS) {
            mask = mask or (1 shl bit)
            bit = nextSetBit(bit + 1)
        }
        return mask
    }

    private fun Int.has(keyManagementIndex: Int): Boolean =
        keyManagementIndex in 0 until Int.SIZE_BITS && (this and (1 shl keyManagementIndex)) != 0

    private fun String.stripQuotes(): String = trim().removeSurrounding("\"")

    /** WifiConfiguration 里表示「无值」的字面量 */
    private const val NULL_TOKEN = "null"

    /**
     * KeyMgmt 的位下标，取自 WifiConfiguration.KeyMgmt 的常量值。
     *
     * WAPI_* / DPP 是 API 34 才公开的常量，这里用 `const` 让编译器把数值内联，
     * 因此旧版本系统上不会去读取不存在的字段（行内化是刻意的，不是隐患）。
     */
    @SuppressLint("InlinedApi")
    private object KeyMgmt {
        const val NONE = WifiConfiguration.KeyMgmt.NONE
        const val WPA_PSK = WifiConfiguration.KeyMgmt.WPA_PSK
        const val WPA_EAP = WifiConfiguration.KeyMgmt.WPA_EAP
        const val IEEE8021X = WifiConfiguration.KeyMgmt.IEEE8021X
        const val SAE = WifiConfiguration.KeyMgmt.SAE
        const val OWE = WifiConfiguration.KeyMgmt.OWE
        const val SUITE_B_192 = WifiConfiguration.KeyMgmt.SUITE_B_192
        const val WAPI_PSK = WifiConfiguration.KeyMgmt.WAPI_PSK
        const val WAPI_CERT = WifiConfiguration.KeyMgmt.WAPI_CERT
        const val DPP = WifiConfiguration.KeyMgmt.DPP
    }
}

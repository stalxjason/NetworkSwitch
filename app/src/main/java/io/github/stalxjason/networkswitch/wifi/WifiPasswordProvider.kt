package io.github.stalxjason.networkswitch.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcelable
import android.util.Log
import io.github.stalxjason.networkswitch.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.TimeUnit

/**
 * 已保存 WiFi 密码读取
 *
 * 主通道：Shizuku 以 shell 身份反射调用特权 API IWifiManager.getPrivilegedConfiguredNetworks()，
 * 返回含密码的 WifiConfiguration 列表（Android 13+ 需在参数 Bundle 中携带 AttributionSource）。
 *
 * 兜底通道：Android 高版本隐藏 API 收紧导致反射失败时，用 Root 读取
 * WifiConfigStore.xml 解析密码（尽力而为，仅用于展示）。
 */
object WifiPasswordProvider {

    private const val TAG = "WifiPasswordProvider"
    private const val SHELL_PKG = "com.android.shell"
    private const val ATTRIBUTION_BUNDLE_KEY = "EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE"
    private const val ROOT_TIMEOUT_SECONDS = 10L

    data class WifiEntry(
        val ssid: String,
        val password: String?,
        val security: String,
        val bssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val isHidden: Boolean = false,
        val authDetail: String? = null
    )

    sealed interface Result {
        data class Ok(val list: List<WifiEntry>) : Result
        data class ShizukuUnavailable(val message: String) : Result
        data class Error(val message: String) : Result
    }

    suspend fun getSavedNetworks(): Result = withContext(Dispatchers.IO) {
        val status = ShizukuHelper.getStatus()
        if (status !is ShizukuHelper.Status.Authorized) {
            return@withContext Result.ShizukuUnavailable(shizukuHint(status))
        }

        try {
            Result.Ok(loadViaBinder())
        } catch (t: Throwable) {
            Log.w(TAG, "binder 取密失败，尝试 Root 兜底", t)
            val fallback = loadViaRootConfigStore()
            if (fallback != null) {
                Result.Ok(fallback)
            } else {
                Result.Error(
                    "特权接口调用失败（${t.javaClass.simpleName}: ${t.message ?: "未知错误"}），Root 兜底也未成功"
                )
            }
        }
    }

    private fun shizukuHint(status: ShizukuHelper.Status): String = when (status) {
        is ShizukuHelper.Status.Running -> "Shizuku 正在运行，但尚未授权本应用"
        is ShizukuHelper.Status.NotRunning -> "Shizuku 未运行，请先打开 Shizuku"
        is ShizukuHelper.Status.NotInstalled -> "未安装 Shizuku，请先安装并启动"
        is ShizukuHelper.Status.Authorized -> ""
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 主通道：Shizuku binder 反射
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("PrivateApi")
    @Suppress("UNCHECKED_CAST")
    private fun loadViaBinder(): List<WifiEntry> {
        val iwm = Class.forName("android.net.wifi.IWifiManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(
                null,
                ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.WIFI_SERVICE))
            ) ?: error("无法获取 IWifiManager binder")

        val base = Class.forName("android.net.wifi.IWifiManager")
        // shell uid 被授予了读取特权配置的 system permission，无需 root
        val user = when (Shizuku.getUid()) {
            0 -> "root"
            1000 -> "system"
            else -> "shell"
        }

        val raw = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) {
            // Android 13+：签名多了参数 Bundle，需携带调用方 AttributionSource
            val attributionClass = Class.forName("android.content.AttributionSource")
            val attributionSource = attributionClass.getConstructor(
                Int::class.java,
                String::class.java,
                String::class.java,
                Set::class.java,
                attributionClass
            ).newInstance(Shizuku.getUid(), SHELL_PKG, SHELL_PKG, null as Set<String>?, null)

            base.getMethod(
                "getPrivilegedConfiguredNetworks",
                String::class.java,
                String::class.java,
                Bundle::class.java
            ).invoke(
                iwm, user, SHELL_PKG,
                Bundle().apply {
                    putParcelable(ATTRIBUTION_BUNDLE_KEY, attributionSource as Parcelable)
                }
            )
        } else {
            // Android 12 / 12L：先试两参签名，退回三参
            try {
                base.getMethod(
                    "getPrivilegedConfiguredNetworks",
                    String::class.java,
                    String::class.java
                ).invoke(iwm, user, SHELL_PKG)
            } catch (e: NoSuchMethodException) {
                base.getMethod(
                    "getPrivilegedConfiguredNetworks",
                    String::class.java,
                    String::class.java,
                    Bundle::class.java
                ).invoke(iwm, user, SHELL_PKG, null)
            }
        }

        // 返回值是 ParceledListSlice，经 getList() 还原为 List<WifiConfiguration>
        val configs = raw?.javaClass?.getMethod("getList")?.invoke(raw) as? List<WifiConfiguration>
            ?: emptyList()

        return configs.mapNotNull(::toEntry)
            .distinctBy { it.ssid }
            .sortedBy { it.ssid.lowercase() }
    }

    private fun toEntry(config: WifiConfiguration): WifiEntry? {
        val ssid = config.SSID?.trim('"')?.takeIf { it.isNotEmpty() } ?: return null
        val password = when {
            !config.preSharedKey.isNullOrBlank() -> config.preSharedKey.trim('"')
            config.wepKeys?.any { !it.isNullOrBlank() } == true ->
                config.wepKeys.filterNotNull()
                    .filter { it.isNotBlank() }
                    .joinToString("\n") { it.trim('"') }
            else -> null
        }
        val freq = frequencyOf(config)
        val (band, channel) = bandOf(freq)
        return WifiEntry(
            ssid = ssid,
            password = password,
            security = securityLabel(config),
            bssid = config.BSSID?.takeIf { it.isNotBlank() && it != "null" },
            band = band,
            channel = channel,
            isHidden = config.hiddenSSID,
            authDetail = authDetailOf(config)
        )
    }

    /** 频率 → 频段与信道号；频率未知返回 null */
    private fun bandOf(freq: Int): Pair<String?, Int?> = when (freq) {
        in 2400..2500 -> "2.4 GHz" to (freq - 2407) / 5
        in 4900..5900 -> "5 GHz" to (freq - 5000) / 5
        in 5900..7200 -> "6 GHz" to (freq - 5950) / 5
        else -> null to null
    }

    /** frequency 字段在新 SDK 已从公开 API 移除，反射读取；读不到返回 0 */
    private fun frequencyOf(config: WifiConfiguration): Int = try {
        config.javaClass.getField("frequency").getInt(config)
    } catch (e: Exception) {
        0
    }

    private fun securityLabel(config: WifiConfiguration): String {
        val km = config.allowedKeyManagement
        return when {
            km.get(WifiConfiguration.KeyMgmt.SAE) -> "WPA3"
            km.get(WifiConfiguration.KeyMgmt.WPA_EAP) ||
                    km.get(WifiConfiguration.KeyMgmt.IEEE8021X) -> "802.1X 企业网"
            km.get(WifiConfiguration.KeyMgmt.OWE) -> "开放（增强）"
            km.get(WifiConfiguration.KeyMgmt.WPA_PSK) -> "WPA/WPA2"
            km.get(WifiConfiguration.KeyMgmt.NONE) ->
                if (config.allowedAuthAlgorithms.get(WifiConfiguration.AuthAlgorithm.SHARED)) "WEP"
                else "开放"
            else -> "其他"
        }
    }

    private fun authDetailOf(config: WifiConfiguration): String {
        val km = config.allowedKeyManagement
        val parts = buildList {
            if (km.get(WifiConfiguration.KeyMgmt.SAE)) add("SAE（WPA3-Personal）")
            if (km.get(WifiConfiguration.KeyMgmt.SUITE_B_192)) add("Suite-B-192（WPA3 企业 192 位）")
            if (km.get(WifiConfiguration.KeyMgmt.WPA_EAP)) add("802.1X EAP（WPA/WPA2/WPA3 企业）")
            if (km.get(WifiConfiguration.KeyMgmt.WPA_PSK)) add("PSK（WPA/WPA2-Personal）")
            if (km.get(WifiConfiguration.KeyMgmt.OWE)) add("OWE（增强开放）")
            if (km.get(WifiConfiguration.KeyMgmt.IEEE8021X)) add("IEEE 802.1X")
            if (km.get(WifiConfiguration.KeyMgmt.WAPI_PSK)) add("WAPI-PSK")
            if (km.get(WifiConfiguration.KeyMgmt.WAPI_CERT)) add("WAPI-CERT")
            if (km.get(WifiConfiguration.KeyMgmt.DPP)) add("DPP（Easy Connect）")
            if (isEmpty() && km.get(WifiConfiguration.KeyMgmt.NONE)) add("无认证（开放网络）")
        }
        return parts.joinToString(" / ").ifEmpty { "未知" }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 兜底通道：Root 读 WifiConfigStore.xml
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadViaRootConfigStore(): List<WifiEntry>? {
        val candidates = listOf(
            "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
            "/data/misc/apexconfig/com.android.wifi/WifiConfigStore.xml",
            "/data/misc/wifi/WifiConfigStore.xml"
        )
        for (path in candidates) {
            val xml = readAsRoot(path) ?: continue
            val entries = parseWifiConfigStore(xml)
            if (entries.isNotEmpty()) return entries
        }
        return null
    }

    private fun readAsRoot(path: String): String? = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $path"))
        val finished = process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        when {
            !finished -> {
                process.destroyForcibly()
                null
            }
            process.exitValue() != 0 -> null
            else -> process.inputStream.bufferedReader().readText()
                .takeIf { it.contains("WifiConfiguration") }
        }
    } catch (e: Exception) {
        Log.d(TAG, "Root 读取失败: $path", e)
        null
    }

    private val networkBlockRegex =
        Regex("<WifiConfiguration>(.*?)</WifiConfiguration>", RegexOption.DOT_MATCHES_ALL)
    private val keyMgmtRegex = Regex("<int name=\"KeyMgmt\" value=\"(\\d+)\"")
    private val frequencyRegex = Regex("<int name=\"Frequency\" value=\"(\\d+)\"")
    private val hiddenRegex = Regex("<boolean name=\"HiddenSSID\" value=\"true\"")

    private fun fieldRegex(name: String) =
        Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

    private fun parseWifiConfigStore(xml: String): List<WifiEntry> =
        networkBlockRegex.findAll(xml).mapNotNull { match ->
            val block = match.groupValues[1]
            val ssid = fieldRegex("SSID").find(block)?.groupValues?.get(1)
                ?.trim('"')?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val psk = fieldRegex("PreSharedKey").find(block)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() && it != "null" }
            val keyMgmt = keyMgmtRegex.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val freq = frequencyRegex.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val (band, channel) = bandOf(freq)
            WifiEntry(
                ssid = ssid,
                password = psk?.trim('"')?.let(::unescapeXml),
                security = keyMgmtLabel(keyMgmt),
                bssid = fieldRegex("BSSID").find(block)?.groupValues?.get(1)
                    ?.takeIf { it.isNotBlank() && it != "null" },
                band = band,
                channel = channel,
                isHidden = hiddenRegex.containsMatchIn(block),
                authDetail = null
            )
        }.distinctBy { it.ssid }
            .sortedBy { it.ssid.lowercase() }
            .toList()

    private fun keyMgmtLabel(value: Int): String = when (value) {
        1 -> "WPA/WPA2"          // WPA_PSK
        2, 3 -> "802.1X 企业网"   // WPA_EAP / IEEE8021X
        6 -> "开放（增强）"        // OWE
        7 -> "WPA3"              // SAE
        0 -> "开放"               // NONE
        else -> "其他"
    }

    private fun unescapeXml(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

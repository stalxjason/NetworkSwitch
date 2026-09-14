package io.github.stalxjason.networkswitch.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcelable
import android.util.Log
import androidx.annotation.StringRes
import io.github.stalxjason.networkswitch.R
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
 *
 * 展示文案在数据层只存字符串资源 id，由 Adapter 结合 Context 解析。
 */
object WifiPasswordProvider {

    private const val TAG = "WifiPasswordProvider"
    private const val SHELL_PKG = "com.android.shell"
    private const val ATTRIBUTION_BUNDLE_KEY = "EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE"
    private const val ROOT_TIMEOUT_SECONDS = 10L

    data class WifiEntry(
        val ssid: String,
        val password: String?,
        @StringRes val securityRes: Int,
        val bssid: String? = null,
        @StringRes val bandRes: Int? = null,          // null = 频率未知
        val channel: Int? = null,
        val isHidden: Boolean = false,
        @StringRes val authDetailRes: List<Int> = emptyList()
    )

    sealed interface Result {
        data class Ok(val list: List<WifiEntry>) : Result
        data class ShizukuUnavailable(val message: String) : Result
        data class Error(val message: String) : Result
    }

    suspend fun getSavedNetworks(context: Context): Result = withContext(Dispatchers.IO) {
        val status = ShizukuHelper.getStatus(context)
        if (status !is ShizukuHelper.Status.Authorized) {
            return@withContext Result.ShizukuUnavailable(shizukuHint(context, status))
        }

        try {
            Result.Ok(loadViaBinder(context))
        } catch (t: Throwable) {
            Log.w(TAG, "binder 取密失败，尝试 Root 兜底", t)
            val fallback = loadViaRootConfigStore()
            if (fallback != null) {
                Result.Ok(fallback)
            } else {
                Result.Error(
                    context.getString(
                        R.string.wifi_status_binder_failed,
                        t.javaClass.simpleName,
                        t.message ?: context.getString(R.string.err_unknown)
                    )
                )
            }
        }
    }

    private fun shizukuHint(context: Context, status: ShizukuHelper.Status): String = when (status) {
        is ShizukuHelper.Status.Running -> context.getString(R.string.shizuku_hint_running)
        is ShizukuHelper.Status.NotRunning -> context.getString(R.string.shizuku_hint_not_running)
        is ShizukuHelper.Status.NotInstalled -> context.getString(R.string.shizuku_hint_not_installed)
        is ShizukuHelper.Status.Authorized -> ""
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 主通道：Shizuku binder 反射
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("PrivateApi")
    @Suppress("UNCHECKED_CAST")
    private fun loadViaBinder(context: Context): List<WifiEntry> {
        val iwm = Class.forName("android.net.wifi.IWifiManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(
                null,
                ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.WIFI_SERVICE))
            ) ?: error(context.getString(R.string.wifi_binder_error))

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
        val (bandRes, channel) = bandOf(freq)
        return WifiEntry(
            ssid = ssid,
            password = password,
            securityRes = securityResOf(config),
            bssid = config.BSSID?.takeIf { it.isNotBlank() && it != "null" },
            bandRes = bandRes,
            channel = channel,
            isHidden = config.hiddenSSID,
            authDetailRes = authDetailResOf(config)
        )
    }

    /**
     * 频率 → 频段资源 id 与信道号；频率未知返回 null to null。
     * 信道按 ITU-R SM.3290 计算：2.4G ch1-13 间隔 5MHz 起于 2412，
     * ch14（仅日本）为 2484 单独处理；5G ch34 起于 5170；6G ch1 起于 5925。
     */
    internal fun bandOf(freq: Int): Pair<Int?, Int?> = when {
        freq == 2484 -> R.string.band_24 to 14
        freq in 2412..2472 -> R.string.band_24 to (freq - 2412) / 5 + 1
        freq in 5150..5825 || freq in 5850..5895 -> R.string.band_5 to (freq - 5000) / 5
        freq in 5925..7125 -> R.string.band_6 to (freq - 5925) / 5 + 1
        else -> null to null
    }

    /** frequency 字段在新 SDK 已从公开 API 移除，反射读取；读不到返回 0 */
    private fun frequencyOf(config: WifiConfiguration): Int = try {
        config.javaClass.getField("frequency").getInt(config)
    } catch (e: Exception) {
        0
    }

    @StringRes
    private fun securityResOf(config: WifiConfiguration): Int {
        val km = config.allowedKeyManagement
        return when {
            km.get(WifiConfiguration.KeyMgmt.SAE) -> R.string.sec_wpa3
            km.get(WifiConfiguration.KeyMgmt.WPA_EAP) ||
                    km.get(WifiConfiguration.KeyMgmt.IEEE8021X) -> R.string.sec_8021x
            km.get(WifiConfiguration.KeyMgmt.OWE) -> R.string.sec_owe
            km.get(WifiConfiguration.KeyMgmt.WPA_PSK) -> R.string.sec_wpa
            km.get(WifiConfiguration.KeyMgmt.NONE) ->
                if (config.allowedAuthAlgorithms.get(WifiConfiguration.AuthAlgorithm.SHARED)) R.string.sec_wep
                else R.string.sec_open
            else -> R.string.sec_other
        }
    }

    /** 认证方式明细的资源 id 列表；为空时由 Adapter 退回显示 securityRes */
    @StringRes
    private fun authDetailResOf(config: WifiConfiguration): List<Int> {
        val km = config.allowedKeyManagement
        return buildList {
            if (km.get(WifiConfiguration.KeyMgmt.SAE)) add(R.string.auth_sae)
            if (km.get(WifiConfiguration.KeyMgmt.SUITE_B_192)) add(R.string.auth_suiteb)
            if (km.get(WifiConfiguration.KeyMgmt.WPA_EAP)) add(R.string.auth_eap)
            if (km.get(WifiConfiguration.KeyMgmt.WPA_PSK)) add(R.string.auth_psk)
            if (km.get(WifiConfiguration.KeyMgmt.OWE)) add(R.string.auth_owe)
            if (km.get(WifiConfiguration.KeyMgmt.IEEE8021X)) add(R.string.auth_ieee8021x)
            if (km.get(WifiConfiguration.KeyMgmt.WAPI_PSK)) add(R.string.auth_wapi_psk)
            if (km.get(WifiConfiguration.KeyMgmt.WAPI_CERT)) add(R.string.auth_wapi_cert)
            if (km.get(WifiConfiguration.KeyMgmt.DPP)) add(R.string.auth_dpp)
            if (isEmpty() && km.get(WifiConfiguration.KeyMgmt.NONE)) add(R.string.auth_none)
        }
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
            val (bandRes, channel) = bandOf(freq)
            WifiEntry(
                ssid = ssid,
                password = psk?.trim('"')?.let(::unescapeXml),
                securityRes = keyMgmtRes(keyMgmt),
                bssid = fieldRegex("BSSID").find(block)?.groupValues?.get(1)
                    ?.takeIf { it.isNotBlank() && it != "null" },
                bandRes = bandRes,
                channel = channel,
                isHidden = hiddenRegex.containsMatchIn(block)
            )
        }.distinctBy { it.ssid }
            .sortedBy { it.ssid.lowercase() }
            .toList()

    @StringRes
    private fun keyMgmtRes(value: Int): Int = when (value) {
        1 -> R.string.sec_wpa       // WPA_PSK
        2, 3 -> R.string.sec_8021x  // WPA_EAP / IEEE8021X
        6 -> R.string.sec_owe       // OWE
        7 -> R.string.sec_wpa3      // SAE
        0 -> R.string.sec_open      // NONE
        else -> R.string.sec_other
    }

    private fun unescapeXml(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

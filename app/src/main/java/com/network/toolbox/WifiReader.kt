package com.network.toolbox

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object WifiReader {

    private const val TAG = "NetworkToolbox"

    private val _debugLog = StringBuilder()
    val debugLog: String get() = _debugLog.toString()

    private fun log(msg: String) {
        Log.d(TAG, msg)
        _debugLog.appendLine(msg)
    }

    suspend fun readWifiNetworks(): List<WifiConfiguration> = withContext(Dispatchers.IO) {
        _debugLog.clear()
        log("=== WiFi Password Reader ===")
        log("Android SDK: ${Build.VERSION.SDK_INT}, Device: ${Build.MANUFACTURER} ${Build.MODEL}")

        try {
            val networks = getWifiViaPrivilegedApi()
            log("SUCCESS: Found ${networks.size} networks")
            networks
        } catch (e: Exception) {
            log("FAILED: ${e.message}")
            Log.e(TAG, "Failed", e)
            emptyList()
        }
    }

    @SuppressLint("PrivateApi")
    private fun getWifiViaPrivilegedApi(): List<WifiConfiguration> {
        log("Loading IWifiManager via reflection...")

        val base = Class.forName("android.net.wifi.IWifiManager")
        val stub = Class.forName("android.net.wifi.IWifiManager\$Stub")
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)

        val wifiBinder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
            ?: throw IllegalStateException("WiFi service binder is null")

        val wrappedBinder = ShizukuBinderWrapper(wifiBinder)
        val iwm = asInterface.invoke(null, wrappedBinder)
        log("Got IWifiManager: ${iwm?.javaClass?.name}")

        val user = "shell"
        val pkg = "com.android.shell"

        val rawResult: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            log("Android 13+, using AttributionSource Bundle...")
            val method = base.getMethod(
                "getPrivilegedConfiguredNetworks",
                String::class.java, String::class.java, Bundle::class.java
            )
            val bundle = Bundle().apply {
                val sourceClass = Class.forName("android.content.AttributionSource")
                val attributionSource = sourceClass.getConstructor(
                    Int::class.java, String::class.java,
                    String::class.java, Set::class.java,
                    Class.forName("android.content.AttributionSource")
                ).newInstance(Shizuku.getUid(), pkg, pkg, null as Set<String>?, null) as android.os.Parcelable
                putParcelable("EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE", attributionSource)
            }
            method.invoke(iwm, user, pkg, bundle)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            log("Android 11-12, trying 2-param...")
            try {
                base.getMethod("getPrivilegedConfiguredNetworks", String::class.java, String::class.java)
                    .invoke(iwm, user, pkg)
            } catch (_: NoSuchMethodException) {
                base.getMethod("getPrivilegedConfiguredNetworks", String::class.java, String::class.java, Bundle::class.java)
                    .invoke(iwm, user, pkg, null)
            }
        } else {
            base.getMethod("getPrivilegedConfiguredNetworks", String::class.java, String::class.java, Bundle::class.java)
                .invoke(iwm, user, pkg, null)
        }

        @Suppress("UNCHECKED_CAST")
        val list = rawResult?.let {
            try {
                it::class.java.getMethod("getList").invoke(it) as? List<WifiConfiguration>
            } catch (_: Exception) { null }
        } ?: emptyList()

        log("Got ${list.size} WifiConfiguration items")
        return list
            .sortedBy { it.SSID?.lowercase() }
            .distinctBy { it.SSID?.replace("\"", "")?.trim()?.lowercase() }
    }
}

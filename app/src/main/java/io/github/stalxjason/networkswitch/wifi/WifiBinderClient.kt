package io.github.stalxjason.networkswitch.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcelable
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/**
 * 以 shell 身份问 WifiService 要「含密码的配置列表」。
 *
 * 平台事实（与实现无关，谁写都一样）：
 *  - `IWifiManager.getPrivilegedConfiguredNetworks(...)` 是 @hide 接口，返回 ParceledListSlice；
 *  - Android 13 起签名多一个 `Bundle` 参数，需携带调用方的 `AttributionSource`；
 *  - 通过 Shizuku 拿到的 binder 必须用 `ShizukuBinderWrapper` 包一层，
 *    否则 IPC 的 UID 仍是本应用。
 *
 * 实现上不写死参数个数：从接口方法里挑出目标重载，再按它的形参类型逐个填值。
 * 这样 ROM 改了签名（多一个参数 / 少一个参数）时不需要再补一个 try/catch 分支。
 */
internal class WifiBinderClient {

    fun privilegedConfigurations(): List<WifiConfiguration> {
        val query = privilegedQueryMethod()
        val manager = remoteWifiManager()
        val result = query.invoke(manager, *queryArguments(query.parameterTypes))
        return asConfigurationList(result)
    }

    // ── 远端服务 ──────────────────────────────────────────────────────────

    @SuppressLint("PrivateApi")
    private fun remoteWifiManager(): Any {
        val serviceBinder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        val asInterface = Class.forName(WIFI_MANAGER_STUB)
            .getMethod("asInterface", IBinder::class.java)
        return asInterface.invoke(null, ShizukuBinderWrapper(serviceBinder))
            ?: throw IllegalStateException("IWifiManager binder is null")
    }

    /**
     * 挑出 `getPrivilegedConfiguredNetworks` 的重载。
     * Android 13+ 一定有带 Bundle 的版本；更早的版本优先用参数最少的那个。
     */
    @SuppressLint("PrivateApi")
    private fun privilegedQueryMethod(): Method {
        cachedQuery?.let { return it }
        val candidates = Class.forName(WIFI_MANAGER_INTERFACE)
            .declaredMethods
            .filter { it.name == QUERY_METHOD }
        require(candidates.isNotEmpty()) { "IWifiManager.$QUERY_METHOD not found" }

        val picked = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) {
            candidates.maxBy { it.parameterCount }
        } else {
            candidates.minBy { it.parameterCount }
        }
        cachedQuery = picked
        return picked
    }

    /**
     * 按形参类型填值：
     *  - 第一个 String = 调用方身份（root / system / shell），WifiService 用它解析用户；
     *  - 第二个 String = 调用方包名；
     *  - Bundle = Android 13+ 的额外参数，装 AttributionSource。
     */
    private fun queryArguments(types: Array<Class<*>>): Array<Any?> =
        Array(types.size) { index ->
            when {
                types[index] == String::class.java && index == 0 -> callerIdentity()
                types[index] == String::class.java -> SHELL_PACKAGE
                types[index] == Bundle::class.java -> attributionExtras()
                else -> null
            }
        }

    /** Shizuku 以哪个 uid 运行，就用哪个身份调用；WifiService 据此判定权限 */
    private fun callerIdentity(): String = when (Shizuku.getUid()) {
        0 -> "root"
        1000 -> "system"
        else -> "shell"
    }

    /**
     * Android 13+ 必须带 AttributionSource，否则 WifiService 直接拒；
     * 更早的版本没有这个要求，返回 null 即可。
     */
    private fun attributionExtras(): Bundle? {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) return null
        val source = newAttributionSource() ?: return null
        return Bundle().apply { putParcelable(ATTR_SOURCE_KEY, source) }
    }

    /**
     * AttributionSource 的 5 参构造是 @hide（不在 android.jar 里），只能运行时反射。
     * 按形参类型精确挑，不能只看参数个数——同类里还有别的 5 参构造。
     */
    @SuppressLint("PrivateApi")
    private fun newAttributionSource(): Parcelable? = runCatching {
        val clazz = Class.forName("android.content.AttributionSource")
        val ctor = clazz.constructors.firstOrNull { candidate ->
            val types = candidate.parameterTypes
            types.size == ATTRIBUTION_ARITY &&
                types[0] == Integer.TYPE &&
                types[1] == String::class.java &&
                types[2] == String::class.java &&
                Set::class.java.isAssignableFrom(types[3]) &&
                types[4] == clazz
        } ?: error("AttributionSource ctor not found")

        ctor.newInstance(
            Shizuku.getUid(),
            SHELL_PACKAGE,
            SHELL_PACKAGE,
            null,
            null
        ) as Parcelable
    }.onFailure { Log.w(TAG, "构造 AttributionSource 失败，特权接口将缺少调用方信息", it) }
        .getOrNull()

    // ── 返回值拆包 ────────────────────────────────────────────────────────

    /** 返回的是 ParceledListSlice，`getList()` 才是真正的 List；个别 ROM 直接给 List */
    @Suppress("UNCHECKED_CAST")
    private fun asConfigurationList(result: Any?): List<WifiConfiguration> = when (result) {
        null -> emptyList()
        is List<*> -> result.filterIsInstance<WifiConfiguration>()
        else -> runCatching {
            (result.javaClass.getMethod("getList").invoke(result) as? List<*>)
                .orEmpty()
                .filterIsInstance<WifiConfiguration>()
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val TAG = "WifiBinderClient"
        const val WIFI_MANAGER_INTERFACE = "android.net.wifi.IWifiManager"
        const val WIFI_MANAGER_STUB = "android.net.wifi.IWifiManager\$Stub"
        const val QUERY_METHOD = "getPrivilegedConfiguredNetworks"

        /** WifiService 里代表 shell 的包名 */
        const val SHELL_PACKAGE = "com.android.shell"

        /** 13+ 的额外参数里，AttributionSource 用的键名（@hide 常量，只能写字面量） */
        const val ATTR_SOURCE_KEY = "EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE"
        const val ATTRIBUTION_ARITY = 5

        @Volatile
        private var cachedQuery: Method? = null
    }
}

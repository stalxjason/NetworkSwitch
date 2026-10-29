package io.github.stalxjason.networkswitch

import android.content.Context

/**
 * 应用级设置（与主题偏好分开存）
 */
object AppSettings {

    private const val PREFS = "app_settings"
    private const val KEY_ALLOW_ROOT = "allow_root_fallback"

    /**
     * 是否允许在 Shizuku 通道失败时用 Root 兜底。
     *
     * 默认关闭：已 Root 设备上 `su` 会弹授权框，用户可能只是想点一下 WiFi 密码页，
     * 不该被静默提权。用户勾选后才在取密失败时尝试 Root。
     */
    fun allowRootFallback(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALLOW_ROOT, false)

    fun setAllowRootFallback(context: Context, allow: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_ROOT, allow).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

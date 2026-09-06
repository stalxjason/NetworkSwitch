package io.github.stalxjason.networkswitch

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/**
 * 外观设置：深浅模式 + 主题色
 *
 * 模式与主题色均持久化在 SharedPreferences；
 * 深浅模式走 AppCompatDelegate 全局生效，主题色由各 Activity 在
 * setContentView 前 applyStyle 覆盖 colorPrimary。
 */
object AppTheme {

    const val MODE_LIGHT = 0
    const val MODE_DARK = 1
    const val MODE_SYSTEM = 2

    val ACCENTS: List<Pair<String, Int>> = listOf(
        "blue" to R.style.AccentOverlay_Blue,
        "green" to R.style.AccentOverlay_Green,
        "purple" to R.style.AccentOverlay_Purple,
        "orange" to R.style.AccentOverlay_Orange,
    )

    private const val PREFS = "app_theme"
    private const val KEY_MODE = "mode"
    private const val KEY_ACCENT = "accent"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedMode(context: Context): Int = prefs(context).getInt(KEY_MODE, MODE_SYSTEM)

    fun savedAccent(context: Context): String = prefs(context).getString(KEY_ACCENT, "blue") ?: "blue"

    fun applyNightMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (savedMode(context)) {
                MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun applyAccent(activity: Activity) {
        val style = ACCENTS.firstOrNull { it.first == savedAccent(activity) }?.second
            ?: ACCENTS[0].second
        activity.theme.applyStyle(style, true)
    }

    fun setNightMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_MODE, mode).apply()
        applyNightMode(context)
    }

    fun setAccent(context: Context, key: String) {
        prefs(context).edit().putString(KEY_ACCENT, key).apply()
    }
}

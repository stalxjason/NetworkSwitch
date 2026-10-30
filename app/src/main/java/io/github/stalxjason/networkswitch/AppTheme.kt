package io.github.stalxjason.networkswitch

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat

/**
 * 外观设置：深浅模式 + 主题色
 *
 * 主题色不再是「只换 colorPrimary」。每个 Accent 是一整套色板，由
 * `AccentOverlay.*` 一次性交给主题的四个属性：
 *   colorPrimary —— 主按钮、行首图标、强调文字
 *   heroStart / heroEnd —— 主卡渐变（圆角渐变卡是这个界面的视觉主体）
 *   tileBg —— 行首图标瓦片底色（主色的淡色化）
 *
 * 页面里一律写 `?attr/colorPrimary`、`?attr/heroStart`、`?attr/heroEnd`、`?attr/tileBg`，
 * 所以换色之后主卡、按钮、图标是同一套颜色，不会再出现「主卡还是蓝的」。
 *
 * 深浅模式走 AppCompatDelegate（全局生效）；主题色在 setContentView 之前
 * 由 [applyAccent] 用 applyStyle 覆盖，因此换色需要 recreate()。
 */
object AppTheme {

    const val MODE_LIGHT = 0
    const val MODE_DARK = 1
    const val MODE_SYSTEM = 2

    /**
     * 一个主题色。
     *
     * @param key      持久化用的键，改名会让已保存的选择回落到蓝色
     * @param nameRes  弹窗里的无障碍标签
     * @param overlay  整套色板对应的主题 overlay
     * @param dotColor 弹窗里圆点用的主色（与 overlay 里的 colorPrimary 同值）
     */
    data class Accent(
        val key: String,
        @StringRes val nameRes: Int,
        @StyleRes val overlay: Int,
        @ColorRes val dotColor: Int,
    )

    val ACCENTS: List<Accent> = listOf(
        Accent("blue", R.string.accent_blue_name, R.style.AccentOverlay_Blue, R.color.accent_blue),
        Accent("green", R.string.accent_green_name, R.style.AccentOverlay_Green, R.color.accent_green),
        Accent("purple", R.string.accent_purple_name, R.style.AccentOverlay_Purple, R.color.accent_purple),
        Accent("orange", R.string.accent_orange_name, R.style.AccentOverlay_Orange, R.color.accent_orange),
        Accent("cyan", R.string.accent_cyan_name, R.style.AccentOverlay_Cyan, R.color.accent_cyan),
        Accent("rose", R.string.accent_rose_name, R.style.AccentOverlay_Rose, R.color.accent_rose),
    )

    private const val PREFS = "app_theme"
    private const val KEY_MODE = "mode"
    private const val KEY_ACCENT = "accent"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedMode(context: Context): Int = prefs(context).getInt(KEY_MODE, MODE_SYSTEM)

    fun savedAccent(context: Context): String =
        prefs(context).getString(KEY_ACCENT, ACCENTS[0].key) ?: ACCENTS[0].key

    /** 按键取主题色；键已失效（改名/降级）时回落到第一个，不让界面空转 */
    fun accentOf(key: String): Accent =
        ACCENTS.firstOrNull { it.key == key } ?: ACCENTS[0]

    fun accentColor(context: Context, accent: Accent): Int =
        ContextCompat.getColor(context, accent.dotColor)

    fun applyNightMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (savedMode(context)) {
                MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /** 必须在 setContentView 之前调用：applyStyle 覆盖 colorPrimary 与三个自定义色 */
    fun applyAccent(activity: Activity) {
        activity.theme.applyStyle(accentOf(savedAccent(activity)).overlay, true)
    }

    fun setNightMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_MODE, mode).apply()
        applyNightMode(context)
    }

    fun setAccent(context: Context, key: String) {
        prefs(context).edit().putString(KEY_ACCENT, accentOf(key).key).apply()
    }
}

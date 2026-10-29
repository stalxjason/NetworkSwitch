package io.github.stalxjason.networkswitch

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

/**
 * 两个桌面小组件的共同基类
 *
 * 共同部分：切网令牌校验、点击切网、PendingIntent 构造、按 widgetId 可取消的刷新任务、
 * 拖拽 resize 防抖。差异（布局、action、缩放、附加信息）交给子类。
 */
abstract class BaseNetworkWidgetProvider : AppWidgetProvider() {

    /** 组件布局资源 */
    protected abstract val layoutId: Int

    /** 切网广播 action；两个组件各用一个，避免互相触发 */
    protected abstract val toggleAction: String

    /** 组件当前尺寸（dp） */
    protected data class Size(val widthDp: Int, val heightDp: Int)

    /** 是否需要附加信息（双卡明细 / 内网 IP）；默认不查 */
    protected open fun wantsExtras(size: Size): Boolean = false

    /**
     * 把文案填进 RemoteViews。
     *
     * 默认实现适用于「同一套控件 id（tv_widget_mode / tv_widget_carrier / tv_widget_signal /
     * btn_widget_toggle / widget_root）」的布局，横排、竖排都能直接用；
     * 需要按尺寸缩放、改可见性的子类（如可自由拉伸款）覆盖它。
     */
    protected open fun buildRemoteViews(
        context: Context,
        widgetId: Int,
        size: Size,
        texts: WidgetTextFormatter.Texts
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layoutId)
        views.setTextViewText(R.id.tv_widget_mode, texts.modeLabel)
        views.setTextViewText(R.id.tv_widget_carrier, texts.carrierText)
        views.setTextViewText(R.id.tv_widget_signal, texts.signalText)

        views.setOnClickPendingIntent(R.id.btn_widget_toggle, togglePendingIntent(context))
        openAppPendingIntent(context)?.let {
            views.setOnClickPendingIntent(R.id.widget_root, it)
        }
        return views
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { render(context, appWidgetManager, it, debounceMs = 0L) }
    }

    /**
     * 拖拽调整尺寸时重建。连续 resize 会连发很多次，
     * 用防抖合并成一次，避免每帧都跑一遍 binder 查询。
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        render(context, appWidgetManager, appWidgetId, debounceMs = RESIZE_DEBOUNCE_MS)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == toggleAction && hasToggleToken(context, intent)) {
            handleToggle(context)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { jobs.remove(jobKey(this, it))?.cancel() }
    }

    override fun onDisabled(context: Context) {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 刷新
    // ─────────────────────────────────────────────────────────────────────────

    private fun render(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int,
        debounceMs: Long
    ) {
        val key = jobKey(this, widgetId)
        // 同一个 widget 的旧任务先取消：快速 resize 时，旧尺寸的结果不能盖掉新的
        jobs.remove(key)?.cancel()
        jobs[key] = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val size = withContext(Dispatchers.IO) { sizeOf(appWidgetManager, widgetId) }
            val texts = WidgetTextFormatter.load(context, wantsExtras(size))
            appWidgetManager.updateAppWidget(
                widgetId,
                buildRemoteViews(context, widgetId, size, texts)
            )
        }
    }

    /**
     * 组件当前尺寸取 MIN_*（launcher 会把它同步为当前 cell 的 dp 尺寸）。
     * 不能用 MAX_*——那代表容器允许的上限，拉高组件时取到的不是实际高度。
     * （OPTION_APPWIDGET_WIDTH/HEIGHT 更直接，但属 @UnsupportedAppUsage 不可编译引用）
     */
    private fun sizeOf(appWidgetManager: AppWidgetManager, widgetId: Int): Size {
        val opts = appWidgetManager.getAppWidgetOptions(widgetId)
        val widthDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            .takeIf { it > 0 } ?: DEFAULT_WIDTH_DP
        val heightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            .takeIf { it > 0 } ?: DEFAULT_HEIGHT_DP
        return Size(widthDp, heightDp)
    }

    private fun handleToggle(context: Context) {
        val cls = this.javaClass
        scope.launch {
            val result = NetworkModeHelper.toggleNetworkMode(context)
            // 未通过回读校验时用 result.message 诚实提示，不谎称「已切换到 X」
            val msg = if (result.success && result.verified) {
                context.getString(
                    R.string.toast_switch_to, context.getString(result.mode.labelRes)
                )
            } else {
                result.message
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

            val appWidgetManager = AppWidgetManager.getInstance(context)
            appWidgetManager.getAppWidgetIds(ComponentName(context, cls))
                .forEach { render(context, appWidgetManager, it, debounceMs = 0L) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 子类可复用的 PendingIntent
    // ─────────────────────────────────────────────────────────────────────────

    /** 切换按钮：显式广播 + 每次安装随机生成的令牌 */
    protected fun togglePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, this.javaClass).apply {
            action = toggleAction
            putExtra(EXTRA_TOGGLE_TOKEN, toggleToken(context))
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 点组件空白处打开 App；取不到启动 Intent 时返回 null */
    protected fun openAppPendingIntent(context: Context): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(
                context, 1, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

    companion object {
        private const val RESIZE_DEBOUNCE_MS = 350L
        private const val DEFAULT_WIDTH_DP = 110
        private const val DEFAULT_HEIGHT_DP = 40

        // 切网令牌：两个 receiver 现在是 exported=false（见 Manifest），外部应用根本发不进来；
        // 令牌是第二道保险——万一哪天有人把 receiver 改回导出，伪造广播仍需要令牌。
        // 令牌在安装后随机生成、只存在应用私有目录。
        internal const val EXTRA_TOGGLE_TOKEN = "extra_toggle_token"
        private const val TOGGLE_PREFS = "widget_toggle"
        private const val PREF_TOGGLE_TOKEN = "toggle_token"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        /** 刷新任务表：widgetId 在两个组件之间会重复，键里带上具体类 */
        private val jobs = HashMap<String, Job>()

        private fun jobKey(provider: BaseNetworkWidgetProvider, widgetId: Int): String =
            "${provider.javaClass.name}#$widgetId"

        /**
         * 取本机令牌，首次调用时生成并落盘。
         * 加锁：生成是「读 → 没有就写」，并发调用会各生成一个，导致 PendingIntent 与校验不一致。
         */
        @Synchronized
        @SuppressLint("ApplySharedPref")   // 有意用 commit：返回前必须落盘
        internal fun toggleToken(context: Context): String {
            val prefs = context.applicationContext
                .getSharedPreferences(TOGGLE_PREFS, Context.MODE_PRIVATE)
            prefs.getString(PREF_TOGGLE_TOKEN, null)?.let { return it }
            val fresh = UUID.randomUUID().toString()
            // 用 commit 而不是 apply：返回时保证已落盘，避免进程被杀后令牌变化
            prefs.edit().putString(PREF_TOGGLE_TOKEN, fresh).commit()
            return fresh
        }

        /** 校验切网意图是否携带本应用 PendingIntent 写入的令牌 */
        internal fun hasToggleToken(context: Context, intent: Intent): Boolean {
            val expected = toggleToken(context)
            val actual = intent.getStringExtra(EXTRA_TOGGLE_TOKEN) ?: return false
            // 定长比较，避免逐字符短路泄漏前缀
            return MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
        }

        /** 向指定组件发一次 APPWIDGET_UPDATE */
        fun updateWidget(context: Context, cls: Class<out BaseNetworkWidgetProvider>) {
            val intent = Intent(context, cls).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                val ids = AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(ComponentName(context, cls))
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}

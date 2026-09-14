package io.github.stalxjason.networkswitch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网络切换桌面 Widget (4x2 澎湃OS3 深色风格)
 *
 * 左：当前网络模式 (LTE/5G NR) + 运营商 + 信号强度
 * 右：切换按钮
 *
 * 展示数据在子线程取（getCurrentMode / getSignalInfo 内部有跨进程 binder 调用），
 * RemoteViews 回主线程拼装，避免桌面渲染时卡主线程。
 */
class NetworkWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "io.github.stalxjason.networkswitch.ACTION_TOGGLE"

        // 切网令牌：widget receiver 必须 exported（PendingIntent 触发需要），
        // android:exported=false 与包可见性都拦不住显式组件广播。
        // 令牌挡住「随手发的显式广播」，但不是硬边界——读常量即可拿到。
        internal const val EXTRA_TOGGLE_TOKEN = "extra_toggle_token"
        internal const val TOGGLE_TOKEN = "networkswitch.toggle.v1"

        /** 校验切网意图是否携带本应用 PendingIntent 写入的令牌 */
        internal fun hasToggleToken(intent: Intent): Boolean =
            intent.getStringExtra(EXTRA_TOGGLE_TOKEN) == TOGGLE_TOKEN

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        fun updateWidget(context: Context) {
            val intent = Intent(context, NetworkWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                val ids = AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(ComponentName(context, NetworkWidgetProvider::class.java))
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }

    /** Widget 文案快照：所有 IO 查询结果，主线程只做赋值 */
    private data class WidgetTexts(
        val modeLabel: String,
        val carrierText: String,
        val signalText: String
    )

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE && hasToggleToken(intent)) {
            handleToggle(context)
        }
    }

    private fun handleToggle(context: Context) {
        scope.launch {
            val result = NetworkModeHelper.toggleNetworkMode(context)
            val msg = if (result.success) {
                context.getString(
                    R.string.toast_switch_to, context.getString(result.mode.labelRes)
                )
            } else {
                result.message
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(
                ComponentName(context, NetworkWidgetProvider::class.java)
            )
            onUpdate(context, appWidgetManager, ids)
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ) {
        scope.launch {
            applyViews(context, appWidgetManager, widgetId, loadTexts(context))
        }
    }

    private suspend fun loadTexts(context: Context): WidgetTexts = withContext(Dispatchers.IO) {
        val modeLabel = try {
            context.getString(NetworkModeHelper.getCurrentMode(context).labelRes)
        } catch (_: Exception) {
            ""
        }

        var carrierText = context.getString(R.string.widget_carrier_unknown)
        var signalText = ""
        try {
            val dataSim = NetworkInfoHelper.getSignalInfo(context).sims.find { it.isDataSim }
            val carrier = dataSim?.carrierName ?: context.getString(R.string.widget_carrier_unknown)
            val netType = dataSim?.networkTypeName
            carrierText = if (netType != null) {
                context.getString(R.string.widget_carrier_net, carrier, netType)
            } else {
                carrier
            }
            signalText = buildString {
                val level = dataSim?.signalLevel ?: 0
                val dbm = dataSim?.signalDbm
                if (level > 0) append(context.getString(R.string.widget_signal_bars, level))
                if (dbm != null) {
                    if (isNotEmpty()) append(context.getString(R.string.signal_separator))
                    append(context.getString(R.string.signal_dbm, dbm))
                }
                if (isEmpty()) append(context.getString(R.string.widget_signal_none))
            }
        } catch (_: Exception) {}

        WidgetTexts(modeLabel, carrierText, signalText)
    }

    private fun applyViews(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int,
        texts: WidgetTexts
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_network)
        views.setTextViewText(R.id.tv_widget_mode, texts.modeLabel)
        views.setTextViewText(R.id.tv_widget_carrier, texts.carrierText)
        views.setTextViewText(R.id.tv_widget_signal, texts.signalText)

        // 切换按钮点击
        val toggleIntent = Intent(context, NetworkWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE
            putExtra(EXTRA_TOGGLE_TOKEN, TOGGLE_TOKEN)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_widget_toggle, pendingIntent)

        // 整个 widget 点击也能打开 app
        val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (openAppIntent != null) {
            val openPendingIntent = PendingIntent.getActivity(
                context, 1, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openPendingIntent)
        }

        appWidgetManager.updateAppWidget(widgetId, views)
    }
}

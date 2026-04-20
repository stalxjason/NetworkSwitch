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

/**
 * 网络切换桌面 Widget (4x2 澎湃OS3 深色风格)
 *
 * 左：当前网络模式 (LTE/5G NR) + 运营商 + 信号强度
 * 右：切换按钮
 */
class NetworkWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "io.github.stalxjason.networkswitch.ACTION_TOGGLE"
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
        if (intent.action == ACTION_TOGGLE) {
            handleToggle(context)
        }
    }

    private fun handleToggle(context: Context) {
        scope.launch {
            val result = NetworkModeHelper.toggleNetworkMode(context)
            val msg = if (result.success) {
                "已切换到 ${result.currentMode.label}"
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
        val views = RemoteViews(context.packageName, R.layout.widget_network)

        // 当前网络模式
        val currentMode = NetworkModeHelper.getCurrentMode(context)
        views.setTextViewText(R.id.tv_widget_mode, currentMode.label)

        // 运营商 + 信号
        try {
            val signalInfo = NetworkInfoHelper.getSignalInfo(context)
            val dataSim = signalInfo.sims.find { it.isDataSim }
            val carrier = dataSim?.carrierName ?: "未知运营商"
            val netType = dataSim?.networkTypeName
            val carrierText = buildString {
                append(carrier)
                if (netType != null) append(" · $netType")
            }
            views.setTextViewText(R.id.tv_widget_carrier, carrierText)

            // 信号信息
            val dbm = dataSim?.signalDbm
            val level = dataSim?.signalLevel ?: 0
            val signalText = buildString {
                if (level > 0) append("$level/4 格")
                if (dbm != null) {
                    if (isNotEmpty()) append("  ")
                    append("${dbm} dBm")
                }
                if (isEmpty()) append("无信号")
            }
            views.setTextViewText(R.id.tv_widget_signal, signalText)
        } catch (_: Exception) {
            views.setTextViewText(R.id.tv_widget_carrier, "未知运营商")
            views.setTextViewText(R.id.tv_widget_signal, "")
        }

        // 切换按钮点击
        val toggleIntent = Intent(context, NetworkWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE
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

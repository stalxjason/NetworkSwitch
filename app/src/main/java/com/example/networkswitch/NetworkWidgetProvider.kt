package com.example.networkswitch

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
 * 网络模式切换桌面 Widget
 *
 * 切换优先使用 Shizuku，其次 Root，最后回退到打开系统设置。
 */
class NetworkWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "com.example.networkswitch.ACTION_TOGGLE"
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

            // 更新所有 Widget 实例
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

        val currentMode = NetworkModeHelper.getCurrentMode(context)
        views.setTextViewText(R.id.tv_network_mode, currentMode.label)

        // 5G 绿色，4G 蓝色
        if (currentMode == NetworkMode.NR_5G) {
            views.setInt(R.id.tv_network_mode, "setTextColor", 0xFF4CAF50.toInt())
        } else {
            views.setInt(R.id.tv_network_mode, "setTextColor", 0xFF2196F3.toInt())
        }

        // 点击 → 切换
        val toggleIntent = Intent(context, NetworkWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        appWidgetManager.updateAppWidget(widgetId, views)
    }
}

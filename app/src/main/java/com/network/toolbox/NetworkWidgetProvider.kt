package com.network.toolbox

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
import java.net.Inet4Address
import java.net.NetworkInterface

class NetworkWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "com.network.toolbox.ACTION_TOGGLE"
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
        val label = currentMode.label
        views.setTextViewText(R.id.tv_widget_badge, label)
        views.setTextViewText(R.id.tv_widget_mode, label)

        // 运营商 + 信号
        try {
            val sims = NetworkInfoHelper.getSimInfo(context)
            val dataSim = sims.find { it.isDataSim }
            views.setTextViewText(R.id.tv_widget_carrier, dataSim?.carrierName ?: "未知运营商")
            val dbm = dataSim?.signalDbm
            val signalText = if (dbm != null) "${dbm} dBm" else "-- dBm"
            views.setTextViewText(R.id.tv_widget_signal, signalText)
        } catch (_: Exception) {
            views.setTextViewText(R.id.tv_widget_carrier, "未知运营商")
            views.setTextViewText(R.id.tv_widget_signal, "-- dBm")
        }

        // IP 地址
        views.setTextViewText(R.id.tv_widget_ip, getLocalIpAddress())

        // 连接状态
        views.setTextViewText(R.id.tv_widget_status, when (label) {
            "5G" -> "5G 已连接"
            "4G" -> "4G 已连接"
            else -> "已连接"
        })

        // 切换按钮点击
        val toggleIntent = Intent(context, NetworkWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.tv_widget_mode, pendingIntent)

        appWidgetManager.updateAppWidget(widgetId, views)
    }

    private fun getLocalIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "无网络"
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses ?: continue
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
            "无网络"
        } catch (_: Exception) {
            "无网络"
        }
    }
}
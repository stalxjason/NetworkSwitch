package io.github.stalxjason.networkswitch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 可自由调节尺寸的桌面小组件
 *
 * 内容与 NetworkWidgetProvider 一致；差异点：
 * 1. resizeMode 开放横向+纵向，桌面长按可任意拉伸；
 * 2. 文字与切换按钮大小随组件实际尺寸缩放
 *    （读取 AppWidgetOptions 的 dp 宽高，取宽/高缩放系数的较小值，
 *     宽度基准 110dp、高度基准 40dp，与默认 2x1 一致）。
 */
class ResizableNetworkWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "io.github.stalxjason.networkswitch.ACTION_TOGGLE_RESIZABLE"
        private const val BASE_WIDTH_DP = 110f
        private const val BASE_HEIGHT_DP = 40f
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        fun updateWidget(context: Context) {
            val intent = Intent(context, ResizableNetworkWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                val ids = AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(ComponentName(context, ResizableNetworkWidgetProvider::class.java))
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

    /** 拖拽调整尺寸时实时重排文字 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        updateAppWidget(context, appWidgetManager, appWidgetId)
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
                "已切换到 ${result.mode.label}"
            } else {
                result.message
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(
                ComponentName(context, ResizableNetworkWidgetProvider::class.java)
            )
            onUpdate(context, appWidgetManager, ids)
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_network_resizable)

        // 文字缩放系数：取宽/高缩放比较小者，避免只拉高时不必要的大字
        val opts = appWidgetManager.getAppWidgetOptions(widgetId)
        val widthDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            .takeIf { it > 0 } ?: BASE_WIDTH_DP.toInt()
        val heightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            .takeIf { it > 0 } ?: BASE_HEIGHT_DP.toInt()
        val widthScale = (widthDp / BASE_WIDTH_DP).coerceIn(0.6f, 3.5f)
        val heightScale = (heightDp / BASE_HEIGHT_DP).coerceIn(0.6f, 3.5f)
        val scale = minOf(widthScale, heightScale)

        // 横向拉宽（约 1.6 倍以上）时信息文字整体水平居中，窄时靠左；
        // setGravity 会整体覆盖，垂直居中位需要一并设置
        val infoGravity =
            if (widthDp >= 180) android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.CENTER_VERTICAL
            else android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        views.setInt(R.id.widget_info_column, "setGravity", infoGravity)

        // 竖向拉伸（高约 3 格以上）时展开附加信息：双卡信号明细 + 内网 IP
        val tall = heightDp >= 120

        // 当前网络模式
        val currentMode = NetworkModeHelper.getCurrentMode(context)
        views.setTextViewText(R.id.tv_widget_mode, currentMode.label)

        // 运营商 + 信号
        try {
            val signalInfo = NetworkInfoHelper.getSignalInfo(context)
            val dataSim = signalInfo.sims.find { it.isDataSim }
            val carrier = dataSim?.carrierName ?: "未知运营商"
            val netType = dataSim?.networkTypeName
            views.setTextViewText(
                R.id.tv_widget_carrier,
                buildString {
                    append(carrier)
                    if (netType != null) append(" · $netType")
                }
            )

            val dbm = dataSim?.signalDbm
            val level = dataSim?.signalLevel ?: 0
            views.setTextViewText(
                R.id.tv_widget_signal,
                buildString {
                    if (level > 0) append("$level/4 格")
                    if (dbm != null) {
                        if (isNotEmpty()) append("  ")
                        append("${dbm} dBm")
                    }
                    if (isEmpty()) append("无信号")
                }
            )
        } catch (_: Exception) {
            views.setTextViewText(R.id.tv_widget_carrier, "未知运营商")
            views.setTextViewText(R.id.tv_widget_signal, "")
        }

        // 竖向拉伸时：双卡信号明细 + 内网 IP（窄矮组件自动隐藏）
        try {
            val signalInfo = NetworkInfoHelper.getSignalInfo(context)
            val simsText = signalInfo.sims.sortedBy { it.slotIndex }.joinToString("\n") { sim ->
                buildString {
                    append("SIM${sim.slotIndex + 1} ")
                    append(sim.carrierName ?: "")
                    if (sim.isDataSim) append(" ★")
                    sim.networkTypeName?.let { append(" ").append(it) }
                    sim.signalDbm?.let { append("  ${it}dBm") }
                }
            }
            if (tall && simsText.isNotEmpty()) {
                views.setTextViewText(R.id.tv_widget_sims, simsText)
                views.setViewVisibility(R.id.tv_widget_sims, android.view.View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.tv_widget_sims, android.view.View.GONE)
            }
        } catch (_: Exception) {
            views.setViewVisibility(R.id.tv_widget_sims, android.view.View.GONE)
        }

        try {
            val ipText = IpHelper.getAllIpEntries(context).take(4).joinToString("\n") { entry ->
                buildString {
                    append(
                        when (entry.kind) {
                            IpHelper.Kind.MOBILE -> entry.simSlotIndex?.let { "SIM${it + 1}" } ?: entry.ifaceName
                            IpHelper.Kind.WIFI -> "WLAN"
                            IpHelper.Kind.ETHERNET -> "以太网"
                            IpHelper.Kind.VPN -> "VPN"
                        }
                    )
                    append("  ")
                    append(entry.ipv4 ?: entry.ipv6 ?: "")
                }
            }
            if (tall && ipText.isNotEmpty()) {
                views.setTextViewText(R.id.tv_widget_ips, ipText)
                views.setViewVisibility(R.id.tv_widget_ips, android.view.View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.tv_widget_ips, android.view.View.GONE)
            }
        } catch (_: Exception) {
            views.setViewVisibility(R.id.tv_widget_ips, android.view.View.GONE)
        }

        // 文字大小随组件尺寸缩放
        views.setTextViewTextSize(R.id.tv_widget_mode, TypedValue.COMPLEX_UNIT_SP, 22f * scale)
        views.setTextViewTextSize(R.id.tv_widget_carrier, TypedValue.COMPLEX_UNIT_SP, 10f * scale)
        views.setTextViewTextSize(R.id.tv_widget_signal, TypedValue.COMPLEX_UNIT_SP, 9f * scale)
        views.setTextViewTextSize(R.id.tv_widget_btn_text, TypedValue.COMPLEX_UNIT_SP, 9f * scale)

        // 切换按钮尺寸同步缩放（API 31+ 支持按 RemoteViews 设置视图尺寸）
        val buttonSize = 40f * scale
        views.setViewLayoutWidth(R.id.btn_widget_toggle, buttonSize, TypedValue.COMPLEX_UNIT_DIP)
        views.setViewLayoutHeight(R.id.btn_widget_toggle, buttonSize, TypedValue.COMPLEX_UNIT_DIP)

        // 切换按钮点击
        val toggleIntent = Intent(context, ResizableNetworkWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_widget_toggle, pendingIntent)

        // 整个 widget 点击打开 app
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

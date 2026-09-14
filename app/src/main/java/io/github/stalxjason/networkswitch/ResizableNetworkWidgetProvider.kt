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
import kotlinx.coroutines.withContext

/**
 * 可自由调节尺寸的桌面小组件
 *
 * 内容与 NetworkWidgetProvider 一致；差异点：
 * 1. resizeMode 开放横向+纵向，桌面长按可任意拉伸；
 * 2. 文字与切换按钮大小随组件实际尺寸缩放
 *   （取宽/高缩放系数的较小值，宽度基准 110dp、高度基准 40dp，与默认 2x1 一致）。
 *
 * 尺寸与展示数据都在子线程取（含跨进程 binder 查询），RemoteViews 回主线程拼装。
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

    /** 组件当前尺寸（dp）+ 文案快照；全部查询结果，主线程只做赋值 */
    private data class Snapshot(
        val widthDp: Int,
        val heightDp: Int,
        val modeLabel: String,
        val carrierText: String,
        val signalText: String,
        val simsText: String,
        val ipText: String
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
        // 令牌校验见 NetworkWidgetProvider.EXTRA_TOGGLE_TOKEN 说明
        if (intent.action == ACTION_TOGGLE && NetworkWidgetProvider.hasToggleToken(intent)) {
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
        scope.launch {
            applyViews(context, appWidgetManager, widgetId, loadSnapshot(context, appWidgetManager, widgetId))
        }
    }

    private suspend fun loadSnapshot(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ): Snapshot = withContext(Dispatchers.IO) {
        // 组件当前尺寸取 MIN_*（launcher 会把它同步为当前 cell 的 dp 尺寸）。
        // 不能用 MAX_* ——那代表容器允许的上限，拉高组件时取到的不是实际高度。
        // （OPTION_APPWIDGET_WIDTH/HEIGHT 虽更直接，但属 @UnsupportedAppUsage 不可编译引用）
        val opts = appWidgetManager.getAppWidgetOptions(widgetId)
        val widthDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 }
            ?: BASE_WIDTH_DP.toInt()
        val heightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).takeIf { it > 0 }
            ?: BASE_HEIGHT_DP.toInt()

        val modeLabel = try {
            context.getString(NetworkModeHelper.getCurrentMode(context).labelRes)
        } catch (_: Exception) {
            ""
        }

        var carrierText = context.getString(R.string.widget_carrier_unknown)
        var signalText = ""
        var simsText = ""
        try {
            // 只查一次：窄矮组件不需要双卡明细，但查询本身必须出主线程
            val sims = NetworkInfoHelper.getSignalInfo(context).sims
            val dataSim = sims.find { it.isDataSim }
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
            simsText = sims.sortedBy { it.slotIndex }.joinToString("\n") { sim ->
                buildString {
                    append(context.getString(R.string.sim_label, sim.slotIndex + 1))
                    append(" ")
                    append(sim.carrierName ?: "")
                    if (sim.isDataSim) append(context.getString(R.string.data_sim_mark))
                    sim.networkTypeName?.let { append(" ").append(it) }
                    sim.signalDbm?.let {
                        append(context.getString(R.string.signal_separator))
                        append(context.getString(R.string.signal_dbm, it))
                    }
                }
            }
        } catch (_: Exception) {}

        val ipText = try {
            IpHelper.getAllIpEntries(context).take(4).joinToString("\n") { entry ->
                buildString {
                    append(ipKindLabel(context, entry))
                    append(context.getString(R.string.signal_separator))
                    append(entry.ipv4 ?: entry.ipv6 ?: "")
                }
            }
        } catch (_: Exception) {
            ""
        }

        Snapshot(widthDp, heightDp, modeLabel, carrierText, signalText, simsText, ipText)
    }

    /** 紧凑行前缀：移动卡显示 SIM 号（归属不到时退回网卡名），其余显示接口类型 */
    private fun ipKindLabel(context: Context, entry: IpHelper.IpEntry): String = when (entry.kind) {
        IpHelper.Kind.MOBILE -> entry.simSlotIndex?.let {
            context.getString(R.string.sim_label, it + 1)
        } ?: entry.ifaceName
        IpHelper.Kind.WIFI -> context.getString(R.string.ip_label_wlan)
        IpHelper.Kind.ETHERNET -> context.getString(R.string.ip_label_ethernet)
        IpHelper.Kind.VPN -> context.getString(R.string.ip_label_vpn)
    }

    private fun applyViews(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int,
        snapshot: Snapshot
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_network_resizable)
        val widthDp = snapshot.widthDp
        val heightDp = snapshot.heightDp

        // 文字缩放系数：取宽/高缩放比较小者，避免只拉高时不必要的大字
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

        views.setTextViewText(R.id.tv_widget_mode, snapshot.modeLabel)
        views.setTextViewText(R.id.tv_widget_carrier, snapshot.carrierText)
        views.setTextViewText(R.id.tv_widget_signal, snapshot.signalText)

        // 双卡信号明细
        if (tall && snapshot.simsText.isNotEmpty()) {
            views.setTextViewText(R.id.tv_widget_sims, snapshot.simsText)
            views.setViewVisibility(R.id.tv_widget_sims, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.tv_widget_sims, android.view.View.GONE)
        }

        // 内网 IP
        if (tall && snapshot.ipText.isNotEmpty()) {
            views.setTextViewText(R.id.tv_widget_ips, snapshot.ipText)
            views.setViewVisibility(R.id.tv_widget_ips, android.view.View.VISIBLE)
        } else {
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
            putExtra(NetworkWidgetProvider.EXTRA_TOGGLE_TOKEN, NetworkWidgetProvider.TOGGLE_TOKEN)
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

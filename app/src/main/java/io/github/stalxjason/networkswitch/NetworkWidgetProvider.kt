package io.github.stalxjason.networkswitch

import android.content.Context
import android.widget.RemoteViews

/**
 * 网络切换桌面 Widget（经典 2x1，澎湃OS3 深色风格）
 *
 * 左：当前网络模式 (LTE / 5G NR) + 运营商 + 信号强度
 * 右：切换按钮
 *
 * 共用逻辑（令牌校验、点击切网、刷新调度）都在 [BaseNetworkWidgetProvider]。
 */
class NetworkWidgetProvider : BaseNetworkWidgetProvider() {

    override val layoutId: Int = R.layout.widget_network
    override val toggleAction: String = ACTION_TOGGLE

    override fun buildRemoteViews(
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

    companion object {
        const val ACTION_TOGGLE = "io.github.stalxjason.networkswitch.ACTION_TOGGLE"

        fun updateWidget(context: Context) =
            BaseNetworkWidgetProvider.updateWidget(context, NetworkWidgetProvider::class.java)
    }
}

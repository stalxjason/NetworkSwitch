package io.github.stalxjason.networkswitch

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews

/**
 * 可自由调节尺寸的桌面小组件
 *
 * 内容与 [NetworkWidgetProvider] 一致；差异点：
 * 1. resizeMode 开放横向 + 纵向，桌面长按可任意拉伸；
 * 2. 文字与切换按钮大小随组件实际尺寸缩放
 *    （取宽/高缩放系数的较小值，宽度基准 110dp、高度基准 40dp，与默认 2x1 一致）；
 * 3. 拉高到约 3 格以上才展开双卡明细与内网 IP —— 尺寸不够时连查都不查。
 */
class ResizableNetworkWidgetProvider : BaseNetworkWidgetProvider() {

    override val layoutId: Int = R.layout.widget_network_resizable
    override val toggleAction: String = ACTION_TOGGLE

    /** 只有拉高到能显示附加信息时才去查双卡明细与 IP */
    override fun wantsExtras(size: Size): Boolean = size.heightDp >= TALL_HEIGHT_DP

    override fun buildRemoteViews(
        context: Context,
        widgetId: Int,
        size: Size,
        texts: WidgetTextFormatter.Texts
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layoutId)

        // 文字缩放系数：取宽/高缩放比较小者，避免只拉高时不必要的大字
        val widthScale = (size.widthDp / BASE_WIDTH_DP).coerceIn(0.6f, 3.5f)
        val heightScale = (size.heightDp / BASE_HEIGHT_DP).coerceIn(0.6f, 3.5f)
        val scale = minOf(widthScale, heightScale)

        // 横向拉宽（约 1.6 倍以上）时信息文字整体水平居中，窄时靠左；
        // setGravity 会整体覆盖，垂直居中位需要一并设置
        val infoGravity =
            if (size.widthDp >= WIDE_WIDTH_DP) Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            else Gravity.START or Gravity.CENTER_VERTICAL
        views.setInt(R.id.widget_info_column, "setGravity", infoGravity)

        views.setTextViewText(R.id.tv_widget_mode, texts.modeLabel)
        views.setTextViewText(R.id.tv_widget_carrier, texts.carrierText)
        views.setTextViewText(R.id.tv_widget_signal, texts.signalText)

        // 双卡信号明细
        if (texts.simsText.isNotEmpty()) {
            views.setTextViewText(R.id.tv_widget_sims, texts.simsText)
            views.setViewVisibility(R.id.tv_widget_sims, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.tv_widget_sims, View.GONE)
        }

        // 内网 IP
        if (texts.ipText.isNotEmpty()) {
            views.setTextViewText(R.id.tv_widget_ips, texts.ipText)
            views.setViewVisibility(R.id.tv_widget_ips, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.tv_widget_ips, View.GONE)
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

        views.setOnClickPendingIntent(R.id.btn_widget_toggle, togglePendingIntent(context))
        openAppPendingIntent(context)?.let {
            views.setOnClickPendingIntent(R.id.widget_root, it)
        }
        return views
    }

    companion object {
        const val ACTION_TOGGLE = "io.github.stalxjason.networkswitch.ACTION_TOGGLE_RESIZABLE"

        private const val BASE_WIDTH_DP = 110f
        private const val BASE_HEIGHT_DP = 40f

        /** 拉宽到该宽度（dp）以上时信息整体居中 */
        private const val WIDE_WIDTH_DP = 180

        /** 拉高到该高度（dp）以上时展开附加信息 */
        private const val TALL_HEIGHT_DP = 120

        fun updateWidget(context: Context) = BaseNetworkWidgetProvider.updateWidget(
            context, ResizableNetworkWidgetProvider::class.java
        )
    }
}

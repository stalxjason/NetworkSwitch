package io.github.stalxjason.networkswitch

import android.content.Context

/**
 * 网络切换桌面小组件（竖向：文字在上、按钮在下）
 *
 * 与经典款内容一致，只是排版改为上下堆叠，适合窄而高的位置。
 * 令牌校验、点击切网、刷新调度都由 [BaseNetworkWidgetProvider] 提供。
 */
class NetworkVerticalWidgetProvider : BaseNetworkWidgetProvider() {

    override val layoutId: Int = R.layout.widget_network_vertical
    override val toggleAction: String = ACTION_TOGGLE

    companion object {
        const val ACTION_TOGGLE = "io.github.stalxjason.networkswitch.ACTION_TOGGLE_VERTICAL"

        fun updateWidget(context: Context) =
            BaseNetworkWidgetProvider.updateWidget(context, NetworkVerticalWidgetProvider::class.java)
    }
}

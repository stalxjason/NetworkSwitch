package io.github.stalxjason.networkswitch

import android.content.Context

/**
 * 网络切换桌面小组件（横向 2x1）
 *
 * 左：当前网络模式 + 运营商 + 信号强度；右：切换按钮。
 * 令牌校验、点击切网、刷新调度都由 [BaseNetworkWidgetProvider] 提供。
 */
class NetworkWidgetProvider : BaseNetworkWidgetProvider() {

    override val layoutId: Int = R.layout.widget_network
    override val toggleAction: String = ACTION_TOGGLE

    companion object {
        const val ACTION_TOGGLE = "io.github.stalxjason.networkswitch.ACTION_TOGGLE"

        fun updateWidget(context: Context) =
            BaseNetworkWidgetProvider.updateWidget(context, NetworkWidgetProvider::class.java)
    }
}

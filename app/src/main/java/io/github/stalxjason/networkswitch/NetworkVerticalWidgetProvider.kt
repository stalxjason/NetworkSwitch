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

    /**
     * 窄到 1 格时只放运营商名：1 格约 80~110dp，扣掉内边距只剩 ~70dp，
     * 「名字 · 网络类型」必然被截断；网络类型上面的大字模式（5G / 4G）已经说了。
     * 拉宽到 2 格（≥150dp）后恢复「名字 · 网络类型」。
     */
    override fun wantsCompactText(size: Size): Boolean = size.widthDp < COMPACT_WIDTH_DP

    companion object {
        const val ACTION_TOGGLE = "io.github.stalxjason.networkswitch.ACTION_TOGGLE_VERTICAL"

        /** 低于该宽度（dp）按 1 格窄组件处理 */
        private const val COMPACT_WIDTH_DP = 150

        fun updateWidget(context: Context) =
            BaseNetworkWidgetProvider.updateWidget(context, NetworkVerticalWidgetProvider::class.java)
    }
}

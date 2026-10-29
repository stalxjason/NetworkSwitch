package io.github.stalxjason.networkswitch.wifi

/**
 * 一条已保存的无线网络（UI 直接消费的模型，不含任何平台对象）
 *
 * 数据来自两条通道：Shizuku 特权接口返回的 WifiConfiguration，
 * 或 Root 读取的 WifiConfigStore.xml。两者都先收敛成这个模型，UI 只认它。
 *
 * 没有「频段 / 信道」：`WifiConfiguration` 无公开频率字段，
 * `WifiConfigStore.xml` 也不存频率，取不到的值不该出现在界面上。
 */
data class WifiEntry(
    val ssid: String,

    /** 明文密码；null = 没有可展示的密码（开放网络 / 企业网 / 系统未回显） */
    val passphrase: String?,

    val security: WifiSecurity,

    val bssid: String? = null,

    val hidden: Boolean = false,

    /** 认证方式明细，可能为空 */
    val authDetails: List<WifiAuthDetail> = emptyList()
)

package io.github.stalxjason.networkswitch

import android.app.Application
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 全局 Application
 *
 * 启动时豁免隐藏 API 限制（"L" 前缀覆盖全部 JNI 类描述符，即豁免所有类），
 * 供 WifiPasswordProvider 反射 IWifiManager 隐藏接口使用。
 */
class NetworkSwitchApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppTheme.applyNightMode(this)
        try {
            HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (t: Throwable) {
            // 豁免失败不阻断启动；取密时还有 Root 兜底通道
            Log.w("NetworkSwitchApp", "HiddenApiBypass unavailable", t)
        }
    }
}

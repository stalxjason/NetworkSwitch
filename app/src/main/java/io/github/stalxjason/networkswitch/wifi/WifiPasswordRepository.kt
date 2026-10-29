package io.github.stalxjason.networkswitch.wifi

import android.content.Context
import io.github.stalxjason.networkswitch.AppSettings
import io.github.stalxjason.networkswitch.R
import io.github.stalxjason.networkswitch.ShizukuHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import android.util.Log

/**
 * 取「已保存网络」的入口：先看授权，再走特权通道，最后按用户设置决定是否 Root 兜底。
 *
 * 只负责「拿到数据 / 说明为什么拿不到」，不做任何文案拼接——
 * 具体提示交给调用方按 [Outcome] 决定，这样同一套错误语义可以给列表页和以后的导出功能复用。
 */
object WifiPasswordRepository {

    private const val TAG = "WifiPasswordRepo"

    /** 取数结果：拿到数据、需要用户授权、或明确失败 */
    sealed interface Outcome {
        data class Ready(val networks: List<WifiEntry>) : Outcome
        data class NeedsAuthorization(val issue: AuthorizationIssue) : Outcome
        data class Failed(val reason: FailureReason) : Outcome
    }

    /** Shizuku 侧的前置条件没满足 */
    enum class AuthorizationIssue { NOT_INSTALLED, NOT_RUNNING, NOT_GRANTED }

    /** 有权限但取不到数据 */
    enum class FailureReason {
        /** 特权接口调用抛异常（ROM 改了隐藏接口等） */
        PRIVILEGED_CALL,

        /** 特权接口失败，且用户没开 Root 兜底 */
        ROOT_DISABLED,

        /** 开了 Root 兜底但仍未读到有效配置 */
        ROOT_FAILED
    }

    suspend fun load(context: Context): Outcome = withContext(Dispatchers.IO) {
        when (ShizukuHelper.getStatus(context)) {
            is ShizukuHelper.Status.Authorized -> loadWithPrivilege(context)
            is ShizukuHelper.Status.Running ->
                Outcome.NeedsAuthorization(AuthorizationIssue.NOT_GRANTED)
            is ShizukuHelper.Status.NotRunning ->
                Outcome.NeedsAuthorization(AuthorizationIssue.NOT_RUNNING)
            is ShizukuHelper.Status.NotInstalled ->
                Outcome.NeedsAuthorization(AuthorizationIssue.NOT_INSTALLED)
        }
    }

    private suspend fun loadWithPrivilege(context: Context): Outcome {
        // 不能用 runCatching：它会把 CancellationException 一起吞掉，
        // 导致「退出页面后取消」对后面的 Root 分支完全失效
        val configs = try {
            WifiBinderClient().privilegedConfigurations()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            // 详情只进日志：类名/异常消息对用户没有意义，还会暴露实现细节
            Log.w(TAG, "特权通道取数失败", t)
            null
        }

        if (configs != null) {
            return Outcome.Ready(present(context, configs.mapNotNull(WifiEntryMapper::fromConfig)))
        }

        if (!AppSettings.allowRootFallback(context)) {
            return Outcome.Failed(FailureReason.ROOT_DISABLED)
        }

        // 读文件是阻塞式的，进入前再确认一次协程还活着
        currentCoroutineContext().ensureActive()
        val fromRoot = RootConfigStoreSource.read()
        if (fromRoot == null) {
            return Outcome.Failed(FailureReason.ROOT_FAILED)
        }
        return Outcome.Ready(present(context, fromRoot.mapNotNull(WifiEntryMapper::fromRaw)))
    }

    /** 去重 + 排序 + 占位符替换（十六进制 SSID 系统只回显 *HEX*，没有可还原的内容） */
    private fun present(context: Context, entries: List<WifiEntry>): List<WifiEntry> =
        entries
            .distinctBy { it.ssid to it.security }
            .map { entry ->
                if (entry.ssid == HEX_SSID_PLACEHOLDER) {
                    entry.copy(ssid = context.getString(R.string.ssid_hex_placeholder))
                } else {
                    entry
                }
            }
            .sortedBy { it.ssid.lowercase() }

    private const val HEX_SSID_PLACEHOLDER = "*HEX*"
}

package io.github.stalxjason.networkswitch

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 网络模式切换核心工具类
 *
 * 设计要点（相对 MacroDroid 宏原始方案的改进）：
 * 1. 槽位动态解析：跟随默认数据卡（宏里写死 -s 0，双卡换数据卡即失效）；
 * 2. 方向读系统真值：`cmd phone get-allowed-network-types-for-users` 回读
 *    判断 NR 是否放行，不再依赖自建影子状态；
 * 3. 切换后校验：set 成功后回读确认，HyperOS 静默拒绝时如实报失败；
 * 4. 通道优先级：Shizuku → Root → 引导系统设置。
 */
object NetworkModeHelper {

    private const val TAG = "NetworkModeHelper"
    private const val SETTINGS_KEY = "preferred_network_mode"
    private const val ROOT_TIMEOUT_SECONDS = 10L
    private const val QUERY_TIMEOUT_SECONDS = 8L

    // Root 探测要 fork su，App 运行期间状态不会变，故缓存结果
    private const val ROOT_CACHE_TTL_MS = 60_000L

    @Volatile
    private var rootCache: Pair<Long, Boolean>? = null

    /** 兼容旧调用的命名：mode 为切换后实际生效（或目标）模式 */
    data class ToggleResult(
        val success: Boolean,
        val mode: NetworkMode,
        val message: String
    )

    // ─────────────────────────────────────────────────────────────────────────
    // 状态读取
    // ─────────────────────────────────────────────────────────────────────────

    /** 数据卡所在槽位；双卡切换数据卡后自动跟随，解析失败回落 slot 0 */
    @SuppressLint("MissingPermission")  // activeSubscriptionInfoList 需 READ_PHONE_STATE；已 catch
    fun dataSlotId(context: Context): Int = try {
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as? SubscriptionManager
            sm?.activeSubscriptionInfoList
                ?.firstOrNull { it.subscriptionId == subId }
                ?.simSlotIndex?.takeIf { it >= 0 } ?: 0
        } else 0
    } catch (_: Exception) {
        0
    }

    /** 快速同步读取（settings 键，subId 键优先）：供 Widget 等无法 suspend 的场景 */
    fun getCurrentMode(context: Context): NetworkMode {
        return try {
            val cr = context.contentResolver
            val subId = try {
                SubscriptionManager.getDefaultDataSubscriptionId()
            } catch (_: Exception) {
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }
            val raw = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                Settings.Global.getString(cr, SETTINGS_KEY + subId)
            } else null
            val value = (raw ?: Settings.Global.getString(cr, SETTINGS_KEY))?.toIntOrNull()
                ?: return NetworkMode.LTE
            modeFromSettingValue(value)
        } catch (_: Exception) {
            NetworkMode.LTE
        }
    }

    /**
     * settings 值 → 模式。21=旧 4G 模式；33=HyperOS 5G；
     * AOSP 的 NR 系取值（≥23）一律按 5G 处理，其余视为 4G。
     */
    private fun modeFromSettingValue(value: Int): NetworkMode = when {
        value == 21 -> NetworkMode.LTE
        value >= 23 -> NetworkMode.NR_5G
        else -> NetworkMode.LTE
    }

    /**
     * 真值查询：shell 回读 allowed 类型判断 NR 是否放行；
     * shell 不可用或解析失败时回落 settings 键。
     */
    suspend fun queryMode(context: Context): NetworkMode {
        val nrAllowed = queryNrAllowed(context, dataSlotId(context))
        return when (nrAllowed) {
            true -> NetworkMode.NR_5G
            false -> NetworkMode.LTE
            null -> getCurrentMode(context)
        }
    }

    /** 回读 NR 放行状态；命令失败或输出不可解析返回 null */
    private suspend fun queryNrAllowed(context: Context, slot: Int): Boolean? {
        val result = runShell(
            context, "cmd phone get-allowed-network-types-for-users -s $slot",
            QUERY_TIMEOUT_SECONDS
        ) ?: return null
        // 记录原始输出，便于适配各 ROM 的格式差异
        Log.i(
            TAG,
            "get-allowed exit=${result.success} stdout='${result.stdout.take(160)}' stderr='${result.stderr.take(80)}'"
        )
        // 命令失败时输出通常是 usage/error 文本，拿它判向会把失败误读成 LTE
        if (!result.success || result.stdout.isBlank()) return null
        return NetworkMode.nrAllowedFromShellOutput(result.stdout)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 切换
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun toggleNetworkMode(context: Context): ToggleResult = withContext(Dispatchers.IO) {
        val slot = dataSlotId(context)
        val currentNr = queryNrAllowed(context, slot) ?: (getCurrentMode(context) == NetworkMode.NR_5G)
        val target = if (currentNr) NetworkMode.LTE else NetworkMode.NR_5G
        val expectedNr = target == NetworkMode.NR_5G
        val setCmd = "cmd phone set-allowed-network-types-for-users -s $slot ${target.allowedTypesMask}"

        // 通道 1：Shizuku
        if (ShizukuHelper.isAvailable(context)) {
            val setResult = ShizukuHelper.exec(context, setCmd)
            if (verifyNrAllowed(context, slot, expectedNr, setResult)) {
                saveBookkeeping(context, target)
                return@withContext ToggleResult(true, target, successMessage(context, target))
            }
            Log.w(TAG, "Shizuku 通道校验未通过（命令成功=${setResult.success}）")
        }

        // 通道 2：Root
        if (hasRootAccess()) {
            val setResult = rootExec(context, setCmd)
            if (verifyNrAllowed(context, slot, expectedNr, setResult)) {
                saveBookkeeping(context, target)
                return@withContext ToggleResult(true, target, successMessage(context, target))
            }
            Log.w(TAG, "Root 通道校验未通过（命令成功=${setResult.success}）")
        }

        // 兜底：引导系统设置手动切换
        ToggleResult(
            false,
            if (currentNr) NetworkMode.NR_5G else NetworkMode.LTE,
            context.getString(R.string.toast_switch_failed_manual)
        )
    }

    /** 切换成功文案：Toast 用，也复用给两个小组件 */
    private fun successMessage(context: Context, mode: NetworkMode): String =
        context.getString(R.string.toast_switch_to, context.getString(mode.labelRes))

    /**
     * 切换后校验：
     * 1. 读回最多 3 次（间隔 1.5s）——modem 应用设置可能异步生效，读太快仍是旧值；
     * 2. 若 get 输出始终无法解析（部分 ROM 格式未知），但 set 命令退出码为 0，
     *    按成功处理——真机（HyperOS）验证 set 本身是真实生效的，宁可不校验也不误报失败。
     */
    private suspend fun verifyNrAllowed(
        context: Context,
        slot: Int,
        expectedNr: Boolean,
        setResult: ShizukuHelper.ShellResult
    ): Boolean {
        repeat(3) { attempt ->
            if (attempt > 0) delay(1500)
            val read = queryNrAllowed(context, slot)
            when {
                read == expectedNr -> return true
                read == null -> {
                    Log.w(TAG, "get 输出无法解析（第 ${attempt + 1} 次）")
                    if (attempt == 2 && setResult.success) {
                        Log.i(TAG, "输出格式未知，按 set 命令退出码判定为成功")
                        return true
                    }
                }
                // read != expected：等待下一次重试
            }
        }
        return false
    }

    /**
     * 切换成功后把 settings 展示键（subId 键 + 裸键）对齐到目标值。
     * 仅作为 Widget 快速展示的记账，写入失败不影响切换结果。
     */
    private suspend fun saveBookkeeping(context: Context, target: NetworkMode) {
        val value = target.telephonyType
        val subId = try {
            SubscriptionManager.getDefaultDataSubscriptionId()
        } catch (_: Exception) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        val commands = buildList {
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                add("settings put global $SETTINGS_KEY$subId $value")
            }
            add("settings put global $SETTINGS_KEY $value")
        }
        for (cmd in commands) {
            try {
                runShell(context, cmd, QUERY_TIMEOUT_SECONDS)
            } catch (_: Exception) {
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shell 通道
    // ─────────────────────────────────────────────────────────────────────────

    /** 统一通道：Shizuku 优先，Root 兜底；都不可用返回 null */
    private suspend fun runShell(
        context: Context,
        command: String,
        timeoutSeconds: Long
    ): ShizukuHelper.ShellResult? {
        if (ShizukuHelper.isAvailable(context)) {
            return ShizukuHelper.exec(context, command, timeoutSeconds)
        }
        if (hasRootAccess()) {
            return rootExec(context, command, timeoutSeconds)
        }
        return null
    }

    /** Root 可用性；结果缓存 [ROOT_CACHE_TTL_MS]，避免每次刷新都 fork su */
    suspend fun hasRootAccess(): Boolean = withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtime()
        rootCache?.takeIf { now - it.first < ROOT_CACHE_TTL_MS }?.second
            ?: detectRoot().also { rootCache = now to it }
    }

    /** 实际 fork `su -c id` 探测；超时或输出异常一律按无 Root 处理 */
    private fun detectRoot(): Boolean = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        if (!process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            false
        } else {
            process.inputStream.bufferedReader().readText().contains("uid=0")
        }
    } catch (_: Exception) {
        false
    }

    /** `su -c` 单命令执行；退出码即该命令本身，避免多命令拼接误报成功 */
    private suspend fun rootExec(
        context: Context,
        command: String,
        timeoutSeconds: Long = ROOT_TIMEOUT_SECONDS
    ): ShizukuHelper.ShellResult = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.w(TAG, "Root command timed out after ${timeoutSeconds}s: $command")
                return@withContext ShizukuHelper.ShellResult(
                    false, "", context.getString(R.string.err_timeout)
                )
            }
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            ShizukuHelper.ShellResult(process.exitValue() == 0, stdout.trim(), stderr.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Root exec failed: $command", e)
            ShizukuHelper.ShellResult(false, "", e.message ?: context.getString(R.string.err_root_failed))
        }
    }
}

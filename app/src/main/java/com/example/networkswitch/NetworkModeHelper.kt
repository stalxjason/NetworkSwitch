package com.example.networkswitch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

/**
 * 网络模式切换核心工具类
 *
 * 切换优先级（从高到低）：
 * 1. Shizuku — 通过 ADB shell 执行 cmd phone set-allowed-network-types-for-users
 * 2. Root — 通过 su 执行相同命令
 * 3. Fallback — 跳转系统设置页，用户手动切换
 */
object NetworkModeHelper {

    private const val TAG = "NetworkModeHelper"
    private const val SETTINGS_KEY = "preferred_network_mode"

    /**
     * 获取当前网络模式（从 Settings Global 读取）
     */
    fun getCurrentMode(context: Context): NetworkMode {
        return try {
            val modeStr = android.provider.Settings.Global.getString(
                context.contentResolver, SETTINGS_KEY
            )
            val mode = modeStr?.toIntOrNull() ?: return NetworkMode.LTE
            NetworkMode.fromTelephonyType(mode) ?: NetworkMode.LTE
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read settings", e)
            NetworkMode.LTE
        }
    }

    /**
     * 切换网络模式
     */
    suspend fun toggleNetworkMode(context: Context): ToggleResult = withContext(Dispatchers.IO) {
        val current = getCurrentMode(context)
        val target = when (current) {
            NetworkMode.LTE -> NetworkMode.NR_5G
            NetworkMode.NR_5G -> NetworkMode.LTE
        }

        // 方案1: Shizuku
        if (ShizukuHelper.isAvailable()) {
            val result = tryShizukuSwitch(target)
            if (result.success) {
                return@withContext ToggleResult(true, target, "已切换到 ${target.label}")
            }
            Log.w(TAG, "Shizuku switch failed: ${result.stderr}")
        }

        // 方案2: Root
        if (tryRootSwitch(target)) {
            return@withContext ToggleResult(true, target, "已切换到 ${target.label}")
        }

        // 方案3: 回退到系统设置
        ToggleResult(false, current, "需要手动切换")
    }

    /**
     * Shizuku 方案：通过 cmd phone 命令直接设置网络类型
     */
    private suspend fun tryShizukuSwitch(target: NetworkMode): ShizukuHelper.ShellResult {
        val cmd = "cmd phone set-allowed-network-types-for-users -s 0 ${target.allowedTypesMask}"
        val result = ShizukuHelper.exec(cmd)
        if (!result.success) {
            return result
        }
        // 同时更新 settings 值，保持一致性
        ShizukuHelper.exec("settings put global $SETTINGS_KEY ${target.telephonyType}")
        return result
    }

    /**
     * Root 方案
     */
    private fun tryRootSwitch(target: NetworkMode): Boolean {
        return try {
            val commands = listOf(
                "cmd phone set-allowed-network-types-for-users -s 0 ${target.allowedTypesMask}",
                "settings put global $SETTINGS_KEY ${target.telephonyType}"
            )
            executeAsRoot(commands)
        } catch (e: Exception) {
            Log.d(TAG, "Root switch failed", e)
            false
        }
    }

    private fun executeAsRoot(commands: List<String>): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            for (cmd in commands) {
                os.writeBytes("$cmd\n")
                os.flush()
            }
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            process.waitFor()
            val output = process.inputStream.bufferedReader().readText()
            output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    data class ToggleResult(
        val success: Boolean,
        val currentMode: NetworkMode,
        val message: String
    )
}

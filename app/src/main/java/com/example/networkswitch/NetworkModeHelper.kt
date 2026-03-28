package com.example.networkswitch

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

/**
 * 网络模式切换核心工具类
 *
 * 切换优先级（从高到低）：
 * 1. Shizuku — 通过 ADB shell 权限执行 settings put（推荐，无需 Root）
 * 2. Root — 通过 su 直接执行
 * 3. 反射 — 调用隐藏 API（需系统签名）
 * 4. Fallback — 跳转系统设置页，用户手动切换
 */
object NetworkModeHelper {

    private const val TAG = "NetworkModeHelper"
    private const val SETTINGS_KEY = "preferred_network_mode"

    /**
     * 获取当前网络模式
     */
    fun getCurrentMode(context: Context): NetworkMode {
        return fallbackFromSettings(context)
    }

    private fun fallbackFromSettings(context: Context): NetworkMode {
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

        // 方案1: Shizuku（推荐）
        if (ShizukuHelper.isAvailable()) {
            val result = tryShizukuSwitch(target)
            if (result.success) {
                return@withContext ToggleResult(true, target, "Shizuku 切换成功")
            }
            Log.w(TAG, "Shizuku switch failed: ${result.stderr}")
        }

        // 方案2: Root
        if (tryRootSwitch(target)) {
            return@withContext ToggleResult(true, target, "Root 切换成功")
        }

        // 方案3: 反射（通常无效）
        if (tryReflectionSwitch(context, target)) {
            return@withContext ToggleResult(true, target, "API 切换成功")
        }

        // 方案4: 回退到系统设置
        ToggleResult(false, current, "需要手动切换，请在系统设置中操作")
    }

    /**
     * Shizuku 方案：通过 adb shell 执行 settings put
     */
    private suspend fun tryShizukuSwitch(target: NetworkMode): ShizukuHelper.ShellResult {
        val commands = listOf(
            "settings put global $SETTINGS_KEY ${target.telephonyType}",
            "settings put global preferred_network_mode1 ${target.telephonyType}",
            // 重新注册网络
            "svc data disable",
            "sleep 2",
            "svc data enable"
        )
        // 逐条执行，确保顺序
        for (cmd in commands) {
            val result = ShizukuHelper.exec(cmd)
            if (!result.success && cmd.startsWith("settings")) {
                return result // settings 命令失败则中止
            }
        }
        return ShizukuHelper.ShellResult(true, "", "")
    }

    /**
     * Root 方案
     */
    private fun tryRootSwitch(target: NetworkMode): Boolean {
        return try {
            val commands = listOf(
                "settings put global $SETTINGS_KEY ${target.telephonyType}",
                "settings put global preferred_network_mode1 ${target.telephonyType}",
                "svc data disable",
                "sleep 1",
                "svc data enable"
            )
            executeAsRoot(commands)
        } catch (e: Exception) {
            Log.d(TAG, "Root switch failed", e)
            false
        }
    }

    /**
     * 反射方案（仅系统签名设备可用）
     */
    private fun tryReflectionSwitch(context: Context, target: NetworkMode): Boolean {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val method = tm.javaClass.getDeclaredMethod(
                "setPreferredNetworkType",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            val result = method.invoke(tm, 0, target.telephonyType)
            result as? Boolean ?: false
        } catch (e: Exception) {
            Log.d(TAG, "Reflection switch failed", e)
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

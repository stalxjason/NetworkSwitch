package com.example.networkswitch

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku 授权管理
 *
 * Shizuku 通过 ADB shell 权限运行，允许普通应用执行需要特权的 shell 命令。
 * 无需 Root，但需要用户安装 Shizuku 并保持运行。
 *
 * 使用方式：
 * 1. 安装 Shizuku (https://shizuku.rikka.app)
 * 2. 通过 ADB 或无线调试启动 Shizuku
 * 3. 在本应用中授权 Shizuku 权限
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

    /**
     * Shizuku 运行状态
     */
    sealed class Status {
        data object NotInstalled : Status()
        data object NotRunning : Status()
        data object Running : Status()
        data object Authorized : Status()
    }

    /**
     * 获取 Shizuku 当前状态
     */
    fun getStatus(): Status {
        return try {
            if (!pingBinder()) {
                Status.NotRunning // Binder 不通 → 未运行（或未安装）
            } else {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    Status.Authorized
                } else {
                    Status.Running
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to check Shizuku status", e)
            Status.NotRunning
        }
    }

    /**
     * Shizuku 是否可用（运行 + 已授权）
     */
    fun isAvailable(): Boolean = getStatus() is Status.Authorized

    /**
     * 尝试 ping Shizuku binder
     */
    private fun pingBinder(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 请求 Shizuku 权限
     * 调用方需注册 OnRequestPermissionResultListener
     */
    fun requestPermission() {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Log.d(TAG, "Should show rationale for Shizuku permission")
            }
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    /**
     * 是否是我们发起的权限请求
     */
    fun isOurPermissionRequest(requestCode: Int): Boolean =
        requestCode == SHIZUKU_PERMISSION_REQUEST_CODE

    /**
     * 通过 Shizuku 执行 shell 命令
     */
    suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            if (!isAvailable()) {
                return@withContext ShellResult(false, "", "Shizuku 未运行或未授权")
            }

            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            process.waitFor()

            ShellResult(process.exitValue() == 0, stdout.trim(), stderr.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to exec via Shizuku: $command", e)
            ShellResult(false, "", e.message ?: "Unknown error")
        }
    }

    data class ShellResult(
        val success: Boolean,
        val stdout: String,
        val stderr: String
    )
}

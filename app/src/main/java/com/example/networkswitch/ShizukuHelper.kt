package com.example.networkswitch

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Shizuku 授权管理
 *
 * 通过反射调用 Shizuku.newProcess 执行 shell 命令
 * （newProcess 在 Shizuku v13+ 被 @hide，需要反射访问）
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    private const val EXEC_TIMEOUT_SECONDS = 15L

    // 缓存反射方法，避免每次调用都重新查找
    private var cachedNewProcessMethod: Method? = null
    private var reflectionAvailable: Boolean? = null

    sealed class Status {
        data object NotInstalled : Status()
        data object NotRunning : Status()
        data object Running : Status()
        data object Authorized : Status()
    }

    fun getStatus(): Status {
        return try {
            if (!pingBinder()) {
                Status.NotRunning
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

    fun isAvailable(): Boolean = getStatus() is Status.Authorized

    private fun pingBinder(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

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

    fun isOurPermissionRequest(requestCode: Int): Boolean =
        requestCode == SHIZUKU_PERMISSION_REQUEST_CODE

    /**
     * 通过反射调用 Shizuku.newProcess 执行 shell 命令
     */
    suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            if (!isAvailable()) {
                return@withContext ShellResult(false, "", "Shizuku 未运行或未授权")
            }

            // 获取反射方法（带缓存）
            val method = getNewProcessMethod()
                ?: return@withContext ShellResult(false, "", "Shizuku API 不可用（版本不兼容）")

            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process

            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val finished = process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.w(TAG, "Shizuku exec timed out after ${EXEC_TIMEOUT_SECONDS}s: $command")
                return@withContext ShellResult(false, stdout.trim(), "命令执行超时")
            }

            ShellResult(process.exitValue() == 0, stdout.trim(), stderr.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to exec via Shizuku: $command", e)
            ShellResult(false, "", e.message ?: "Unknown error")
        }
    }

    /**
     * 获取 Shizuku.newProcess 反射方法（带缓存和降级）
     */
    private fun getNewProcessMethod(): Method? {
        reflectionAvailable?.let { if (!it) return null }

        cachedNewProcessMethod?.let { return it }

        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            cachedNewProcessMethod = method
            reflectionAvailable = true
            method
        } catch (e: NoSuchMethodException) {
            Log.e(TAG, "Shizuku.newProcess method not found, API may have changed", e)
            reflectionAvailable = false
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Shizuku.newProcess method", e)
            reflectionAvailable = false
            null
        }
    }

    data class ShellResult(
        val success: Boolean,
        val stdout: String,
        val stderr: String
    )
}

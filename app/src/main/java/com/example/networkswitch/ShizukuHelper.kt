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
 * 通过反射调用 Shizuku.newProcess 执行 shell 命令
 * （newProcess 在 Shizuku v13+ 被 @hide，需要反射访问）
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

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

            // 反射调用 Shizuku.newProcess(String[], String[], String)
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process

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

package io.github.stalxjason.networkswitch

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
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

    // 读取进程输出的常驻线程池（守护线程，避免挂起命令卡死 IO 调度器）
    private val outputPool = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r).apply { isDaemon = true }
    }

    /**
     * 通过反射调用 Shizuku.newProcess 执行 shell 命令
     *
     * 注意：Shizuku 返回的远程 Process 与标准实现不一致——
     * waitFor(timeout) 可能不生效、未退出时 exitValue() 会抛
     * "process hasn't exited"。因此以「输出流 EOF」为完成信号，
     * 超时作用在 future.get 上；流结束后再取退出码。
     */
    suspend fun exec(
        command: String,
        timeoutSeconds: Long = EXEC_TIMEOUT_SECONDS
    ): ShellResult = withContext(Dispatchers.IO) {
        try {
            if (!isAvailable()) {
                return@withContext ShellResult(false, "", "Shizuku 未运行或未授权")
            }

            // 获取反射方法（带缓存）
            val method = getNewProcessMethod()
                ?: return@withContext ShellResult(false, "", "Shizuku API 不可用（版本不兼容）")

            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process

            val stdoutFuture = outputPool.submit<String> {
                process.inputStream.bufferedReader().readText()
            }
            val stderrFuture = outputPool.submit<String> {
                process.errorStream.bufferedReader().readText()
            }

            val stdout: String
            val stderr: String
            try {
                stdout = stdoutFuture.get(timeoutSeconds, TimeUnit.SECONDS)
                stderr = stderrFuture.get(3, TimeUnit.SECONDS)
            } catch (e: Exception) {
                process.destroyForcibly()
                Log.w(TAG, "Shizuku exec timed out after ${timeoutSeconds}s: $command")
                return@withContext ShellResult(false, "", "命令执行超时")
            }

            // 流已 EOF → 远程命令已执行完，此时取退出码
            val exitCode: Int? = try {
                process.exitValue()
            } catch (_: Throwable) {
                try {
                    process.waitFor(3, TimeUnit.SECONDS)
                    process.exitValue()
                } catch (_: Throwable) {
                    null
                }
            }

            // 拿不到退出码时按成功处理：命令确已执行，由上层校验兜底
            ShellResult(exitCode == null || exitCode == 0, stdout.trim(), stderr.trim())
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

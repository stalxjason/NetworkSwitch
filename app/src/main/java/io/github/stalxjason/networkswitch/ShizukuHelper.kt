package io.github.stalxjason.networkswitch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

    // 只认当前包名；旧包名 rikka.shizuku 在 targetSdk 30+ 下不可见，不再兜底
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    // 缓存反射方法，避免每次调用都重新查找
    private var cachedNewProcessMethod: Method? = null
    private var reflectionAvailable: Boolean? = null

    sealed class Status {
        data object NotInstalled : Status()
        data object NotRunning : Status()
        data object Running : Status()
        data object Authorized : Status()
    }

    /**
     * Shizuku 状态。
     * 判定顺序：未安装 → binder 不通（未运行）→ 已授权 / 运行中未授权。
     * 需要 Context 才能查 PackageManager（targetSdk 30+ 依赖 Manifest 里的 <queries> 声明）。
     */
    fun getStatus(context: Context): Status {
        return try {
            when {
                !isInstalled(context) -> Status.NotInstalled
                !pingBinder() -> Status.NotRunning
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                    Status.Authorized
                else -> Status.Running
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to check Shizuku status", e)
            Status.NotRunning
        }
    }

    fun isAvailable(context: Context): Boolean = getStatus(context) is Status.Authorized

    /** Shizuku 是否已安装；未安装时启动器拿不到启动 Intent */
    private fun isInstalled(context: Context): Boolean =
        runCatching {
            context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) != null
        }.getOrDefault(false)

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

    /** 拉起 Shizuku 应用；未安装或无法启动返回 false，由调用方提示 */
    fun openShizukuApp(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                ?: return false
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }

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
     * stdout 与 stderr 共享同一截止时间，避免 stderr 独占额外等待。
     */
    suspend fun exec(
        context: Context,
        command: String,
        timeoutSeconds: Long = EXEC_TIMEOUT_SECONDS
    ): ShellResult = withContext(Dispatchers.IO) {
        try {
            if (!isAvailable(context)) {
                return@withContext ShellResult(false, "", context.getString(R.string.err_shizuku_unavailable))
            }

            // 获取反射方法（带缓存）
            val method = getNewProcessMethod()
                ?: return@withContext ShellResult(false, "", context.getString(R.string.err_shizuku_api))

            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process

            val stdoutFuture = outputPool.submit<String> {
                process.inputStream.bufferedReader().readText()
            }
            val stderrFuture = outputPool.submit<String> {
                process.errorStream.bufferedReader().readText()
            }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            val stdout: String
            val stderr: String
            try {
                stdout = stdoutFuture.get(remainingNanos(deadline), TimeUnit.NANOSECONDS)
                stderr = stderrFuture.get(remainingNanos(deadline), TimeUnit.NANOSECONDS)
            } catch (e: Exception) {
                process.destroyForcibly()
                Log.w(TAG, "Shizuku exec timed out after ${timeoutSeconds}s: $command")
                return@withContext ShellResult(false, "", context.getString(R.string.err_timeout))
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
            ShellResult(
                false, "",
                e.message ?: context.getString(R.string.err_unknown)
            )
        }
    }

    /** 距统一截止时间的剩余纳秒；已过期直接抛超时，由调用方按失败处理 */
    private fun remainingNanos(deadline: Long): Long {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw TimeoutException("past deadline")
        return remaining
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

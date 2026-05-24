package com.network.toolbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

object NetworkModeHelper {

    private const val TAG = "NetworkToolbox"
    private const val SETTINGS_KEY = "preferred_network_mode"
    private const val EXEC_TIMEOUT_SECONDS = 15L

    private var cachedMethod: Method? = null
    private var reflectionAvailable: Boolean? = null

    data class ShellResult(val success: Boolean, val stdout: String, val stderr: String)

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

    suspend fun toggleNetworkMode(context: Context): ToggleResult = withContext(Dispatchers.IO) {
        val current = getCurrentMode(context)
        val target = when (current) {
            NetworkMode.LTE -> NetworkMode.NR_5G
            NetworkMode.NR_5G -> NetworkMode.LTE
        }

        if (ShizukuManager.isAvailable()) {
            val result = execViaShizuku(target)
            if (result.success) return@withContext ToggleResult(true, target, "已切换到 ${target.label}")
        }

        if (hasRootAccess()) {
            val result = execAsRoot(listOf(
                "cmd phone set-allowed-network-types-for-users -s 0 ${target.allowedTypesMask}",
                "settings put global $SETTINGS_KEY ${target.telephonyType}"
            ))
            if (result) return@withContext ToggleResult(true, target, "已切换到 ${target.label}")
        }

        ToggleResult(false, current, "需要手动切换")
    }

    private suspend fun execViaShizuku(target: NetworkMode): ShellResult {
        val cmd = "cmd phone set-allowed-network-types-for-users -s 0 ${target.allowedTypesMask}"
        val result = execShizuku(cmd)
        if (!result.success) return result
        execShizuku("settings put global $SETTINGS_KEY ${target.telephonyType}")
        return result
    }

    private suspend fun execShizuku(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            if (!ShizukuManager.isAvailable()) {
                return@withContext ShellResult(false, "", "Shizuku 未授权")
            }
            val method = getShizukuMethod() ?: return@withContext ShellResult(false, "", "Shizuku API 不可用")
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val finished = process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) { process.destroyForcibly(); return@withContext ShellResult(false, "", "超时") }
            ShellResult(process.exitValue() == 0, stdout.trim(), stderr.trim())
        } catch (e: Exception) {
            ShellResult(false, "", e.message ?: "Unknown error")
        }
    }

    private fun getShizukuMethod(): Method? {
        reflectionAvailable?.let { if (!it) return null }
        cachedMethod?.let { return it }
        return try {
            val method = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            @Suppress("DEPRECATION")
            method.isAccessible = true
            cachedMethod = method
            reflectionAvailable = true
            method
        } catch (e: Exception) { Log.w(TAG, "Shizuku newProcess reflection failed", e); reflectionAvailable = false; null }
    }

    private suspend fun execAsRoot(commands: List<String>): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = java.io.DataOutputStream(process.outputStream)
            for (cmd in commands) { os.writeBytes("$cmd\n"); os.flush() }
            os.writeBytes("exit\n"); os.flush()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) { process.destroyForcibly(); return@withContext false }
            process.exitValue() == 0
        } catch (e: Exception) { Log.w(TAG, "shell exec failed", e); false }
    }

    suspend fun hasRootAccess(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) { process.destroyForcibly(); return@withContext false }
            process.inputStream.bufferedReader().readText().contains("uid=0")
        } catch (e: Exception) { Log.w(TAG, "shell exec failed", e); false }
    }

    data class ToggleResult(val success: Boolean, val currentMode: NetworkMode, val message: String)
}

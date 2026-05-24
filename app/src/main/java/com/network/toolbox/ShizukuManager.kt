package com.network.toolbox

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

object ShizukuManager {

    private const val TAG = "NetworkToolbox"
    private const val REQUEST_CODE = 1001

    private val _status = MutableStateFlow(ShizukuStatus.DETECTING)
    val status: StateFlow<ShizukuStatus> = _status

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    enum class ShizukuStatus {
        DETECTING, NOT_INSTALLED, NOT_RUNNING, RUNNING, AUTHORIZED
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        updateStatus()
        // 自动请求权限
        if (_status.value == ShizukuStatus.RUNNING) {
            requestPermission()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        _status.value = ShizukuStatus.NOT_RUNNING
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permission result: granted=$granted")
            updateStatus()
        }

    fun init() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Shizuku", e)
            _error.value = "Shizuku 初始化失败: ${e.message}"
        }
    }

    fun destroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy Shizuku", e)
        }
    }

    fun updateStatus() {
        _status.value = try {
            if (!Shizuku.pingBinder()) {
                ShizukuStatus.NOT_RUNNING
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuStatus.AUTHORIZED
            } else {
                ShizukuStatus.RUNNING
            }
        } catch (e: Throwable) {
            ShizukuStatus.NOT_RUNNING
        }
    }

    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.isPreV11()) {
                _error.value = "Shizuku 版本过低，请更新"
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                _status.value = ShizukuStatus.AUTHORIZED
            } else {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request permission", e)
            _error.value = "请求权限失败: ${e.message}"
        }
    }
}

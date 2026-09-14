package io.github.stalxjason.networkswitch.wifi

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.stalxjason.networkswitch.AppTheme
import io.github.stalxjason.networkswitch.R
import io.github.stalxjason.networkswitch.ShizukuHelper
import io.github.stalxjason.networkswitch.databinding.ActivityWifiListBinding
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * 已保存 WiFi 密码列表页
 *
 * 三态展示：加载中 / 列表 / 状态页（未授权、失败、空列表）。
 * 进入页面即自动处理 Shizuku：运行中未授权 → 弹授权请求；
 * 未运行 → 自动跳转 Shizuku；授权成功后自动加载并展示列表。
 */
class WifiListActivity : AppCompatActivity(), Shizuku.OnRequestPermissionResultListener {

    private lateinit var binding: ActivityWifiListBinding
    private val adapter = WifiListAdapter()
    private var loadedOnce = false
    private var autoJumpedToShizuku = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.applyAccent(this)
        super.onCreate(savedInstanceState)
        binding = ActivityWifiListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        binding.btnAction.setOnClickListener {
            when (ShizukuHelper.getStatus(this)) {
                is ShizukuHelper.Status.Running -> ShizukuHelper.requestPermission()
                else -> launchShizukuApp()
            }
        }

        Shizuku.addRequestPermissionResultListener(this)

        // 打开即自动处理授权链路：运行中 → 弹授权；未运行 → 跳 Shizuku
        when (ShizukuHelper.getStatus(this)) {
            is ShizukuHelper.Status.Running -> ShizukuHelper.requestPermission()
            is ShizukuHelper.Status.NotRunning -> {
                if (!autoJumpedToShizuku) {
                    autoJumpedToShizuku = true
                    launchShizukuApp()
                }
            }
            else -> {}
        }

        loadNetworks()
    }

    override fun onResume() {
        super.onResume()
        // 从 Shizuku 授权页返回时自动重载
        if (!loadedOnce && ShizukuHelper.isAvailable(this)) {
            loadNetworks()
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(this)
        super.onDestroy()
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (!ShizukuHelper.isOurPermissionRequest(requestCode)) return
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            loadNetworks()
        } else {
            showStatus(R.string.wifi_status_denied, R.string.wifi_action_regrant)
        }
    }

    private fun loadNetworks() {
        binding.progress.visibility = View.VISIBLE
        binding.recycler.visibility = View.GONE
        binding.statusContainer.visibility = View.GONE

        lifecycleScope.launch {
            when (val result = WifiPasswordProvider.getSavedNetworks(this@WifiListActivity)) {
                is WifiPasswordProvider.Result.Ok -> {
                    loadedOnce = true
                    if (result.list.isEmpty()) {
                        showStatus(R.string.wifi_status_empty, R.string.wifi_action_retry)
                    } else {
                        adapter.submit(result.list)
                        binding.recycler.visibility = View.VISIBLE
                    }
                }
                is WifiPasswordProvider.Result.ShizukuUnavailable ->
                    showStatus(result.message, getString(R.string.wifi_action_grant))
                is WifiPasswordProvider.Result.Error ->
                    showStatus(
                        getString(R.string.wifi_status_error, result.message),
                        getString(R.string.wifi_action_retry)
                    )
            }
            binding.progress.visibility = View.GONE
        }
    }

    private fun showStatus(@StringRes messageRes: Int, @StringRes actionRes: Int) =
        showStatus(getString(messageRes), getString(actionRes))

    private fun showStatus(message: String, actionText: String) {
        binding.statusContainer.visibility = View.VISIBLE
        binding.tvStatus.text = message
        binding.btnAction.text = actionText
    }

    /** 拉起 Shizuku 应用；未安装或无法启动时提示安装 */
    private fun launchShizukuApp() {
        if (!ShizukuHelper.openShizukuApp(this)) {
            Toast.makeText(this, R.string.toast_shizuku_install_first, Toast.LENGTH_LONG).show()
        }
    }
}

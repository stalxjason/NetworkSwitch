package io.github.stalxjason.networkswitch.wifi

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.stalxjason.networkswitch.AppTheme
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

        binding.btnAction.setOnClickListener {
            when (ShizukuHelper.getStatus()) {
                is ShizukuHelper.Status.Running -> ShizukuHelper.requestPermission()
                else -> openShizukuApp()
            }
        }

        Shizuku.addRequestPermissionResultListener(this)

        // 打开即自动处理授权链路：运行中 → 弹授权；未运行 → 跳 Shizuku
        when (ShizukuHelper.getStatus()) {
            is ShizukuHelper.Status.Running -> ShizukuHelper.requestPermission()
            is ShizukuHelper.Status.NotRunning -> {
                if (!autoJumpedToShizuku) {
                    autoJumpedToShizuku = true
                    openShizukuApp()
                }
            }
            else -> {}
        }

        loadNetworks()
    }

    override fun onResume() {
        super.onResume()
        // 从 Shizuku 授权页返回时自动重载
        if (!loadedOnce && ShizukuHelper.isAvailable()) {
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
            showStatus("Shizuku 授权被拒绝，无法读取密码", "重新授权")
        }
    }

    private fun loadNetworks() {
        binding.progress.visibility = View.VISIBLE
        binding.recycler.visibility = View.GONE
        binding.statusContainer.visibility = View.GONE

        lifecycleScope.launch {
            when (val result = WifiPasswordProvider.getSavedNetworks()) {
                is WifiPasswordProvider.Result.Ok -> {
                    loadedOnce = true
                    if (result.list.isEmpty()) {
                        showStatus("本机没有已保存的 WiFi 网络", "重试")
                    } else {
                        adapter.submit(result.list)
                        binding.recycler.visibility = View.VISIBLE
                    }
                }
                is WifiPasswordProvider.Result.ShizukuUnavailable ->
                    showStatus(result.message, "去授权")
                is WifiPasswordProvider.Result.Error ->
                    showStatus("读取失败：${result.message}", "重试")
            }
            binding.progress.visibility = View.GONE
        }
    }

    private fun showStatus(message: String, actionText: String) {
        binding.statusContainer.visibility = View.VISIBLE
        binding.tvStatus.text = message
        binding.btnAction.text = actionText
    }

    private fun openShizukuApp() {
        val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "未检测到 Shizuku，请先安装", Toast.LENGTH_LONG).show()
        }
    }
}

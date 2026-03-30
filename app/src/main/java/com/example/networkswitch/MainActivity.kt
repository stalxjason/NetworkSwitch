package com.example.networkswitch

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.networkswitch.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (ShizukuHelper.isOurPermissionRequest(requestCode)) {
                val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                refreshStatus()
                Toast.makeText(
                    this,
                    if (granted) "Shizuku 授权成功 ✅" else "Shizuku 授权被拒绝",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        setupUI()
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun setupUI() {
        binding.btnToggle.setOnClickListener { performToggle() }
        binding.btnShizukuAuth.setOnClickListener {
            when (val status = ShizukuHelper.getStatus()) {
                is ShizukuHelper.Status.Authorized ->
                    Toast.makeText(this, "Shizuku 已授权 ✅", Toast.LENGTH_SHORT).show()
                is ShizukuHelper.Status.Running -> ShizukuHelper.requestPermission()
                is ShizukuHelper.Status.NotRunning -> {
                    Toast.makeText(this, "请先启动 Shizuku 应用", Toast.LENGTH_LONG).show()
                    openShizukuApp()
                }
                else -> Toast.makeText(this, "请先安装 Shizuku", Toast.LENGTH_LONG).show()
            }
            refreshStatus()
        }
        binding.btnOpenSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
        }
        binding.btnRefresh.setOnClickListener { refreshStatus() }
    }

    private fun openShizukuApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: packageManager.getLaunchIntentForPackage("rikka.shizuku")
            if (intent != null) startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun refreshStatus() {
        val currentMode = NetworkModeHelper.getCurrentMode(this)
        binding.tvCurrentMode.text = currentMode.label
        binding.tvModeDesc.text = when (currentMode) {
            NetworkMode.LTE -> "当前使用 4G LTE 网络"
            NetworkMode.NR_5G -> "当前使用 5G NR 网络"
        }

        val shizukuStatus = ShizukuHelper.getStatus()
        binding.tvShizukuStatus.text = when (shizukuStatus) {
            is ShizukuHelper.Status.Authorized -> "✅ Shizuku 已授权 — 可一键切换"
            is ShizukuHelper.Status.Running -> "⚠️ Shizuku 运行中 — 点击授权"
            is ShizukuHelper.Status.NotRunning -> "⚠️ Shizuku 未运行 — 请启动 Shizuku"
            is ShizukuHelper.Status.NotInstalled -> "❌ 未安装 Shizuku"
        }

        val hasRoot = NetworkModeHelper.hasRootAccess()
        binding.tvRootStatus.text = if (hasRoot) "✅ Root 可用" else "Root 不可用"

        val canToggle = ShizukuHelper.isAvailable() || hasRoot
        binding.btnToggle.isEnabled = canToggle
        binding.btnToggle.text = if (canToggle) "切换 4G/5G" else "需要 Shizuku 或 Root"

        binding.btnShizukuAuth.text = when (shizukuStatus) {
            is ShizukuHelper.Status.Authorized -> "✅ Shizuku 已就绪"
            else -> "授权 Shizuku"
        }

        // 获取内网 IP
        val ip = IpHelper.getLocalIp()
        binding.tvIpv4.text = "IPv4: ${ip.ipv4 ?: "未获取到"}"
        binding.tvIpv6.text = "IPv6: ${ip.ipv6 ?: "未获取到"}"
    }

    private fun performToggle() {
        binding.btnToggle.isEnabled = false
        binding.btnToggle.text = "切换中..."

        lifecycleScope.launch {
            val result = NetworkModeHelper.toggleNetworkMode(this@MainActivity)
            binding.btnToggle.isEnabled = true

            if (result.success) {
                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                }
            }

            refreshStatus()
            NetworkWidgetProvider.updateWidget(this@MainActivity)
        }
    }
}

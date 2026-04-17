package io.github.stalxjason.networkswitch

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.stalxjason.networkswitch.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (ShizukuHelper.isOurPermissionRequest(requestCode)) {
                val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                lifecycleScope.launch { refreshStatus() }
                Toast.makeText(
                    this,
                    if (granted) "Shizuku 授权成功" else "Shizuku 授权被拒绝",
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
        lifecycleScope.launch { refreshStatus() }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { refreshStatus() }
    }

    private fun setupUI() {
        binding.btnToggle.setOnClickListener { performToggle() }
        binding.btnShizukuAuth.setOnClickListener {
            when (val status = ShizukuHelper.getStatus()) {
                is ShizukuHelper.Status.Authorized ->
                    Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show()
                is ShizukuHelper.Status.Running -> ShizukuHelper.requestPermission()
                is ShizukuHelper.Status.NotRunning -> {
                    Toast.makeText(this, "请先启动 Shizuku 应用", Toast.LENGTH_LONG).show()
                    openShizukuApp()
                }
                else -> Toast.makeText(this, "请先安装 Shizuku", Toast.LENGTH_LONG).show()
            }
            lifecycleScope.launch { refreshStatus() }
        }
        binding.btnOpenSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
        }
        binding.btnRefresh.setOnClickListener { lifecycleScope.launch { refreshStatus() } }
    }

    private fun openShizukuApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: packageManager.getLaunchIntentForPackage("rikka.shizuku")
            if (intent != null) startActivity(intent)
        } catch (_: Exception) {}
    }

    private suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        // 网络模式
        val currentMode = NetworkModeHelper.getCurrentMode(this@MainActivity)
        withContext(Dispatchers.Main) {
            binding.tvCurrentMode.text = currentMode.label
            binding.tvModeDesc.text = when (currentMode) {
                NetworkMode.LTE -> "当前使用 4G LTE 网络"
                NetworkMode.NR_5G -> "当前使用 5G NR 网络"
            }
        }

        // 信号与运营商
        val signalInfo = NetworkInfoHelper.getSignalInfo(this@MainActivity)
        withContext(Dispatchers.Main) {
            updateSignalBars(signalInfo.signalLevel)
            binding.tvCarrier.text = buildCarrierText(signalInfo)
            binding.tvSignalDetail.text = buildSignalDetailText(signalInfo)
        }

        // IP 列表
        val ipList = IpHelper.getAllInterfaceIps()
        withContext(Dispatchers.Main) {
            updateIpList(ipList)
        }

        // Shizuku / Root 状态
        val shizukuStatus = ShizukuHelper.getStatus()
        withContext(Dispatchers.Main) {
            binding.tvShizukuStatus.text = when (shizukuStatus) {
                is ShizukuHelper.Status.Authorized -> "Shizuku 已授权 — 可一键切换"
                is ShizukuHelper.Status.Running -> "Shizuku 运行中 — 点击授权"
                is ShizukuHelper.Status.NotRunning -> "Shizuku 未运行 — 请启动 Shizuku"
                is ShizukuHelper.Status.NotInstalled -> "未安装 Shizuku"
            }
        }

        val hasRoot = NetworkModeHelper.hasRootAccess()
        withContext(Dispatchers.Main) {
            binding.tvRootStatus.text = if (hasRoot) "Root 可用" else "Root 不可用"

            val canToggle = ShizukuHelper.isAvailable() || hasRoot
            binding.btnToggle.isEnabled = canToggle
            binding.btnToggle.text = if (canToggle) "切换 4G/5G" else "需要 Shizuku 或 Root"

            binding.btnShizukuAuth.text = when (shizukuStatus) {
                is ShizukuHelper.Status.Authorized -> "Shizuku 已就绪"
                else -> "授权 Shizuku"
            }
        }
    }

    /**
     * 更新信号格数显示
     */
    private fun updateSignalBars(level: Int) {
        val activeColor = ContextCompat.getColor(this, R.color.primary)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_hint)
        val barIds = listOf(
            binding.signalBar1, binding.signalBar2,
            binding.signalBar3, binding.signalBar4
        )
        barIds.forEachIndexed { index, bar ->
            bar.setBackgroundColor(if (index < level) activeColor else inactiveColor)
        }
    }

    private fun buildCarrierText(info: NetworkInfoHelper.SignalInfo): String {
        val parts = mutableListOf<String>()
        info.carrierName?.let { parts.add(it) }
        info.networkTypeName?.let { parts.add(it) }
        return parts.ifEmpty { listOf("未知运营商") }.joinToString(" · ")
    }

    private fun buildSignalDetailText(info: NetworkInfoHelper.SignalInfo): String {
        val parts = mutableListOf<String>()
        info.signalStrengthDbm?.let { parts.add("${it} dBm") }
        if (info.signalLevel > 0) {
            parts.add("${info.signalLevel}/4 格")
        }
        return parts.ifEmpty { "无信号" }.joinToString("  |  ")
    }

    /**
     * 动态生成 IP 列表
     */
    private fun updateIpList(ipList: List<IpHelper.InterfaceIp>) {
        val container = binding.ipListContainer
        container.removeAllViews()

        if (ipList.isEmpty()) {
            container.addView(createIpTextView("未检测到网络接口"))
            return
        }

        for ((index, iface) in ipList.withIndex()) {
            // 接口名标签
            val label = createIpTextView(iface.ifaceName)
            label.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            label.setTypeface(null, android.graphics.Typeface.BOLD)
            container.addView(label)

            // IPv4
            iface.ipv4?.let {
                val tv = createIpTextView("  IPv4  $it")
                tv.typeface = android.graphics.Typeface.MONOSPACE
                container.addView(tv)
            }

            // IPv6
            iface.ipv6?.let {
                val tv = createIpTextView("  IPv6  $it")
                tv.typeface = android.graphics.Typeface.MONOSPACE
                container.addView(tv)
            }

            // 分隔线（非最后一个）
            if (index < ipList.size - 1) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    ).apply { setMargins(0, 12, 0, 12) }
                    setBackgroundColor(0x22FFFFFF.toInt())
                }
                container.addView(divider)
            }
        }
    }

    private fun createIpTextView(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 4
                bottomMargin = 4
            }
        }
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

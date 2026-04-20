package io.github.stalxjason.networkswitch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.stalxjason.networkswitch.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // 运行时请求 READ_PHONE_STATE 权限
    private val requestPhonePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 无论授权与否都刷新，拒绝时降级显示
        lifecycleScope.launch { refreshStatus() }
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (ShizukuHelper.isOurPermissionRequest(requestCode)) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
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

        // 请求电话权限后刷新；已有权限直接刷新
        val permsNeeded = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_BASIC_PHONE_STATE
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permsNeeded.isNotEmpty()) {
            requestPhonePermission.launch(permsNeeded.toTypedArray())
        } else {
            lifecycleScope.launch { refreshStatus() }
        }
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

        // 信号与运营商（双卡）
        val signalInfo = NetworkInfoHelper.getSignalInfo(this@MainActivity)
        withContext(Dispatchers.Main) {
            updateSignalBars(signalInfo.signalLevel)
            updateCarrierView(signalInfo)
            binding.tvSignalDetail.text = buildSignalDetailText(signalInfo)
        }

        // IP 列表
        val ipList = IpHelper.getAllInterfaceIps()
        withContext(Dispatchers.Main) {
            updateIpList(ipList, signalInfo)
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

    private fun updateSignalBars(level: Int) {
        val activeColor = ContextCompat.getColor(this, R.color.primary)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_hint)
        val bars = listOf(binding.signalBar1, binding.signalBar2, binding.signalBar3, binding.signalBar4)
        bars.forEachIndexed { index, bar ->
            bar.setBackgroundColor(if (index < level) activeColor else inactiveColor)
        }
    }

    /**
     * 运营商区域动态渲染（每张 SIM 一行）
     * SIM1  中国电信  LTE  ★（数据卡）
     * SIM2  中国移动  LTE
     */
    private fun updateCarrierView(info: NetworkInfoHelper.SignalInfo) {
        val container = binding.llCarrierContainer
        container.removeAllViews()
        if (info.sims.isEmpty()) {
            val tv = TextView(this).apply {
                text = "未知运营商"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            }
            container.addView(tv)
            return
        }
        info.sims.sortedBy { it.slotIndex }.forEach { sim ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4 }
            }

            // SIM 标签（如 SIM1）
            val tvSlot = TextView(this).apply {
                text = "SIM${sim.slotIndex + 1}"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                val bg = ContextCompat.getColor(this@MainActivity,
                    if (sim.isDataSim) R.color.primary else R.color.text_hint)
                setBackgroundColor(bg)
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(10, 3, 10, 3)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 10 }
            }
            row.addView(tvSlot)

            // 运营商名称
            val tvCarrier = TextView(this).apply {
                text = sim.carrierName ?: "未知"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8 }
            }
            row.addView(tvCarrier)

            // 网络类型（如 LTE、5G NR）
            sim.networkTypeName?.let { netType ->
                val tvNet = TextView(this).apply {
                    text = netType
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = 6 }
                }
                row.addView(tvNet)
            }

            // 数据卡标记
            if (sim.isDataSim) {
                val tvMark = TextView(this).apply {
                    text = "★"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
                }
                row.addView(tvMark)
            }

            container.addView(row)
        }
    }

    private fun buildSignalDetailText(info: NetworkInfoHelper.SignalInfo): String {
        val parts = mutableListOf<String>()
        info.signalStrengthDbm?.let { parts.add("${it} dBm") }
        if (info.signalLevel > 0) parts.add("${info.signalLevel}/4 格")
        return parts.ifEmpty { listOf("无信号") }.joinToString("  |  ")
    }

    /**
     * 动态生成 IP 列表
     * 移动网络接口显示：SIM1 移动 ★ (rmnet_data0)
     * 普通接口直接显示接口名
     */
    private fun updateIpList(
        ipList: List<IpHelper.InterfaceIp>,
        signalInfo: NetworkInfoHelper.SignalInfo
    ) {
        val container = binding.ipListContainer
        container.removeAllViews()

        if (ipList.isEmpty()) {
            container.addView(createIpTextView("未检测到网络接口"))
            return
        }

        for ((index, iface) in ipList.withIndex()) {
            val labelText = buildIfaceLabel(iface, signalInfo)
            val label = createIpTextView(labelText)
            label.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            label.setTypeface(null, android.graphics.Typeface.BOLD)
            container.addView(label)

            iface.ipv4?.let {
                val tv = createIpTextView("  IPv4  $it")
                tv.typeface = android.graphics.Typeface.MONOSPACE
                container.addView(tv)
            }

            iface.ipv6?.let {
                val tv = createIpTextView("  IPv6  $it")
                tv.typeface = android.graphics.Typeface.MONOSPACE
                container.addView(tv)
            }

            if (index < ipList.size - 1) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { setMargins(0, 12, 0, 12) }
                    setBackgroundColor(0x22FFFFFF.toInt())
                }
                container.addView(divider)
            }
        }
    }

    /**
     * 接口标签：
     * - 移动网络接口（rmnet 类）：
     *     若接口名与 activeIfaceName 完全匹配 → 显示运营商 + ★
     *     否则 → 仅显示"移动网络"
     * - 非移动接口：直接显示接口名
     */
    private fun buildIfaceLabel(
        iface: IpHelper.InterfaceIp,
        signalInfo: NetworkInfoHelper.SignalInfo
    ): String {
        if (!iface.isMobile) return iface.ifaceName
        return if (iface.ifaceName == signalInfo.activeIfaceName) {
            // 当前数据卡接口：找到对应运营商
            val dataSim = signalInfo.sims.find { it.isDataSim }
            val carrier = dataSim?.carrierName?.let { " $it" } ?: ""
            val slotLabel = dataSim?.let { " SIM${it.slotIndex + 1}" } ?: ""
            "移动网络$slotLabel$carrier ★  (${iface.ifaceName})"
        } else {
            "移动网络  (${iface.ifaceName})"
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

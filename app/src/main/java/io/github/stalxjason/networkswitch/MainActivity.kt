package io.github.stalxjason.networkswitch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
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
            updateSignalView(signalInfo)
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

    // ─────────────────────────────────────────────────────────────────────────
    // 信号区域：每张 SIM 一行
    //   [▎▎▎▎]  SIM1  中国电信  LTE  ★  -89 dBm
    //   [▎▎░░]  SIM2  中国移动  LTE     -102 dBm
    // ─────────────────────────────────────────────────────────────────────────
    private fun updateSignalView(info: NetworkInfoHelper.SignalInfo) {
        val container = binding.llSignalContainer
        container.removeAllViews()

        if (info.sims.isEmpty()) {
            val tv = TextView(this).apply {
                text = "无 SIM 卡"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            }
            container.addView(tv)
            return
        }

        val primaryColor   = ContextCompat.getColor(this, R.color.primary)
        val hintColor      = ContextCompat.getColor(this, R.color.text_hint)
        val textPrimary    = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary  = ContextCompat.getColor(this, R.color.text_secondary)

        info.sims.sortedBy { it.slotIndex }.forEachIndexed { idx, sim ->
            // 行容器
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (idx > 0) topMargin = dpToPx(8)
                }
            }

            // 信号格（4格，阶梯高度）
            val barsLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dpToPx(10) }
            }
            val barHeights = intArrayOf(6, 10, 15, 20) // dp
            for (i in 0..3) {
                val bar = View(this).apply {
                    val active = i < sim.signalLevel
                    setBackgroundColor(if (active) primaryColor else hintColor)
                    layoutParams = LinearLayout.LayoutParams(
                        dpToPx(5), dpToPx(barHeights[i])
                    ).apply { if (i > 0) marginStart = dpToPx(2) }
                }
                barsLayout.addView(bar)
            }
            row.addView(barsLayout)

            // SIM 标签
            val tvSlot = TextView(this).apply {
                text = "SIM${sim.slotIndex + 1}"
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setBackgroundColor(if (sim.isDataSim) primaryColor else hintColor)
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dpToPx(8) }
            }
            row.addView(tvSlot)

            // 运营商名
            val tvCarrier = TextView(this).apply {
                text = sim.carrierName ?: "未知"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textPrimary)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dpToPx(6) }
            }
            row.addView(tvCarrier)

            // 网络类型
            sim.networkTypeName?.let { netType ->
                val tvNet = TextView(this).apply {
                    text = netType
                    textSize = 12f
                    setTextColor(textSecondary)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dpToPx(4) }
                }
                row.addView(tvNet)
            }

            // 数据卡标记 ★
            if (sim.isDataSim) {
                val tvMark = TextView(this).apply {
                    text = "★"
                    textSize = 11f
                    setTextColor(primaryColor)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dpToPx(6) }
                }
                row.addView(tvMark)
            }

            // dBm
            sim.signalDbm?.let { dbm ->
                val tvDbm = TextView(this).apply {
                    text = "$dbm dBm"
                    textSize = 11f
                    setTextColor(textSecondary)
                }
                row.addView(tvDbm)
            }

            container.addView(row)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IP 列表
    // ─────────────────────────────────────────────────────────────────────────
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
                    ).apply { setMargins(0, dpToPx(10), 0, dpToPx(10)) }
                    setBackgroundColor(0x22FFFFFF.toInt())
                }
                container.addView(divider)
            }
        }
    }

    private fun buildIfaceLabel(
        iface: IpHelper.InterfaceIp,
        signalInfo: NetworkInfoHelper.SignalInfo
    ): String {
        if (!iface.isMobile) return iface.ifaceName
        return if (iface.ifaceName == signalInfo.activeIfaceName) {
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
            ).apply { topMargin = dpToPx(3); bottomMargin = dpToPx(3) }
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

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()
}

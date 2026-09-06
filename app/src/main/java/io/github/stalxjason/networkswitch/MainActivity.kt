package io.github.stalxjason.networkswitch

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import io.github.stalxjason.networkswitch.databinding.ActivityMainBinding
import io.github.stalxjason.networkswitch.wifi.WifiListActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingWifiPage = false
    private var initializingThemeUI = false

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
                // 授权前点过「查看 WiFi 密码」→ 授权成功直接进入
                if (granted && pendingWifiPage) {
                    pendingWifiPage = false
                    startActivity(Intent(this, WifiListActivity::class.java))
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppTheme.applyAccent(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        setupUI()
        setupThemeUI()
        autoShizukuPrompt()

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

    /**
     * 打开 App 即处理 Shizuku：运行中未授权 → 自动弹出授权弹窗；
     * 未运行 → 提示引导（点「授权 Shizuku」会拉起 Shizuku 应用）。
     */
    private fun autoShizukuPrompt() {
        when (ShizukuHelper.getStatus()) {
            is ShizukuHelper.Status.Running -> ShizukuHelper.requestPermission()
            is ShizukuHelper.Status.NotRunning ->
                Toast.makeText(
                    this,
                    "Shizuku 未运行：点「授权 Shizuku」启动并授权",
                    Toast.LENGTH_LONG
                ).show()
            else -> {}
        }
    }

    /** 外观设置：深浅模式三段选择 + 主题色圆点 */
    private fun setupThemeUI() {
        initializingThemeUI = true
        when (AppTheme.savedMode(this)) {
            AppTheme.MODE_LIGHT -> binding.toggleTheme.check(R.id.btn_theme_light)
            AppTheme.MODE_DARK -> binding.toggleTheme.check(R.id.btn_theme_dark)
            else -> binding.toggleTheme.check(R.id.btn_theme_system)
        }
        initializingThemeUI = false

        binding.toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || initializingThemeUI) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btn_theme_light -> AppTheme.MODE_LIGHT
                R.id.btn_theme_dark -> AppTheme.MODE_DARK
                else -> AppTheme.MODE_SYSTEM
            }
            AppTheme.setNightMode(this, mode)
        }

        val accentViews = linkedMapOf(
            "blue" to binding.accentBlue,
            "green" to binding.accentGreen,
            "purple" to binding.accentPurple,
            "orange" to binding.accentOrange,
        )
        fun refreshAccentDots(selected: String) {
            accentViews.forEach { (key, view) ->
                val normal = when (key) {
                    "green" -> R.drawable.bg_accent_green
                    "purple" -> R.drawable.bg_accent_purple
                    "orange" -> R.drawable.bg_accent_orange
                    else -> R.drawable.bg_accent_blue
                }
                val selectedDr = when (key) {
                    "green" -> R.drawable.bg_accent_green_selected
                    "purple" -> R.drawable.bg_accent_purple_selected
                    "orange" -> R.drawable.bg_accent_orange_selected
                    else -> R.drawable.bg_accent_blue_selected
                }
                view.background =
                    ContextCompat.getDrawable(this, if (key == selected) selectedDr else normal)
            }
        }
        val saved = AppTheme.savedAccent(this)
        refreshAccentDots(saved)
        accentViews.forEach { (key, view) ->
            view.setOnClickListener {
                AppTheme.setAccent(this, key)
                refreshAccentDots(key)
                recreate()
            }
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
        binding.btnWifiPassword.setOnClickListener { openWifiPasswordPage() }
        binding.btnOpenSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
        }
        binding.btnRefresh.setOnClickListener { lifecycleScope.launch { refreshStatus() } }
    }

    private fun openWifiPasswordPage() {
        when (ShizukuHelper.getStatus()) {
            is ShizukuHelper.Status.Authorized ->
                startActivity(Intent(this, WifiListActivity::class.java))
            is ShizukuHelper.Status.Running -> {
                pendingWifiPage = true
                ShizukuHelper.requestPermission()
                Toast.makeText(this, "请在 Shizuku 授权弹窗中允许", Toast.LENGTH_LONG).show()
            }
            else -> {
                Toast.makeText(this, "需要 Shizuku：请先启动 Shizuku 应用", Toast.LENGTH_LONG).show()
                openShizukuApp()
            }
        }
    }

    private fun openShizukuApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: packageManager.getLaunchIntentForPackage("rikka.shizuku")
            if (intent != null) startActivity(intent)
        } catch (_: Exception) {}
    }

    private suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        // 网络模式（shell 回读真值，不可用时回落 settings 键）
        val currentMode = NetworkModeHelper.queryMode(this@MainActivity)
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
        val ipList = IpHelper.getAllIpEntries(this@MainActivity)
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

        val primaryColor   = primaryColor()
        val hintColor      = ContextCompat.getColor(this, R.color.text_hint)
        val textPrimary    = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary  = ContextCompat.getColor(this, R.color.text_secondary)
        val dividerColor   = ContextCompat.getColor(this, R.color.divider)

        info.sims.sortedBy { it.slotIndex }.forEachIndexed { idx, sim ->
            // 行容器
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (idx > 0) topMargin = dpToPx(10)
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
                    setBackgroundColor(if (active) primaryColor else dividerColor)
                    layoutParams = LinearLayout.LayoutParams(
                        dpToPx(5), dpToPx(barHeights[i])
                    ).apply { if (i > 0) marginStart = dpToPx(2) }
                }
                barsLayout.addView(bar)
            }
            row.addView(barsLayout)

            // SIM 标签：数据卡用实色主题芯片（半透明 tint 会把主题色洗淡导致白字不可读），
            // 其余卡用中性芯片
            val tvSlot = TextView(this).apply {
                text = "SIM${sim.slotIndex + 1}"
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dpToPx(8) }
                if (sim.isDataSim) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dpToPx(6).toFloat()
                        setColor(primaryColor())
                    }
                    setTextColor(0xFFFFFFFF.toInt())
                } else {
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
                    setTextColor(textSecondary)
                }
            }
            row.addView(tvSlot)

            // 运营商名
            val tvCarrier = TextView(this).apply {
                text = sim.carrierName ?: "未知"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textPrimary)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dpToPx(6) }
            }
            row.addView(tvCarrier)

            // 网络类型（芯片样式）
            sim.networkTypeName?.let { netType ->
                val chip = createChip(netType)
                chip.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dpToPx(4) }
                row.addView(chip)
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
    // IP 列表（ConnectivityManager 网络 → SIM 归属），单行要点式：
    //   • 移动网络 SIM1 中国电信 ★（rmnet_data3）— IPv4 x.x.x.x ＋ IPv6 xxxx
    //   • 移动网络 SIM1 中国电信（rmnet_data1）— IPv6 xxxx（待机承载）
    // ─────────────────────────────────────────────────────────────────────────
    private fun updateIpList(ipList: List<IpHelper.IpEntry>) {
        val container = binding.ipListContainer
        container.removeAllViews()

        if (ipList.isEmpty()) {
            container.addView(createIpTextView("未检测到网络接口"))
            return
        }

        for (entry in ipList) {
            container.addView(createIpLineView(entry))
        }
    }

    private fun entryLabelMain(entry: IpHelper.IpEntry): String = when (entry.kind) {
        IpHelper.Kind.MOBILE -> buildString {
            append("移动网络")
            entry.simSlotIndex?.let { append(" SIM${it + 1}") }
            entry.simCarrier?.let { c -> append(" ").append(c) }
            if (entry.isActiveData) append(" ★")
        }
        IpHelper.Kind.WIFI -> "WLAN"
        IpHelper.Kind.ETHERNET -> "以太网"
        IpHelper.Kind.VPN -> "VPN"
    }

    /** 单行 IP 条目：• 标签（接口名）— IP…；点按复制该条的 IP */
    private fun createIpLineView(entry: IpHelper.IpEntry): View {
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val textHint = ContextCompat.getColor(this, R.color.text_hint)

        val label = "${entryLabelMain(entry)}（${entry.ifaceName}）"
        val ips = buildList {
            entry.ipv4?.let { add("IPv4 $it") }
            entry.ipv6?.let { add("IPv6 $it") }
        }
        val standby = entry.kind == IpHelper.Kind.MOBILE &&
                !entry.isActiveData && entry.simSubscriptionId != null

        return TextView(this).apply {
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(4); bottomMargin = dpToPx(4) }

            val ssb = SpannableStringBuilder()
            ssb.append(label)
            ssb.setSpan(
                StyleSpan(Typeface.BOLD), 0, label.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            ssb.setSpan(
                BulletSpan(dpToPx(6), primaryColor()),
                0, label.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            // 每个 IP 单独一行
            ips.forEachIndexed { i, item ->
                ssb.append("\n    —  ")
                val start = ssb.length
                ssb.append(item)
                ssb.setSpan(
                    ForegroundColorSpan(textSecondary), start, ssb.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (standby && i == ips.lastIndex) {
                    val note = ssb.length
                    ssb.append("（待机承载）")
                    ssb.setSpan(
                        ForegroundColorSpan(textHint), note, ssb.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    ssb.setSpan(
                        RelativeSizeSpan(0.85f), note, ssb.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            text = ssb
            setOnClickListener {
                copyTextToClipboard(
                    ips.joinToString("\n") { it.substringAfter(' ') },
                    "IP 地址"
                )
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
            ).apply { topMargin = dpToPx(3); bottomMargin = dpToPx(3) }
        }
    }

    /** 小号圆角芯片（网络类型 / IPv4·IPv6 前缀） */
    private fun createChip(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 10f
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
        setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun copyTextToClipboard(text: String, label: String = "文本") {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
    }

    /** 当前主题色（跟随「外观」里的主题色选择） */
    private fun primaryColor(): Int =
        MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)

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
            ResizableNetworkWidgetProvider.updateWidget(this@MainActivity)
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()
}

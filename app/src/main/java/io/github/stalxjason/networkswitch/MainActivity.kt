package io.github.stalxjason.networkswitch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import io.github.stalxjason.networkswitch.databinding.ActivityMainBinding
import io.github.stalxjason.networkswitch.wifi.WifiListActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val ipAdapter = IpListAdapter()
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
                    if (granted) R.string.toast_shizuku_granted else R.string.toast_shizuku_denied,
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

        // 配置重建（换主题 / 旋转）后仍需保留「授权完去 WiFi 密码页」的意图
        pendingWifiPage = savedInstanceState?.getBoolean(KEY_PENDING_WIFI_PAGE) ?: false

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        setupUI()
        setupThemeUI()
        autoShizukuPrompt()

        // READ_BASIC_PHONE_STATE 是 API 33 常量，minSdk 31 不能参与运行时判断，
        // 否则会被内联进字节码（lint InlinedApi）
        val wantedPerms = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_BASIC_PHONE_STATE)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (wantedPerms.isNotEmpty()) {
            requestPhonePermission.launch(wantedPerms.toTypedArray())
        } else {
            lifecycleScope.launch { refreshStatus() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PENDING_WIFI_PAGE, pendingWifiPage)
    }

    /**
     * 打开 App 即处理 Shizuku：运行中未授权 → 自动弹出授权弹窗；
     * 未运行 → 提示引导（点「授权 Shizuku」会拉起 Shizuku 应用）。
     */
    private fun autoShizukuPrompt() {
        when (ShizukuHelper.getStatus(this)) {
            is ShizukuHelper.Status.Running -> ShizukuHelper.requestPermission()
            is ShizukuHelper.Status.NotRunning ->
                Toast.makeText(this, R.string.toast_shizuku_not_running_guide, Toast.LENGTH_LONG)
                    .show()
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
        binding.ipRecycler.layoutManager = LinearLayoutManager(this)
        binding.ipRecycler.adapter = ipAdapter

        binding.btnToggle.setOnClickListener { performToggle() }
        binding.btnShizukuAuth.setOnClickListener {
            when (val status = ShizukuHelper.getStatus(this)) {
                is ShizukuHelper.Status.Authorized ->
                    Toast.makeText(this, R.string.toast_shizuku_authorized_short, Toast.LENGTH_SHORT)
                        .show()
                is ShizukuHelper.Status.Running -> ShizukuHelper.requestPermission()
                is ShizukuHelper.Status.NotRunning -> {
                    Toast.makeText(this, R.string.toast_shizuku_start_first, Toast.LENGTH_LONG)
                        .show()
                    launchShizukuApp()
                }
                else ->
                    Toast.makeText(this, R.string.toast_shizuku_install_first, Toast.LENGTH_LONG)
                        .show()
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
        when (ShizukuHelper.getStatus(this)) {
            is ShizukuHelper.Status.Authorized ->
                startActivity(Intent(this, WifiListActivity::class.java))
            is ShizukuHelper.Status.Running -> {
                pendingWifiPage = true
                ShizukuHelper.requestPermission()
                Toast.makeText(this, R.string.toast_shizuku_allow_hint, Toast.LENGTH_LONG).show()
            }
            else -> {
                Toast.makeText(this, R.string.toast_shizuku_needed, Toast.LENGTH_LONG).show()
                launchShizukuApp()
            }
        }
    }

    /** 拉起 Shizuku 应用；未安装或无法启动时提示安装 */
    private fun launchShizukuApp() {
        if (!ShizukuHelper.openShizukuApp(this)) {
            Toast.makeText(this, R.string.toast_shizuku_install_first, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 刷新全部状态。五段查询互不依赖：并行发起、各自 await 后立即刷新对应区域，
     * 避免串行等待（shell 回读 + su 探测）拖慢首屏。
     */
    private suspend fun refreshStatus() = coroutineScope {
        val modeDeferred = async(Dispatchers.IO) { NetworkModeHelper.queryMode(this@MainActivity) }
        val signalDeferred = async(Dispatchers.IO) { NetworkInfoHelper.getSignalInfo(this@MainActivity) }
        val ipDeferred = async(Dispatchers.IO) { IpHelper.getAllIpEntries(this@MainActivity) }
        val shizukuDeferred = async { ShizukuHelper.getStatus(this@MainActivity) }
        val rootDeferred = async(Dispatchers.IO) { NetworkModeHelper.hasRootAccess() }

        // 网络模式（shell 回读真值，不可用时回落 settings 键）
        val currentMode = modeDeferred.await()
        binding.tvCurrentMode.text = getString(currentMode.labelRes)
        binding.tvModeDesc.text = getString(currentMode.descRes)

        // 信号与运营商（双卡）
        updateSignalView(signalDeferred.await())

        // IP 列表
        updateIpList(ipDeferred.await())

        // Shizuku / Root 状态
        val shizukuStatus = shizukuDeferred.await()
        binding.tvShizukuStatus.text = when (shizukuStatus) {
            is ShizukuHelper.Status.Authorized -> R.string.shizuku_authorized
            is ShizukuHelper.Status.Running -> R.string.shizuku_running
            is ShizukuHelper.Status.NotRunning -> R.string.shizuku_not_running
            is ShizukuHelper.Status.NotInstalled -> R.string.shizuku_not_installed
        }.let { getString(it) }

        val hasRoot = rootDeferred.await()
        binding.tvRootStatus.text = getString(
            if (hasRoot) R.string.root_available else R.string.root_unavailable
        )

        val canToggle = shizukuStatus is ShizukuHelper.Status.Authorized || hasRoot
        binding.btnToggle.isEnabled = canToggle
        binding.btnToggle.text = getString(
            if (canToggle) R.string.btn_toggle_network else R.string.btn_toggle_disabled
        )

        binding.btnShizukuAuth.text = when (shizukuStatus) {
            is ShizukuHelper.Status.Authorized -> R.string.shizuku_ready
            else -> R.string.btn_shizuku_auth
        }.let { getString(it) }
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
                text = getString(R.string.no_sim)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            }
            container.addView(tv)
            return
        }

        val primaryColor   = primaryColor()
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
                text = getString(R.string.sim_label, sim.slotIndex + 1)
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
                text = sim.carrierName ?: getString(R.string.carrier_unknown)
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
                    text = getString(R.string.data_sim_star)
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
                    text = getString(R.string.signal_dbm, dbm)
                    textSize = 11f
                    setTextColor(textSecondary)
                }
                row.addView(tvDbm)
            }

            container.addView(row)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IP 列表（ConnectivityManager 网络 → SIM 归属），每条一张卡片：
    //   移动网络 SIM1 中国电信 ★（rmnet_data3）
    //   —  IPv4 x.x.x.x
    //   —  IPv6 xxxx（待机承载）
    // 行内排版见 IpListAdapter
    // ─────────────────────────────────────────────────────────────────────────
    private fun updateIpList(ipList: List<IpHelper.IpEntry>) {
        binding.ipRecycler.visibility = if (ipList.isEmpty()) View.GONE else View.VISIBLE
        binding.tvIpEmpty.visibility = if (ipList.isEmpty()) View.VISIBLE else View.GONE
        ipAdapter.submit(ipList)
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

    /** 当前主题色（跟随「外观」里的主题色选择） */
    private fun primaryColor(): Int =
        MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)

    private fun performToggle() {
        binding.btnToggle.isEnabled = false
        binding.btnToggle.text = getString(R.string.btn_toggle_working)

        lifecycleScope.launch {
            try {
                val result = NetworkModeHelper.toggleNetworkMode(this@MainActivity)

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
            } finally {
                // 任何异常都要解锁按钮，否则卡在「切换中…」永久不可点
                binding.btnToggle.isEnabled = true
            }
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val KEY_PENDING_WIFI_PAGE = "pending_wifi_page"
    }
}

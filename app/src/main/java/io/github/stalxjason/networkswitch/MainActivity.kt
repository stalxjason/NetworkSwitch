package io.github.stalxjason.networkswitch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import io.github.stalxjason.networkswitch.databinding.ActivityMainBinding
import io.github.stalxjason.networkswitch.databinding.ItemSignalBinding
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

    /** 已申请过一次电话权限；用于区分「还能再问」和「只能去系统设置」 */
    private var phonePermissionDenied = false

    // 运行时请求 READ_PHONE_STATE 权限（只在用户点信号区提示时触发）
    private val requestPhonePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        phonePermissionDenied =
            granted[Manifest.permission.READ_PHONE_STATE] != true
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
        // targetSdk 35+ 强制 edge-to-edge：状态栏/导航栏设为透明，内容自行避让
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        // 配置重建（换主题 / 旋转）后仍需保留「授权完去 WiFi 密码页」的意图
        pendingWifiPage = savedInstanceState?.getBoolean(KEY_PENDING_WIFI_PAGE) ?: false

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        setupUI()
        setupThemeUI()

        // 不自动弹任何权限：首屏直接渲染，无电话权限时信号区里给一行「点此授权」，
        // 由用户主动触发申请（READ_BASIC_PHONE_STATE 是 normal 权限，不需要运行时请求）。
        lifecycleScope.launch { refreshStatus() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PENDING_WIFI_PAGE, pendingWifiPage)
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

        // Root 兜底开关：默认关闭，勾选后才在取密失败时尝试 su
        binding.cbAllowRoot.isChecked = AppSettings.allowRootFallback(this)
        binding.cbAllowRoot.setOnCheckedChangeListener { _, checked ->
            AppSettings.setAllowRootFallback(this, checked)
        }
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
        // Shizuku 状态包含 PackageManager 查询与 binder ping，不能留主线程
        val shizukuDeferred = async(Dispatchers.IO) { ShizukuHelper.getStatus(this@MainActivity) }
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
    private fun hasPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 没有电话权限时，信号区放一行可点提示：先说明用途再申请；
     * 已被「不再询问」拦下时，引导去系统设置里开。
     */
    private fun addSignalPermissionHint(container: LinearLayout) {
        val canAsk = ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.READ_PHONE_STATE
        )
        val goSettings = phonePermissionDenied && !canAsk
        val hint = TextView(this).apply {
            text = getString(
                if (goSettings) R.string.signal_permission_settings
                else R.string.signal_permission_rationale
            )
            // dimen 写的是 sp，getDimension 已按 scaledDensity 折算成 px，textSize 属性正是 px
            textSize = resources.getDimension(R.dimen.signal_text_placeholder)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            val padV = resources.getDimensionPixelSize(R.dimen.signal_hint_padding_v)
            setPadding(0, padV, 0, padV)
            setOnClickListener {
                if (goSettings) {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)
                        )
                    )
                } else {
                    requestPhonePermission.launch(
                        arrayOf(Manifest.permission.READ_PHONE_STATE)
                    )
                }
            }
        }
        container.addView(hint)
    }

    private fun updateSignalView(info: NetworkInfoHelper.SignalInfo) {
        val container = binding.llSignalContainer
        container.removeAllViews()

        // 没电话权限时不要显示「无 SIM 卡」——那是在说假话
        if (!hasPhonePermission()) {
            addSignalPermissionHint(container)
            return
        }

        if (info.sims.isEmpty()) {
            // 区分「真的没插卡」和「读不到」——后者要让用户知道是权限/异常，不是没卡
            val emptyText = when (info.error) {
                NetworkInfoHelper.SignalError.NO_TELEPHONY_SERVICE ->
                    getString(R.string.signal_unsupported)

                NetworkInfoHelper.SignalError.PERMISSION,
                NetworkInfoHelper.SignalError.NO_SUBSCRIPTION_SERVICE,
                NetworkInfoHelper.SignalError.READ_FAILED ->
                    getString(R.string.signal_read_failed)

                null -> getString(R.string.no_sim)
            }
            val tv = TextView(this).apply {
                text = emptyText
                textSize = resources.getDimension(R.dimen.signal_text_placeholder)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            }
            container.addView(tv)
            return
        }

        val primaryColor   = primaryColor()
        val textPrimary    = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary  = ContextCompat.getColor(this, R.color.text_secondary)
        val dividerColor   = ContextCompat.getColor(this, R.color.divider)
        val onPrimary      = ContextCompat.getColor(this, R.color.on_primary)

        info.sims.sortedBy { it.slotIndex }.forEachIndexed { idx, sim ->
            val row = ItemSignalBinding.inflate(layoutInflater, container, false)
            if (idx > 0) {
                (row.root.layoutParams as? LinearLayout.LayoutParams)?.topMargin =
                    resources.getDimensionPixelSize(R.dimen.signal_row_gap)
            }

            // 信号格：达到的格数用主题色，其余用分隔线色
            arrayOf(row.bar1, row.bar2, row.bar3, row.bar4).forEachIndexed { i, bar ->
                bar.setBackgroundColor(if (i < sim.signalLevel) primaryColor else dividerColor)
            }

            // SIM 标签：数据卡用实色主题芯片（半透明 tint 会把主题色洗淡导致白字不可读），
            // 其余卡用中性芯片
            row.tvSlot.text = getString(R.string.sim_label, sim.slotIndex + 1)
            if (sim.isDataSim) {
                row.tvSlot.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = resources.getDimension(R.dimen.signal_chip_corner)
                    setColor(primaryColor)
                }
                row.tvSlot.setTextColor(onPrimary)
            } else {
                row.tvSlot.background = ContextCompat.getDrawable(this, R.drawable.bg_chip)
                row.tvSlot.setTextColor(textSecondary)
            }

            // 运营商名
            row.tvCarrier.text = sim.carrierName ?: getString(R.string.carrier_unknown)
            row.tvCarrier.setTextColor(textPrimary)

            // 网络类型（芯片样式）
            row.tvNet.text = sim.networkTypeName.orEmpty()
            row.tvNet.visibility = if (sim.networkTypeName != null) View.VISIBLE else View.GONE

            // 数据卡标记 ★
            row.tvStar.setTextColor(primaryColor)
            row.tvStar.visibility = if (sim.isDataSim) View.VISIBLE else View.GONE

            // dBm
            row.tvDbm.text = sim.signalDbm?.let { getString(R.string.signal_dbm, it) }.orEmpty()
            row.tvDbm.setTextColor(textSecondary)
            row.tvDbm.visibility = if (sim.signalDbm != null) View.VISIBLE else View.GONE

            container.addView(row.root)
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

                NetworkWidgetProvider.updateWidget(this@MainActivity)
                NetworkVerticalWidgetProvider.updateWidget(this@MainActivity)
                ResizableNetworkWidgetProvider.updateWidget(this@MainActivity)
            } finally {
                // 可用性统一由 refreshStatus 决定（无 Shizuku/Root 时保持禁用）；
                // 放在 finally 里，异常路径也不会永久卡在「切换中…」
                runCatching { refreshStatus() }
            }
        }
    }

    /** edge-to-edge 下由内容自己避让状态栏/导航栏（替代 XML 里的 fitsSystemWindows） */
    private fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private companion object {
        const val KEY_PENDING_WIFI_PAGE = "pending_wifi_page"
    }
}

package io.github.stalxjason.networkswitch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import io.github.stalxjason.networkswitch.databinding.ActivityMainBinding
import io.github.stalxjason.networkswitch.databinding.ItemSignalBinding
import io.github.stalxjason.networkswitch.databinding.SheetAppearanceBinding
import io.github.stalxjason.networkswitch.wifi.WifiListActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import kotlin.math.roundToInt

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

    /**
     * 外观设置：首页只留一行摘要，具体的深浅模式与主题色在底部弹窗里改。
     * 首页曾经放了一整张卡（3 段主题 + 4 个色点 = 7 个可点目标），属于配置一次就不再动的低频项。
     */
    private fun setupThemeUI() {
        updateAppearanceSummary()
        binding.rowAppearance.setOnClickListener { showAppearanceSheet() }
    }

    /** 首页「外观」行的摘要：跟随系统 · 蓝色 */
    private fun updateAppearanceSummary() {
        val modeText = getString(
            when (AppTheme.savedMode(this)) {
                AppTheme.MODE_LIGHT -> R.string.theme_light
                AppTheme.MODE_DARK -> R.string.theme_dark
                else -> R.string.theme_system
            }
        )
        val accentText = getString(AppTheme.accentOf(AppTheme.savedAccent(this)).nameRes)
        binding.tvAppearanceSummary.text = getString(R.string.theme_summary, modeText, accentText)
    }

    private fun showAppearanceSheet() {
        val sheet = SheetAppearanceBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)

        initializingThemeUI = true
        when (AppTheme.savedMode(this)) {
            AppTheme.MODE_LIGHT -> sheet.toggleTheme.check(R.id.btn_theme_light)
            AppTheme.MODE_DARK -> sheet.toggleTheme.check(R.id.btn_theme_dark)
            else -> sheet.toggleTheme.check(R.id.btn_theme_system)
        }
        initializingThemeUI = false

        sheet.toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || initializingThemeUI) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btn_theme_light -> AppTheme.MODE_LIGHT
                R.id.btn_theme_dark -> AppTheme.MODE_DARK
                else -> AppTheme.MODE_SYSTEM
            }
            AppTheme.setNightMode(this, mode)
            updateAppearanceSummary()
        }

        // 主题色圆点按 ACCENTS 生成：加一个颜色只需要补一组颜色值 + 一条 AccentOverlay，
        // 不用再为它画两张 drawable（选中态是代码描边）。
        val selectedKey = AppTheme.savedAccent(this)
        val dotSize = dp(34)
        AppTheme.ACCENTS.forEach { accent ->
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize)
                    .apply { marginEnd = dp(16) }
                background = accentDot(
                    AppTheme.accentColor(this@MainActivity, accent),
                    accent.key == selectedKey
                )
                contentDescription = getString(accent.nameRes)
            }
            dot.setOnClickListener {
                if (accent.key == AppTheme.savedAccent(this)) return@setOnClickListener
                AppTheme.setAccent(this, accent.key)
                // 主题色在 setContentView 之前用 applyStyle 覆盖，只能重建整页来应用
                dialog.dismiss()
                recreate()
            }
            sheet.accentRow.addView(dot)
        }
        dialog.show()
    }

    /** 主题色圆点：选中时描一圈正文色，未选中只填色 */
    private fun accentDot(color: Int, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (selected) {
                setStroke(dp(2), ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

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
        binding.rowShizuku.setOnClickListener { onShizukuRowClick() }
        // 主卡底部那行提示也直达授权：它是「不能切网」时最该先点的东西
        binding.heroHintRow.setOnClickListener { onShizukuRowClick() }
        binding.rowWifiPassword.setOnClickListener { openWifiPasswordPage() }
        binding.rowOpenSettings.setOnClickListener { openNetworkSettings() }
        binding.btnRefresh.setOnClickListener { lifecycleScope.launch { refreshStatus() } }

        // 可折叠行：信号默认展开（一眼看到几格、几个卡），IP 默认收起
        binding.rowSignal.setOnClickListener {
            setExpanded(
                binding.signalContainer,
                binding.chevSignal,
                binding.signalContainer.visibility != View.VISIBLE
            )
        }
        binding.rowIp.setOnClickListener {
            setExpanded(
                binding.ipContainer,
                binding.chevIp,
                binding.ipContainer.visibility != View.VISIBLE
            )
        }
        binding.chevSignal.rotation = 180f

        // Root 兜底开关：默认关闭，勾选后才在取密失败时尝试 su
        binding.cbAllowRoot.isChecked = AppSettings.allowRootFallback(this)
        binding.cbAllowRoot.setOnCheckedChangeListener { _, checked ->
            AppSettings.setAllowRootFallback(this, checked)
        }

        // 底部渐隐只在还能继续往下滚时显示
        binding.scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateFade() }
        binding.scroll.post { updateFade() }
    }

    /** 折叠/展开一行：内容显隐 + chevron 翻转 */
    private fun setExpanded(content: View, chevron: ImageView, expanded: Boolean) {
        content.visibility = if (expanded) View.VISIBLE else View.GONE
        chevron.animate()
            .rotation(if (expanded) 180f else 0f)
            .setDuration(160)
            .start()
    }

    private fun updateFade() {
        binding.fadeBottom.visibility =
            if (binding.scroll.canScrollVertically(1)) View.VISIBLE else View.GONE
    }

    /** Shizuku 一行的点击：已授权只提示，运行中申请，未运行/未安装则引导 */
    private fun onShizukuRowClick() {
        when (ShizukuHelper.getStatus(this)) {
            is ShizukuHelper.Status.Authorized ->
                Toast.makeText(this, R.string.toast_shizuku_authorized_short, Toast.LENGTH_SHORT)
                    .show()

            is ShizukuHelper.Status.Running -> ShizukuHelper.requestPermission()

            is ShizukuHelper.Status.NotRunning -> {
                Toast.makeText(this, R.string.toast_shizuku_start_first, Toast.LENGTH_LONG).show()
                launchShizukuApp()
            }

            else ->
                Toast.makeText(this, R.string.toast_shizuku_install_first, Toast.LENGTH_LONG).show()
        }
        lifecycleScope.launch { refreshStatus() }
    }

    private fun openNetworkSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
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

        val authorized = shizukuStatus is ShizukuHelper.Status.Authorized
        val canToggle = authorized || hasRoot

        binding.btnToggle.isEnabled = canToggle
        // 文案写目标态：现在是 4G 就显示「切换到 5G NR」，比「切换 4G/5G」说得清楚
        binding.btnToggle.text = getString(
            when {
                !canToggle -> R.string.btn_toggle_disabled
                currentMode == NetworkMode.LTE -> R.string.btn_switch_to_5g
                else -> R.string.btn_switch_to_4g
            }
        )

        // 主卡上的授权状态：胶囊文案 + 状态点颜色（绿=可切换，琥珀=还需授权）
        binding.tvHeroStatus.setText(
            if (canToggle) R.string.hero_status_ready else R.string.hero_status_need
        )
        binding.tvHeroHint.setText(
            if (canToggle) R.string.hero_hint_ready else R.string.hero_hint_need
        )
        val dotColor = ContextCompat.getColor(
            this@MainActivity, if (canToggle) R.color.status_dot else R.color.status_dot_warn
        )
        binding.heroStatusDot.backgroundTintList = ColorStateList.valueOf(dotColor)
        binding.heroHintDot.backgroundTintList = ColorStateList.valueOf(dotColor)

        binding.tvShizukuAction.setText(
            if (authorized) R.string.row_shizuku_action_ready else R.string.row_shizuku_action_grant
        )
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
        val container = binding.signalContainer
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
        // 收起状态下也能一眼看到主要地址；没有就退回说明文案
        val primary = ipList.firstOrNull { it.ipv4 != null }?.ipv4
            ?: ipList.firstOrNull()?.ipv6
        binding.tvIpSummary.text = primary ?: getString(R.string.row_ip_sub)
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

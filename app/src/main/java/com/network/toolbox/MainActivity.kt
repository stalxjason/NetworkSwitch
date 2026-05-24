package com.network.toolbox

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.network.toolbox.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.text.Editable
import android.text.TextWatcher

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val wifiAdapter = WifiListAdapter()
    private var allWifiNetworks: List<WiFiInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-Edge：内容延伸到状态栏和导航栏下方
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT

        // 导航栏图标颜色适配主题
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = !isDark

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 系统栏 Insets：顶部条延伸到状态栏下，底部给导航栏留空间
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, 0, 0, navBars.bottom)
            insets
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.topStatusBar) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(view.paddingLeft, statusBars.top, view.paddingRight, view.paddingBottom)
            insets
        }

        ShizukuManager.init()
        setupBottomNav()
        setupWifiTab()
        setupNetworkTab()
        setupShizukuObservers()

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_wifi
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuManager.updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        ShizukuManager.destroy()
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_wifi -> { binding.wifiContainer.visibility = View.VISIBLE; binding.networkContainer.visibility = View.GONE; true }
                R.id.nav_network -> { binding.wifiContainer.visibility = View.GONE; binding.networkContainer.visibility = View.VISIBLE; refreshNetworkInfo(); true }
                else -> false
            }
        }
    }

    // ─── WiFi Tab ────────────────────────────────────────────────────

    private fun setupWifiTab() {
        binding.recyclerViewWifi.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.recyclerViewWifi.adapter = wifiAdapter

        binding.swipeRefreshWifi.setOnRefreshListener { loadWifi() }

        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { filterWifi(s?.toString() ?: "") }
        })

        binding.btnDebugLog.setOnClickListener {
            val log = WifiReader.debugLog
            if (log.isEmpty()) { Toast.makeText(this, "暂无日志", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val dialog = android.app.Dialog(this)
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            dialog.setContentView(R.layout.dialog_debug)
            dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            dialog.findViewById<TextView>(R.id.tvDebugLog).text = log
            dialog.findViewById<ImageButton>(R.id.btnClose).setOnClickListener { dialog.dismiss() }
            dialog.findViewById<View>(R.id.btnCopyLog).setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Debug Log", log))
                Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
            }
            dialog.show()
        }
    }

    private fun loadWifi() {
        binding.swipeRefreshWifi.isRefreshing = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { WifiReader.readWifiNetworks() to null } catch (e: Exception) { null to e.message }
            }
            binding.swipeRefreshWifi.isRefreshing = false
            result.first?.let { list ->
                allWifiNetworks = list.map { it.toWiFiInfo() }
                wifiAdapter.submitList(allWifiNetworks)
                binding.tvWifiCount.text = "共 ${allWifiNetworks.size} 个网络"
            } ?: run {
                Toast.makeText(this@MainActivity, result.second ?: "读取失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun filterWifi(query: String) {
        val filtered = if (query.isBlank()) allWifiNetworks
        else allWifiNetworks.filter { it.ssid.contains(query, ignoreCase = true) }
        wifiAdapter.submitList(filtered)
        binding.tvWifiCount.text = if (query.isBlank()) "共 ${allWifiNetworks.size} 个网络" else "匹配 ${filtered.size} 个"
    }

    // ─── Network Tab ─────────────────────────────────────────────────

    private fun setupNetworkTab() {
        binding.btnToggle.setOnClickListener { performToggle() }
        binding.btnOpenSettings.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        }
        binding.btnRefreshNetwork.setOnClickListener { refreshNetworkInfo() }
    }

    private fun refreshNetworkInfo() {
        lifecycleScope.launch {
            val mode = withContext(Dispatchers.IO) { NetworkModeHelper.getCurrentMode(this@MainActivity) }
            binding.tvCurrentMode.text = mode.label
            binding.tvModeDesc.text = when (mode) { NetworkMode.LTE -> "当前使用 4G LTE 网络"; NetworkMode.NR_5G -> "当前使用 5G NR 网络" }

            val sims = withContext(Dispatchers.IO) { NetworkInfoHelper.getSimInfo(this@MainActivity) }
            updateSignalView(sims)

            val canToggle = ShizukuManager.isAvailable() || NetworkModeHelper.hasRootAccess()
            binding.btnToggle.isEnabled = canToggle
            binding.btnToggle.text = if (canToggle) "切换 4G/5G" else "需要 Shizuku 或 Root"
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateSignalView(sims: List<NetworkInfoHelper.SimInfo>) {
        binding.llSignalContainer.removeAllViews()
        if (sims.isEmpty()) {
            val tv = TextView(this).apply { text = "无 SIM 卡"; textSize = 14f; setTextColor(getColor(R.color.text_secondary)) }
            binding.llSignalContainer.addView(tv); return
        }
        sims.sortedBy { it.slotIndex }.forEachIndexed { idx, sim ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { if (idx > 0) topMargin = dpToPx(8) }
            }
            val barsLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpToPx(10) }
            }
            for (i in 0..3) {
                val bar = View(this).apply {
                    setBackgroundColor(if (i < sim.signalLevel) getColor(R.color.primary) else getColor(R.color.text_hint))
                    layoutParams = LinearLayout.LayoutParams(dpToPx(5), dpToPx(intArrayOf(6, 10, 15, 20)[i])).apply { if (i > 0) marginStart = dpToPx(2) }
                }
                barsLayout.addView(bar)
            }
            row.addView(barsLayout)

            val tvSlot = TextView(this).apply {
                text = "SIM${sim.slotIndex + 1}"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD
                setBackgroundColor(if (sim.isDataSim) getColor(R.color.primary) else getColor(R.color.text_hint))
                setTextColor(0xFFFFFFFF.toInt()); setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpToPx(8) }
            }
            row.addView(tvSlot)

            val tvCarrier = TextView(this).apply {
                text = sim.carrierName ?: "未知"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpToPx(6) }
            }
            row.addView(tvCarrier)

            sim.networkTypeName?.let { netType ->
                val tvNet = TextView(this).apply { text = netType; textSize = 12f; setTextColor(getColor(R.color.text_secondary)); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpToPx(4) } }
                row.addView(tvNet)
            }

            if (sim.isDataSim) {
                val tvMark = TextView(this).apply { text = "★"; textSize = 11f; setTextColor(getColor(R.color.primary)); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpToPx(6) } }
                row.addView(tvMark)
            }

            sim.signalDbm?.let { dbm ->
                val tvDbm = TextView(this).apply { text = "$dbm dBm"; textSize = 11f; setTextColor(getColor(R.color.text_secondary)) }
                row.addView(tvDbm)
            }

            binding.llSignalContainer.addView(row)
        }
    }

    private fun performToggle() {
        binding.btnToggle.isEnabled = false; binding.btnToggle.text = "切换中..."
        lifecycleScope.launch {
            val result = NetworkModeHelper.toggleNetworkMode(this@MainActivity)
            Toast.makeText(this@MainActivity, result.message, if (result.success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            if (!result.success) {
                try { startActivity(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)) } catch (_: Exception) {}
            }
            refreshNetworkInfo()
        }
    }

    // ─── Shizuku ─────────────────────────────────────────────────────

    private fun setupShizukuObservers() {
        lifecycleScope.launch {
            ShizukuManager.status.collect { status ->
                binding.tvShizukuStatus.text = when (status) {
                    ShizukuManager.ShizukuStatus.AUTHORIZED -> "Shizuku: 已授权"
                    ShizukuManager.ShizukuStatus.RUNNING -> "Shizuku: 运行中"
                    ShizukuManager.ShizukuStatus.NOT_RUNNING -> "Shizuku: 未运行"
                    ShizukuManager.ShizukuStatus.NOT_INSTALLED -> "Shizuku: 未安装"
                    ShizukuManager.ShizukuStatus.DETECTING -> "Shizuku: 检测中..."
                }
                binding.tvShizukuStatus.setTextColor(getColor(when (status) {
                    ShizukuManager.ShizukuStatus.AUTHORIZED -> R.color.success
                    ShizukuManager.ShizukuStatus.RUNNING -> R.color.warning
                    else -> R.color.text_secondary
                }))
            }
        }
        lifecycleScope.launch {
            ShizukuManager.error.collect { error ->
                error?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
            }
        }
        binding.tvShizukuStatus.setOnClickListener { ShizukuManager.requestPermission() }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()
}

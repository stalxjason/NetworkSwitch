package io.github.stalxjason.networkswitch.wifi

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.stalxjason.networkswitch.AppTheme
import io.github.stalxjason.networkswitch.R
import io.github.stalxjason.networkswitch.ShizukuHelper
import io.github.stalxjason.networkswitch.databinding.ActivityWifiListBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * 已保存 WiFi 密码列表页
 *
 * 三态展示：加载中 / 列表 / 状态页（未授权、失败、空列表）。
 * 进页面只加载数据；缺授权时由状态页给一个「去授权」按钮，
 * 由用户主动触发授权流程（不在 onCreate 里自动弹窗或跳转）。
 */
class WifiListActivity : AppCompatActivity(), Shizuku.OnRequestPermissionResultListener {

    private lateinit var binding: ActivityWifiListBinding
    private val adapter = WifiListAdapter()
    private var loadedOnce = false

    /** 状态页按钮当前该做的事：授权 / 重试 / 重装 */
    private var statusAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.applyAccent(this)
        super.onCreate(savedInstanceState)
        // targetSdk 35+ 强制 edge-to-edge：内容自己避让系统栏
        enableEdgeToEdge()
        binding = ActivityWifiListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        binding.btnAction.setOnClickListener {
            // 动作由 showStatus 决定：未授权 → 去授权；空列表/失败 → 重试
            val action = statusAction
            if (action != null) action() else handlePermissionAction()
        }

        Shizuku.addRequestPermissionResultListener(this)

        // 进页面只加载，不主动弹授权/跳转：需要授权时状态页会给出「去授权」按钮，
        // 由用户点。自动弹授权和主界面「先讲用途再申请」的做法自相矛盾。
        loadNetworks()
    }

    override fun onResume() {
        super.onResume()
        // 从 Shizuku 授权页返回时自动重载；已有任务在跑就不重复发起
        if (!loadedOnce && loadJob?.isActive != true && ShizukuHelper.isAvailable(this)) {
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
            showStatus(
                R.string.wifi_status_denied,
                R.string.wifi_action_regrant,
                ::handlePermissionAction
            )
        }
    }

    /** 正在跑的加载任务：三处入口（onCreate / onResume / 授权回调）共用，后发先取消 */
    private var loadJob: Job? = null

    private fun loadNetworks() {
        // 同一时刻只允许一个加载：否则重复 binder/Root 调用，且旧结果可能覆盖新结果
        loadJob?.cancel()
        binding.progress.visibility = View.VISIBLE
        binding.recycler.visibility = View.GONE
        binding.statusContainer.visibility = View.GONE

        loadJob = lifecycleScope.launch {
            when (val outcome = WifiPasswordRepository.load(this@WifiListActivity)) {
                is WifiPasswordRepository.Outcome.Ready -> {
                    loadedOnce = true
                    if (outcome.networks.isEmpty()) {
                        showStatus(
                            R.string.wifi_status_empty,
                            R.string.wifi_action_retry,
                            ::loadNetworks
                        )
                    } else {
                        statusAction = null
                        adapter.submit(outcome.networks)
                        binding.recycler.visibility = View.VISIBLE
                    }
                }

                is WifiPasswordRepository.Outcome.NeedsAuthorization ->
                    showStatus(
                        getString(outcome.issue.hintRes()),
                        getString(R.string.wifi_action_grant)
                    ) { handlePermissionAction() }

                is WifiPasswordRepository.Outcome.Failed ->
                    showStatus(
                        getString(outcome.reason.messageRes()),
                        getString(R.string.wifi_action_retry),
                        ::loadNetworks
                    )
            }
            binding.progress.visibility = View.GONE
        }
    }

    @StringRes
    private fun WifiPasswordRepository.AuthorizationIssue.hintRes(): Int = when (this) {
        WifiPasswordRepository.AuthorizationIssue.NOT_INSTALLED -> R.string.shizuku_hint_not_installed
        WifiPasswordRepository.AuthorizationIssue.NOT_RUNNING -> R.string.shizuku_hint_not_running
        WifiPasswordRepository.AuthorizationIssue.NOT_GRANTED -> R.string.shizuku_hint_running
    }

    @StringRes
    private fun WifiPasswordRepository.FailureReason.messageRes(): Int = when (this) {
        WifiPasswordRepository.FailureReason.PRIVILEGED_CALL -> R.string.wifi_status_error
        WifiPasswordRepository.FailureReason.ROOT_DISABLED -> R.string.wifi_status_binder_failed_root_off
        WifiPasswordRepository.FailureReason.ROOT_FAILED -> R.string.wifi_status_binder_failed
    }

    private fun showStatus(
        @StringRes messageRes: Int,
        @StringRes actionRes: Int,
        action: () -> Unit
    ) = showStatus(getString(messageRes), getString(actionRes), action)

    private fun showStatus(message: String, actionText: String, action: () -> Unit) {
        statusAction = action
        binding.statusContainer.visibility = View.VISIBLE
        binding.tvStatus.text = message
        binding.btnAction.text = actionText
    }

    /** 授权链路：Shizuku 在跑 → 请求授权；没跑 → 拉起 Shizuku（未安装则提示） */
    private fun handlePermissionAction() {
        when (ShizukuHelper.getStatus(this)) {
            is ShizukuHelper.Status.Running -> ShizukuHelper.requestPermission()
            else -> launchShizukuApp()
        }
    }

    /** 拉起 Shizuku 应用；未安装或无法启动时提示安装 */
    private fun launchShizukuApp() {
        if (!ShizukuHelper.openShizukuApp(this)) {
            Toast.makeText(this, R.string.toast_shizuku_install_first, Toast.LENGTH_LONG).show()
        }
    }
}

package io.github.stalxjason.networkswitch.wifi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.stalxjason.networkswitch.databinding.ItemWifiBinding

/**
 * WiFi 密码列表适配器
 *
 * 密码默认掩码显示，点「显示」切换明文（避免截屏泄露）；
 * 「复制」写入剪贴板（Android 13+ 系统自带复制提示，不再重复弹 Toast）；
 * 点击卡片展开详细信息（MAC / 频段信道 / 隐藏 / 认证方式）。
 */
class WifiListAdapter :
    ListAdapter<WifiPasswordProvider.WifiEntry, WifiListAdapter.Holder>(DIFF) {

    private val revealedPositions = mutableSetOf<Int>()
    private val expandedPositions = mutableSetOf<Int>()

    fun submit(entries: List<WifiPasswordProvider.WifiEntry>) {
        revealedPositions.clear()
        expandedPositions.clear()
        submitList(entries)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemWifiBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = getItem(position)
        holder.bind(
            entry,
            revealed = position in revealedPositions,
            expanded = position in expandedPositions,
            onToggleReveal = {
                if (position in revealedPositions) revealedPositions.remove(position)
                else revealedPositions.add(position)
                notifyItemChanged(position)
            },
            onToggleExpand = {
                if (position in expandedPositions) expandedPositions.remove(position)
                else expandedPositions.add(position)
                notifyItemChanged(position)
            },
            onCopy = { copyToClipboard(holder.itemView.context, entry) }
        )
    }

    private fun copyToClipboard(context: Context, entry: WifiPasswordProvider.WifiEntry) {
        val password = entry.password ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("WiFi password", password))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "已复制密码", Toast.LENGTH_SHORT).show()
        }
    }

    class Holder(private val binding: ItemWifiBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            entry: WifiPasswordProvider.WifiEntry,
            revealed: Boolean,
            expanded: Boolean,
            onToggleReveal: () -> Unit,
            onToggleExpand: () -> Unit,
            onCopy: () -> Unit
        ) {
            binding.tvSsid.text = entry.ssid
            binding.tvSecurity.text = entry.security

            if (entry.password.isNullOrBlank()) {
                binding.tvPassword.text = "无密码（开放网络或企业网）"
                binding.btnToggleVisible.visibility = View.GONE
                binding.btnCopy.visibility = View.GONE
            } else {
                binding.tvPassword.text =
                    if (revealed) entry.password else "••••••••••••"
                binding.btnToggleVisible.visibility = View.VISIBLE
                binding.btnCopy.visibility = View.VISIBLE
                binding.btnToggleVisible.text = if (revealed) "隐藏" else "显示"
                binding.btnToggleVisible.setOnClickListener { onToggleReveal() }
                binding.btnCopy.setOnClickListener { onCopy() }
            }

            binding.detailsContainer.visibility = if (expanded) View.VISIBLE else View.GONE
            if (expanded) {
                binding.detailBssid.text = entry.bssid ?: "—"
                binding.detailBand.text = when {
                    entry.band != null && entry.channel != null ->
                        "${entry.band} · 信道 ${entry.channel}"
                    entry.band != null -> entry.band
                    else -> "—"
                }
                binding.detailHidden.text = if (entry.isHidden) "是" else "否"
                binding.detailAuth.text = entry.authDetail ?: entry.security
            }

            binding.root.setOnClickListener { onToggleExpand() }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<WifiPasswordProvider.WifiEntry>() {
            override fun areItemsTheSame(
                oldItem: WifiPasswordProvider.WifiEntry,
                newItem: WifiPasswordProvider.WifiEntry
            ): Boolean = oldItem.ssid == newItem.ssid

            override fun areContentsTheSame(
                oldItem: WifiPasswordProvider.WifiEntry,
                newItem: WifiPasswordProvider.WifiEntry
            ): Boolean = oldItem == newItem
        }
    }
}

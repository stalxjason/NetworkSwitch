package io.github.stalxjason.networkswitch.wifi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.stalxjason.networkswitch.R
import io.github.stalxjason.networkswitch.copyTextToClipboard
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
            onToggleReveal = { toggleState(revealedPositions, holder) },
            onToggleExpand = { toggleState(expandedPositions, holder) },
            onCopy = {
                entry.password?.let {
                    // 明文密码标敏感，API 34+ 系统提示更明确
                    holder.itemView.context.copyTextToClipboard(
                        it,
                        holder.itemView.context.getString(R.string.clipboard_wifi_password_label),
                        sensitive = true,
                        toastRes = R.string.toast_copied_password
                    )
                }
            }
        )
    }

    /** 按 holder 当前位置翻转状态（DiffUtil 重排后 position 参数已失效） */
    private fun toggleState(positions: MutableSet<Int>, holder: Holder) {
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        if (position in positions) positions.remove(position) else positions.add(position)
        notifyItemChanged(position)
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
            val ctx = binding.root.context
            binding.tvSsid.text = entry.ssid
            binding.tvSecurity.text = ctx.getString(entry.securityRes)

            if (entry.password.isNullOrBlank()) {
                binding.tvPassword.text = ctx.getString(R.string.password_none)
                binding.btnToggleVisible.visibility = View.GONE
                binding.btnCopy.visibility = View.GONE
            } else {
                binding.tvPassword.text =
                    if (revealed) entry.password else ctx.getString(R.string.password_hidden)
                binding.btnToggleVisible.visibility = View.VISIBLE
                binding.btnCopy.visibility = View.VISIBLE
                binding.btnToggleVisible.text =
                    ctx.getString(if (revealed) R.string.btn_hide else R.string.btn_show)
                binding.btnToggleVisible.setOnClickListener { onToggleReveal() }
                binding.btnCopy.setOnClickListener { onCopy() }
            }

            binding.detailsContainer.visibility = if (expanded) View.VISIBLE else View.GONE
            if (expanded) {
                val bandText = entry.bandRes?.let { ctx.getString(it) }
                binding.detailBssid.text = entry.bssid ?: ctx.getString(R.string.placeholder_dash)
                binding.detailBand.text = when {
                    bandText != null && entry.channel != null ->
                        ctx.getString(R.string.detail_band_channel, bandText, entry.channel)
                    bandText != null -> bandText
                    else -> ctx.getString(R.string.placeholder_dash)
                }
                binding.detailHidden.text =
                    ctx.getString(if (entry.isHidden) R.string.yes else R.string.no)
                binding.detailAuth.text = entry.authDetailRes
                    .joinToString(ctx.getString(R.string.list_separator)) { ctx.getString(it) }
                    .ifEmpty { ctx.getString(entry.securityRes) }
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

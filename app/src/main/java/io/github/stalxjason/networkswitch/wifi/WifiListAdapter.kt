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
 * WiFi 密码列表：密码恒为明文，点「复制」写剪贴板，点卡片展开详情。
 *
 * 列表里只有「复制」一个操作——遮挡/显示这类开关对自用工具没有价值，
 * 反而多一次点击；需要临时隐藏时直接把 App 切走即可。
 */
class WifiListAdapter : ListAdapter<WifiEntry, WifiListAdapter.Holder>(DIFF) {

    /**
     * 展开状态按「SSID + 安全类型」记忆。
     * 只用 SSID 会串：同一 SSID 可能同时存在 WPA2 / WPA3 两份配置（去重键也是这个二元组），
     * 用 position 当 key 则会在 DiffUtil 重排后错位。
     */
    private val expandedKeys = mutableSetOf<Pair<String, WifiSecurity>>()

    /** 刷新列表时保留用户的展开状态（条目被删掉时残留的 key 无害） */
    fun submit(entries: List<WifiEntry>) {
        submitList(entries)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemWifiBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = getItem(position)
        val key = entry.ssid to entry.security
        holder.bind(
            entry,
            expanded = key in expandedKeys,
            onToggleExpand = { toggleExpanded(key, holder) },
            onCopy = {
                entry.passphrase?.let { plain ->
                    holder.itemView.context.copyTextToClipboard(
                        plain,
                        holder.itemView.context.getString(R.string.clipboard_wifi_password_label),
                        toastRes = R.string.toast_copied_password
                    )
                }
            }
        )
    }

    private fun toggleExpanded(key: Pair<String, WifiSecurity>, holder: Holder) {
        if (!expandedKeys.remove(key)) expandedKeys.add(key)
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) notifyItemChanged(position)
    }

    class Holder(private val binding: ItemWifiBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            entry: WifiEntry,
            expanded: Boolean,
            onToggleExpand: () -> Unit,
            onCopy: () -> Unit
        ) {
            val ctx = binding.root.context
            binding.tvSsid.text = entry.ssid
            binding.tvSecurity.text = ctx.getString(entry.security.labelRes)

            val passphrase = entry.passphrase
            if (passphrase.isNullOrBlank()) {
                binding.tvPassword.text = ctx.getString(R.string.password_none)
                binding.btnCopy.visibility = View.GONE
            } else {
                binding.tvPassword.text = passphrase
                binding.btnCopy.visibility = View.VISIBLE
                binding.btnCopy.setOnClickListener { onCopy() }
            }

            binding.detailsContainer.visibility = if (expanded) View.VISIBLE else View.GONE
            if (expanded) {
                binding.detailBssid.text = entry.bssid ?: ctx.getString(R.string.placeholder_dash)

                binding.detailHidden.text =
                    ctx.getString(if (entry.hidden) R.string.yes else R.string.no)

                val separator = ctx.getString(R.string.list_separator)
                binding.detailAuth.text = entry.authDetails
                    .joinToString(separator) { ctx.getString(it.labelRes) }
                    .ifEmpty { ctx.getString(entry.security.labelRes) }
            }

            binding.root.setOnClickListener { onToggleExpand() }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<WifiEntry>() {
            override fun areItemsTheSame(oldItem: WifiEntry, newItem: WifiEntry): Boolean =
                oldItem.ssid == newItem.ssid &&
                    oldItem.security == newItem.security

            override fun areContentsTheSame(oldItem: WifiEntry, newItem: WifiEntry): Boolean =
                oldItem == newItem
        }
    }
}

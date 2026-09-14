package io.github.stalxjason.networkswitch

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import io.github.stalxjason.networkswitch.databinding.ItemIpBinding

/**
 * IP 条目列表适配器
 *
 * 每条一张卡片：标签（加粗 + 主题色圆点）在上，IP 逐行在下（次级色），
 * 待机承载在末行附灰色小字；点按卡片复制该条的 IP。
 *
 * 用 DiffUtil 提交，避免每次刷新整表重绑。
 */
class IpListAdapter :
    ListAdapter<IpHelper.IpEntry, IpListAdapter.Holder>(DIFF) {

    fun submit(newEntries: List<IpHelper.IpEntry>) {
        submitList(newEntries)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemIpBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    class Holder(private val binding: ItemIpBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: IpHelper.IpEntry) {
            val context = binding.root.context
            val textSecondary = ContextCompat.getColor(context, R.color.text_secondary)
            val textHint = ContextCompat.getColor(context, R.color.text_hint)
            val bulletColor = MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorPrimary
            )

            // 标签：加粗 + 主题色圆点
            val label = context.getString(
                R.string.ip_entry_header, entry.label(context), entry.ifaceName
            )
            val labelSsb = SpannableStringBuilder(label)
            labelSsb.setSpan(
                StyleSpan(Typeface.BOLD), 0, label.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            val bulletRadius = (6 * context.resources.displayMetrics.density).toInt()
            labelSsb.setSpan(
                BulletSpan(bulletRadius, bulletColor), 0, label.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            binding.tvIpLabel.text = labelSsb

            // IP：逐行，次级色；待机承载在末行附灰色小字
            val ips = buildList {
                entry.ipv4?.let { add(context.getString(R.string.ip_value_v4, it)) }
                entry.ipv6?.let { add(context.getString(R.string.ip_value_v6, it)) }
            }
            if (ips.isEmpty()) {
                binding.tvIpValues.visibility = View.GONE
                return
            }
            val standby = entry.kind == IpHelper.Kind.MOBILE &&
                    !entry.isActiveData && entry.simSubscriptionId != null

            val ssb = SpannableStringBuilder()
            ips.forEachIndexed { i, item ->
                if (i > 0) ssb.append("\n")
                val start = ssb.length
                ssb.append(context.getString(R.string.ip_value_prefix, item))
                ssb.setSpan(
                    ForegroundColorSpan(textSecondary), start, ssb.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (standby && i == ips.lastIndex) {
                    val note = ssb.length
                    ssb.append(context.getString(R.string.ip_standby_note))
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
            binding.tvIpValues.text = ssb
            binding.tvIpValues.visibility = View.VISIBLE

            binding.root.setOnClickListener {
                context.copyTextToClipboard(
                    ips.joinToString("\n") { it.substringAfter(' ') },
                    context.getString(R.string.ip_copy_label)
                )
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<IpHelper.IpEntry>() {
            override fun areItemsTheSame(
                oldItem: IpHelper.IpEntry,
                newItem: IpHelper.IpEntry
            ): Boolean = oldItem.ifaceName == newItem.ifaceName &&
                oldItem.kind == newItem.kind

            override fun areContentsTheSame(
                oldItem: IpHelper.IpEntry,
                newItem: IpHelper.IpEntry
            ): Boolean = oldItem == newItem
        }
    }
}

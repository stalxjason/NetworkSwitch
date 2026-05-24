package com.network.toolbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class WifiListAdapter : ListAdapter<WiFiInfo, WifiListAdapter.ViewHolder>(DiffCallback()) {

    private val expandedSet = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_wifi, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivSecurity: ImageView = itemView.findViewById(R.id.ivSecurity)
        private val tvSsid: TextView = itemView.findViewById(R.id.tvSsid)
        private val tvPassword: TextView = itemView.findViewById(R.id.tvPassword)
        private val tvSecurityType: TextView = itemView.findViewById(R.id.tvSecurityType)
        private val tvHidden: TextView = itemView.findViewById(R.id.tvHidden)
        private val btnCopy: ImageButton = itemView.findViewById(R.id.btnCopy)
        private val btnToggle: ImageButton = itemView.findViewById(R.id.btnToggle)
        private val tvDetails: TextView = itemView.findViewById(R.id.tvDetails)

        fun bind(wifi: WiFiInfo) {
            tvSsid.text = wifi.ssid
            tvPassword.text = wifi.password.ifEmpty { "无密码" }
            tvSecurityType.text = wifi.securityType.displayName
            ivSecurity.setImageResource(wifi.securityIcon)
            tvHidden.visibility = if (wifi.isHidden) View.VISIBLE else View.GONE

            val expanded = expandedSet.contains(wifi.ssid)
            tvDetails.visibility = if (expanded) View.VISIBLE else View.GONE
            tvDetails.text = buildString {
                if (wifi.bssid.isNotBlank()) appendLine("BSSID: ${wifi.bssid}")
                if (wifi.fqdn.isNotBlank()) appendLine("FQDN: ${wifi.fqdn}")
                if (wifi.creator.isNotBlank()) appendLine("创建者: ${wifi.creator}")
                if (wifi.eapMethod.isNotBlank()) appendLine("EAP: ${wifi.eapMethod}")
                if (wifi.identity.isNotBlank()) appendLine("身份: ${wifi.identity}")
            }.trim()

            itemView.setOnClickListener {
                if (expandedSet.contains(wifi.ssid)) expandedSet.remove(wifi.ssid)
                else expandedSet.add(wifi.ssid)
                notifyItemChanged(adapterPosition)
            }

            btnCopy.setOnClickListener {
                copyPassword(itemView.context, wifi)
            }

            btnToggle.setOnClickListener {
                tvPassword.text = if (tvPassword.tag == "shown") {
                    tvPassword.tag = null
                    wifi.password.ifEmpty { "无密码" }
                } else {
                    tvPassword.tag = "shown"
                    "••••••••"
                }
            }
        }

        private fun copyPassword(context: Context, wifi: WiFiInfo) {
            if (wifi.password.isEmpty()) {
                Toast.makeText(context, "无密码", Toast.LENGTH_SHORT).show()
                return
            }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("WiFi密码", wifi.password))
            Toast.makeText(context, "已复制 ${wifi.ssid} 的密码", Toast.LENGTH_SHORT).show()
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<WiFiInfo>() {
        override fun areItemsTheSame(a: WiFiInfo, b: WiFiInfo) = a.ssid == b.ssid
        override fun areContentsTheSame(a: WiFiInfo, b: WiFiInfo) = a == b
    }
}

package io.github.stalxjason.networkswitch.wifi

import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * 解析 Root 通道读到的 `WifiConfigStore.xml`。
 *
 * 字段格式按真机文件校对过（模拟器 API 36 实测样本）：
 *  - SSID / PreSharedKey 是 `<string>`，值本身带引号且被实体转义（`&quot;`）
 *  - 开放或企业网没有密码：`<null name="PreSharedKey" />`
 *  - 密钥管理**不是** `<int name="KeyMgmt">`，而是
 *    `<byte-array name="AllowedKeyMgmt" num="1">02</byte-array>`，
 *    内容是 BitSet 的字节序列（第 i 位对应 KeyMgmt 常量 i）
 *  - WEP 的四个密钥在 `<string-array name="WEPKeys">` 里
 *  - **没有频率字段**，所以每条网络都拿不到频段/信道（UI 已不再展示）
 *
 * 用 pull parser 而不是正则：能自然处理属性顺序、自闭合标签、CDATA 与实体转义。
 * 输出与平台无关的 [RawNetwork]，由 [WifiEntryMapper] 转成 UI 模型。
 */
object WifiConfigStoreParser {

    private const val TAG = "WifiConfigStore"

    /** 一条网络的原始字段（未做展示层加工） */
    data class RawNetwork(
        val ssid: String,
        val passphrase: String?,
        /** 密钥管理位掩码：第 i 位 = WifiConfiguration.KeyMgmt 里下标为 i 的常量 */
        val keyMgmtBits: Int,
        val bssid: String?,
        val hidden: Boolean
    )

    fun parse(xml: String): List<RawNetwork> = parse(xml, Xml.newPullParser())

    /**
     * 可注入解析器的版本：JVM 单测里 `android.util.Xml` 是桩实现，
     * 测试改用 kxml2 的 KXmlParser，跑的是同一段解析逻辑。
     */
    internal fun parse(xml: String, parser: XmlPullParser): List<RawNetwork> {
        val networks = mutableListOf<RawNetwork>()
        var builder: NetworkBuilder? = null

        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            // 少数工具产出的 XML 会带 UTF-8 BOM，pull parser 会把 \uFEFF 当成非法字符直接抛异常
            parser.setInput(StringReader(xml.removePrefix(BOM)))

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        TAG_NETWORK -> builder = NetworkBuilder()

                        TAG_STRING -> builder?.readString(
                            parser.getAttributeValue(null, ATTR_NAME),
                            parser.nextText()
                        )

                        TAG_NULL -> Unit   // <null name="PreSharedKey" />：字段缺失，跳过

                        TAG_BYTE_ARRAY -> builder?.readByteArray(
                            parser.getAttributeValue(null, ATTR_NAME),
                            parser.nextText()
                        )

                        TAG_STRING_ARRAY -> {
                            val field = parser.getAttributeValue(null, ATTR_NAME)
                            builder?.readStringArray(field, parser)
                        }

                        TAG_INT -> builder?.readInt(
                            parser.getAttributeValue(null, ATTR_NAME),
                            parser.getAttributeValue(null, ATTR_VALUE)
                        )

                        TAG_BOOLEAN -> builder?.readBoolean(
                            parser.getAttributeValue(null, ATTR_NAME),
                            parser.getAttributeValue(null, ATTR_VALUE)
                        )
                    }

                    XmlPullParser.END_TAG -> if (parser.name == TAG_NETWORK) {
                        builder?.build()?.let(networks::add)
                        builder = null
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // 文件被截断（su 输出中断）或格式异常时，保留已解析出的部分比整份丢弃更有用
            Log.w(TAG, "WifiConfigStore.xml 解析中断，已解析 ${networks.size} 条", e)
        }

        return dedupe(networks)
    }

    /**
     * 同一 SSID 可能对应多条不同安全类型的配置（WPA2 与 WPA3 并存），
     * 按「SSID + 密钥管理位」去重，避免把另一套凭据吞掉。
     */
    fun dedupe(networks: List<RawNetwork>): List<RawNetwork> =
        networks
            .distinctBy { it.ssid to it.keyMgmtBits }
            .sortedBy { it.ssid.lowercase() }

    // ── 内部：逐节点累积字段 ───────────────────────────────────────────────

    private class NetworkBuilder {
        private var ssid: String? = null
        private var preSharedKey: String? = null
        private var wepKeys: MutableList<String> = mutableListOf()
        private var keyMgmtBits = 0
        private var bssid: String? = null
        private var hidden = false

        fun readString(field: String?, rawValue: String?) {
            val value = rawValue.stripQuotes()
            when (field) {
                FIELD_SSID -> ssid = value.takeIf { it.isNotEmpty() }
                FIELD_PSK -> preSharedKey = value
                    .takeIf { it.isNotEmpty() && it != NULL_TOKEN }
                FIELD_BSSID -> bssid = value.takeIf { it.isNotEmpty() && it != NULL_TOKEN }
            }
        }

        fun readStringArray(field: String?, parser: XmlPullParser) {
            if (field != FIELD_WEP_KEYS) return
            // 消费 <string-array> 的内部节点，收集所有字符串项
            var event = parser.next()
            while (!(event == XmlPullParser.END_TAG && parser.name == TAG_STRING_ARRAY)) {
                if (event == XmlPullParser.END_DOCUMENT) return
                if (event == XmlPullParser.START_TAG && parser.name == TAG_STRING) {
                    parser.nextText().stripQuotes()
                        .takeIf { it.isNotEmpty() && it != NULL_TOKEN }
                        ?.let(wepKeys::add)
                }
                event = parser.next()
            }
        }

        /**
         * BitSet 以字节序列存储：第 n 个字节的第 b 位 = 全局第 n*8+b 位。
         * 例如开放网络 `01` → bit0 = NONE；WPA_PSK `02` → bit1。
         */
        fun readByteArray(field: String?, hex: String?) {
            if (field != FIELD_ALLOWED_KEY_MGMT || hex.isNullOrEmpty()) return
            var mask = 0
            var byteIndex = 0
            var cursor = 0
            while (cursor + 1 < hex.length) {
                val byte = hex.substring(cursor, cursor + 2).toIntOrNull(16) ?: break
                for (bit in 0 until 8) {
                    if (byte and (1 shl bit) != 0) {
                        val globalBit = byteIndex * 8 + bit
                        if (globalBit < Int.SIZE_BITS) mask = mask or (1 shl globalBit)
                    }
                }
                byteIndex++
                cursor += 2
            }
            keyMgmtBits = mask
        }

        fun readInt(field: String?, rawValue: String?) {
            val value = rawValue?.toIntOrNull() ?: return
            // 少数 ROM 仍用旧的 <int name="KeyMgmt"> 写法，作为兜底保留
            if (field == FIELD_LEGACY_KEY_MGMT) keyMgmtBits = keyMgmtBits or (1 shl value)
        }

        fun readBoolean(field: String?, rawValue: String?) {
            if (field == FIELD_HIDDEN) hidden = rawValue == "true"
        }

        fun build(): RawNetwork? {
            val name = ssid ?: return null
            val passphrase = preSharedKey
                ?: wepKeys.takeIf { it.isNotEmpty() }?.joinToString("\n")
            return RawNetwork(
                ssid = name,
                passphrase = passphrase,
                keyMgmtBits = keyMgmtBits,
                bssid = bssid,
                hidden = hidden
            )
        }
    }

    private const val TAG_NETWORK = "WifiConfiguration"
    private const val TAG_STRING = "string"
    private const val TAG_STRING_ARRAY = "string-array"
    private const val TAG_BYTE_ARRAY = "byte-array"
    private const val TAG_NULL = "null"
    private const val TAG_INT = "int"
    private const val TAG_BOOLEAN = "boolean"
    private const val ATTR_NAME = "name"
    private const val ATTR_VALUE = "value"

    private const val FIELD_SSID = "SSID"
    private const val FIELD_PSK = "PreSharedKey"
    private const val FIELD_BSSID = "BSSID"
    private const val FIELD_WEP_KEYS = "WEPKeys"
    private const val FIELD_ALLOWED_KEY_MGMT = "AllowedKeyMgmt"
    private const val FIELD_LEGACY_KEY_MGMT = "KeyMgmt"
    private const val FIELD_HIDDEN = "HiddenSSID"

    private const val NULL_TOKEN = "null"

    /** UTF-8 BOM，读文件时偶尔会带上 */
    private const val BOM = "\uFEFF"

    private fun String?.stripQuotes(): String = this.orEmpty().trim().removeSurrounding("\"")
}

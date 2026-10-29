package io.github.stalxjason.networkswitch

import io.github.stalxjason.networkswitch.wifi.WifiConfigStoreParser
import io.github.stalxjason.networkswitch.wifi.WifiEntry
import io.github.stalxjason.networkswitch.wifi.WifiEntryMapper
import io.github.stalxjason.networkswitch.wifi.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

/**
 * Root 兜底通道的解析器。
 *
 * 字段格式取自真机样本（`/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml`）：
 * SSID 是带引号且被转义的 `<string>`，密钥管理是 `AllowedKeyMgmt` 的**字节数组**，
 * 没有密码时是 `<null name="PreSharedKey" />`，且文件里**没有频率字段**。
 *
 * 生产代码用 `android.util.Xml`（JVM 单测里是桩），测试注入 kxml2 的 KXmlParser，
 * 跑的是同一段解析逻辑。
 */
class WifiConfigStoreParserTest {

    private fun parse(xml: String): List<WifiEntry> =
        WifiConfigStoreParser.parse(xml, KXmlParser())
            .mapNotNull(WifiEntryMapper::fromRaw)

    /** 按真机字段顺序拼一条网络；keyMgmtHex 是 BitSet 的字节序列 */
    private fun network(
        ssid: String,
        psk: String? = null,
        keyMgmtHex: String = "02",
        hidden: Boolean = false
    ): String = buildString {
        append("<WifiConfiguration>")
        append("<string name=\"SSID\">&quot;$ssid&quot;</string>")
        if (psk == null) {
            append("<null name=\"PreSharedKey\" />")
        } else {
            append("<string name=\"PreSharedKey\">&quot;$psk&quot;</string>")
        }
        append("<null name=\"WEPKeys\" />")
        append("<boolean name=\"HiddenSSID\" value=\"$hidden\" />")
        append("<byte-array name=\"AllowedKeyMgmt\" num=\"1\">$keyMgmtHex</byte-array>")
        append("</WifiConfiguration>")
    }

    private fun store(vararg blocks: String): String =
        "<WifiConfigStoreData><NetworkList>" +
            blocks.joinToString("") { "<Network>$it</Network>" } +
            "</NetworkList></WifiConfigStoreData>"

    @Test
    fun `open network from real layout has no passphrase`() {
        // 与模拟器上的 AndroidWifi 完全一致：PreSharedKey 为 null，AllowedKeyMgmt = 01
        val entry = parse(store(network("AndroidWifi", psk = null, keyMgmtHex = "01"))).single()
        assertEquals("AndroidWifi", entry.ssid)
        assertNull(entry.passphrase)
        assertEquals(WifiSecurity.OPEN, entry.security)
    }

    @Test
    fun `wpa2 network keeps its passphrase`() {
        // 真机样本：LabNet / WPA_PSK，AllowedKeyMgmt = 02
        val entry = parse(store(network("LabNet", psk = "labpass12345", keyMgmtHex = "02"))).single()
        assertEquals("labpass12345", entry.passphrase)
        assertEquals(WifiSecurity.WPA, entry.security)
        assertEquals(listOf(io.github.stalxjason.networkswitch.wifi.WifiAuthDetail.PSK), entry.authDetails)
    }

    @Test
    fun `sae bit in the second byte is decoded`() {
        // SAE = 8 → 第 1 个字节（下标 1）的第 0 位 → "0001"
        val entry = parse(store(network("Wpa3Net", keyMgmtHex = "0001"))).single()
        assertEquals(WifiSecurity.WPA3, entry.security)
    }

    @Test
    fun `hex is read as little-endian bitset`() {
        // 0x03 = NONE + WPA_PSK 都置位，判定优先级应落到 WPA
        val entry = parse(store(network("Dual", psk = "x", keyMgmtHex = "03"))).single()
        assertEquals(WifiSecurity.WPA, entry.security)
    }

    @Test
    fun `xml entities in ssid are decoded by the parser`() {
        val entry = parse(store(network("Cafe &amp; Bar &lt;2F&gt;"))).single()
        assertEquals("Cafe & Bar <2F>", entry.ssid)
    }

    @Test
    fun `hidden flag is carried through`() {
        assertTrue(parse(store(network("Secret", psk = "x", hidden = true))).single().hidden)
    }

    @Test
    fun `wep keys are used when there is no psk`() {
        val block = buildString {
            append("<WifiConfiguration>")
            append("<string name=\"SSID\">&quot;OldWep&quot;</string>")
            append("<null name=\"PreSharedKey\" />")
            append("<string-array name=\"WEPKeys\" num=\"4\">")
            append("<string>&quot;wepkey01&quot;</string>")
            append("<string>null</string>")
            append("</string-array>")
            append("<byte-array name=\"AllowedKeyMgmt\" num=\"1\">01</byte-array>")
            append("</WifiConfiguration>")
        }
        val entry = parse(store(block)).single()
        assertEquals("wepkey01", entry.passphrase)
    }

    @Test
    fun `same ssid with different key management stays two entries`() {
        val entries = parse(
            store(
                network("Dual", psk = "old", keyMgmtHex = "02"),
                network("Dual", psk = "new", keyMgmtHex = "0001")
            )
        )
        assertEquals(2, entries.size)
        assertEquals(
            setOf(WifiSecurity.WPA, WifiSecurity.WPA3),
            entries.map { it.security }.toSet()
        )
    }

    @Test
    fun `identical duplicates collapse`() {
        assertEquals(
            1,
            parse(store(network("Same", psk = "p"), network("Same", psk = "p"))).size
        )
    }

    @Test
    fun `malformed or truncated input keeps complete blocks`() {
        assertEquals(0, parse("").size)
        assertEquals(0, parse("<foo/>").size)

        val truncated = store(network("Good", psk = "p")) +
            "<WifiConfiguration><string name=\"SSID\">\"Broken\"</string>"
        val entries = parse(truncated)
        assertEquals(1, entries.size)
        assertEquals("Good", entries.single().ssid)
    }

    @Test
    fun `entries without ssid are dropped`() {
        assertEquals(
            0,
            parse(store("<WifiConfiguration><null name=\"PreSharedKey\" /></WifiConfiguration>")).size
        )
    }

    @Test
    fun `legacy int key management is still accepted`() {
        // 少数 ROM 仍写 <int name="KeyMgmt" value="1"/>
        val block = "<WifiConfiguration>" +
            "<string name=\"SSID\">&quot;Legacy&quot;</string>" +
            "<int name=\"KeyMgmt\" value=\"1\" />" +
            "</WifiConfiguration>"
        assertEquals(WifiSecurity.WPA, parse(store(block)).single().security)
    }

    /**
     * 用**真机导出的样本**（模拟器 API 36 的 WifiConfigStore.xml，已剔除非必要节点）
     * 跑一遍，确保字段名/编码不是照着实现编出来的。
     */
    @Test
    fun `real device sample parses into expected entries`() {
        val xml = javaClass.getResourceAsStream("/wifi_config_store_sample.xml")!!
            .bufferedReader().use { it.readText() }

        val entries = WifiConfigStoreParser.parse(xml, KXmlParser())
            .mapNotNull(WifiEntryMapper::fromRaw)

        assertEquals(3, entries.size)

        val open = entries.first { it.ssid == "AndroidWifi" }
        assertEquals(WifiSecurity.OPEN, open.security)
        assertNull(open.passphrase)

        val wpa = entries.first { it.ssid == "LabNet" }
        assertEquals(WifiSecurity.WPA, wpa.security)
        assertEquals("labpass12345", wpa.passphrase)

        // 真机文件里 SSID 是被转义的 &quot; / &amp; / &lt;，解析后应当还原
        val cjk = entries.first { it.ssid.startsWith("WiFi") }
        assertEquals("WiFi&测试<1>", cjk.ssid)
        assertEquals(WifiSecurity.WPA, cjk.security)
    }
}

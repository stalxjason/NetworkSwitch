package io.github.stalxjason.networkswitch

import io.github.stalxjason.networkswitch.wifi.WifiPasswordProvider
import org.junit.Assert.*
import org.junit.Test

/**
 * bandOf：频率 → 频段 + 信道号（ITU-R SM.3290）
 *
 * 纯映射函数，改动最容易引入回归（曾把 2.4G ch14 与 5G/6G 边界搞错），故单测覆盖。
 */
class WifiPasswordProviderTest {

    @Test
    fun `unknown frequency yields null band and channel`() {
        for (freq in listOf(0, -1, 2400, 2500, 3000, 5000, 7126)) {
            val (band, channel) = WifiPasswordProvider.bandOf(freq)
            assertNull("freq=$freq", band)
            assertNull("freq=$freq", channel)
        }
    }

    @Test
    fun `2412 maps to 2-4GHz band channel 1`() {
        val (band, channel) = WifiPasswordProvider.bandOf(2412)
        assertEquals(R.string.band_24, band)
        assertEquals(1, channel)
    }

    @Test
    fun `2472 maps to 2-4GHz band channel 13`() {
        val (band, channel) = WifiPasswordProvider.bandOf(2472)
        assertEquals(R.string.band_24, band)
        assertEquals(13, channel)
    }

    @Test
    fun `mid 2-4G band increments by 5MHz per channel`() {
        assertEquals(5, WifiPasswordProvider.bandOf(2432).second)
        assertEquals(10, WifiPasswordProvider.bandOf(2457).second)
    }

    @Test
    fun `2484 is the Japan-only channel 14`() {
        val (band, channel) = WifiPasswordProvider.bandOf(2484)
        assertEquals(R.string.band_24, band)
        assertEquals(14, channel)
    }

    @Test
    fun `5GHz channels start at 34 from 5170MHz`() {
        val (band, channel) = WifiPasswordProvider.bandOf(5170)
        assertEquals(R.string.band_5, band)
        assertEquals(34, channel)
    }

    @Test
    fun `5GHz covers both DFS split ranges`() {
        assertEquals(165, WifiPasswordProvider.bandOf(5825).second)
        assertEquals(170, WifiPasswordProvider.bandOf(5850).second)
        assertEquals(179, WifiPasswordProvider.bandOf(5895).second)
    }

    @Test
    fun `6GHz channel 1 starts at 5925MHz`() {
        val (band, channel) = WifiPasswordProvider.bandOf(5925)
        assertEquals(R.string.band_6, band)
        assertEquals(1, channel)
    }

    @Test
    fun `6GHz upper bound maps to channel 241`() {
        val (band, channel) = WifiPasswordProvider.bandOf(7125)
        assertEquals(R.string.band_6, band)
        assertEquals(241, channel)
    }

    @Test
    fun `all recognized frequencies return a non-null band`() {
        for (freq in listOf(2412, 2484, 5170, 5850, 5925, 7125)) {
            assertNotNull(WifiPasswordProvider.bandOf(freq).first)
            assertNotNull(WifiPasswordProvider.bandOf(freq).second)
        }
    }
}

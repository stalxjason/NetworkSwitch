package io.github.stalxjason.networkswitch

import org.junit.Assert.*
import org.junit.Test

/**
 * signalRatOf：dataNetworkType → 信号强度类型家族
 *
 * 双频注册（4G + 5G SA 共存）时 cellSignalStrengths 顺序不保证，选错家族
 * 就会显示与网络类型不符的信号强度，故把映射单独测。
 * 常量值取自 TelephonyManager：GPRS=1 EDGE=2 UMTS=3 CDMA=4 EVDO_0=5
 * EVDO_A=6 1xRTT=7 HSDPA=8 HSUPA=9 HSPA=10 IDEN=11 EVDO_B=12 LTE=13
 * EHRPD=14 HSPAP=15 GSM=16 TD_SCDMA=17 IWLAN=18 NR=20
 */
class NetworkInfoHelperTest {

    @Test
    fun `NR maps to NR family`() {
        assertEquals(NetworkInfoHelper.SignalRat.NR, NetworkInfoHelper.signalRatOf(20))
    }

    @Test
    fun `LTE and IWLAN share the LTE family`() {
        assertEquals(NetworkInfoHelper.SignalRat.LTE, NetworkInfoHelper.signalRatOf(13))
        assertEquals(NetworkInfoHelper.SignalRat.LTE, NetworkInfoHelper.signalRatOf(18))
    }

    @Test
    fun `TD SCDMA has its own family`() {
        assertEquals(NetworkInfoHelper.SignalRat.TDS, NetworkInfoHelper.signalRatOf(17))
    }

    @Test
    fun `3G wideband families map to WCDMA`() {
        for (type in listOf(3, 8, 9, 10, 14, 15)) {
            assertEquals("type=$type", NetworkInfoHelper.SignalRat.WCDMA, NetworkInfoHelper.signalRatOf(type))
        }
    }

    @Test
    fun `cdma family covers cdma and all evdo variants`() {
        for (type in listOf(4, 5, 6, 12, 7)) {
            assertEquals("type=$type", NetworkInfoHelper.SignalRat.CDMA, NetworkInfoHelper.signalRatOf(type))
        }
    }

    @Test
    fun `gsm family covers 2g legacy types`() {
        for (type in listOf(1, 2, 11, 16)) {
            assertEquals("type=$type", NetworkInfoHelper.SignalRat.GSM, NetworkInfoHelper.signalRatOf(type))
        }
    }

    @Test
    fun `unknown and out-of-range types have no rat`() {
        for (type in listOf(0, -1, 127, 999)) {
            assertNull("type=$type", NetworkInfoHelper.signalRatOf(type))
        }
    }

    @Test
    fun `every family has exactly one class`() {
        assertEquals(6, NetworkInfoHelper.SignalRat.values().size)
    }
}

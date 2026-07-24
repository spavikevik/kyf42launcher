package dev.stefan.kyf42launcher.utils

import android.content.Context
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowWifiInfo

/**
 * dBm -> bar-level mapping for both code paths:
 *  - API 29+ reads NetworkCapabilities#getSignalStrength
 *  - API 26–28 falls back to WifiManager#getConnectionInfo rssi
 * Expected levels follow the AOSP calculateSignalLevel(dbm, 5) mapping
 * (linear between MIN_RSSI -100 and MAX_RSSI -55), clamped to 0..4.
 */
@RunWith(RobolectricTestRunner::class)
class WifiLevelTest {

    // dbm to expected level, spanning all five buckets plus out-of-range ends.
    private val expected = listOf(
        -127 to 0,  // WifiInfo INVALID_RSSI
        -105 to 0,  // below MIN_RSSI
        -100 to 0,  // MIN_RSSI boundary
        -90 to 0,
        -85 to 1,
        -78 to 1,
        -70 to 2,
        -67 to 2,
        -60 to 3,
        -56 to 3,
        -55 to 4,   // MAX_RSSI boundary
        -40 to 4,
        -30 to 4,   // strong signal
    )

    private fun capsWithSignal(dbm: Int): NetworkCapabilities {
        val caps = ShadowNetworkCapabilities.newInstance()
        val f = NetworkCapabilities::class.java.getDeclaredField("mSignalStrength")
        f.isAccessible = true
        f.setInt(caps, dbm)
        return caps
    }

    private fun wifiManagerWithRssi(dbm: Int): WifiManager {
        val wm = RuntimeEnvironment.getApplication()
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = ShadowWifiInfo.newInstance()
        shadowOf(info).setRssi(dbm)
        shadowOf(wm).setConnectionInfo(info)
        return wm
    }

    // ---- API 29+ path: dbm comes from NetworkCapabilities ----

    @Test
    @Config(sdk = [29])
    fun api29_mapsCapabilitiesSignalToLevels() {
        for ((dbm, level) in expected) {
            assertEquals("dbm=$dbm", level, WifiLevel.calculate(capsWithSignal(dbm), null))
        }
    }

    @Test
    @Config(sdk = [29])
    fun api29_unspecifiedSignalShowsFullBars() {
        // Default caps carry SIGNAL_STRENGTH_UNSPECIFIED (no per-network dbm yet).
        val caps = ShadowNetworkCapabilities.newInstance()
        assertEquals(4, WifiLevel.calculate(caps, null))
    }

    @Test
    @Config(sdk = [29])
    fun api29_ignoresWifiManager() {
        // Even with a legacy rssi available, 29+ must read the caps value.
        val wm = wifiManagerWithRssi(-30)
        assertEquals(0, WifiLevel.calculate(capsWithSignal(-100), wm))
    }

    // ---- API 26 path: dbm comes from WifiManager connection info ----

    @Test
    @Config(sdk = [26])
    fun api26_mapsConnectionInfoRssiToLevels() {
        for ((dbm, level) in expected) {
            val caps = ShadowNetworkCapabilities.newInstance()
            assertEquals("dbm=$dbm", level, WifiLevel.calculate(caps, wifiManagerWithRssi(dbm)))
        }
    }

    @Test
    @Config(sdk = [26])
    fun api26_nullWifiManagerShowsNoBars() {
        val caps = ShadowNetworkCapabilities.newInstance()
        assertEquals(0, WifiLevel.calculate(caps, null))
    }

    @Test
    @Config(sdk = [28])
    fun api28_stillUsesLegacyPath() {
        // 28 is the last release before getSignalStrength exists.
        assertEquals(2, WifiLevel.calculate(ShadowNetworkCapabilities.newInstance(), wifiManagerWithRssi(-70)))
    }
}

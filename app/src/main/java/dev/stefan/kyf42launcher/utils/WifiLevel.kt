package dev.stefan.kyf42launcher.utils

import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

object WifiLevel {

    // Same mapping as the deprecated WifiManager.calculateSignalLevel(dbm, 5):
    // linear between MIN and MAX, clamped to 0..4. Owned here so it is
    // identical on every OS release and unit-testable.
    private const val MIN_RSSI = -100
    private const val MAX_RSSI = -55
    private const val LEVELS = 5

    fun calculate(
        caps: NetworkCapabilities,
        wifiManager: WifiManager?
    ): Int {
        val dbm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            caps.signalStrength
        } else {
            // Pre-29 NetworkCapabilities carries no signal strength.
            @Suppress("DEPRECATION")
            wifiManager?.connectionInfo?.rssi
        }

        return when (dbm) {
            NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED -> 4
            null -> 0
            else -> levelFor(dbm)
        }
    }

    /** Maps a wifi RSSI in dBm to a 0..4 bar level. */
    fun levelFor(dbm: Int): Int = when {
        dbm <= MIN_RSSI -> 0
        dbm >= MAX_RSSI -> LEVELS - 1
        else -> ((dbm - MIN_RSSI).toFloat() * (LEVELS - 1) / (MAX_RSSI - MIN_RSSI)).toInt()
    }
}

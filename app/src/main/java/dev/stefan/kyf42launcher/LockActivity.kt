package dev.stefan.kyf42launcher

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * KaiOS/iOS-style lock screen. Not a real keyguard — an over-lock activity
 * (started on screen-off by MainActivity) that shows wallpaper + clock and
 * dismisses on the center key, revealing home underneath.
 */
class LockActivity : AppCompatActivity() {

    private lateinit var lockWifi: ImageView
    private lateinit var lockBattery: TextView
    private var connectivity: ConnectivityManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)

        // Sit above the system keyguard when it's set to None/Swipe.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }

        lockWifi = findViewById(R.id.lockWifi)
        lockBattery = findViewById(R.id.lockBattery)
        val carrier = findViewById<TextView>(R.id.lockCarrier)

        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        carrier.text = when (tm?.simState) {
            TelephonyManager.SIM_STATE_READY -> tm.networkOperatorName ?: ""
            else -> "No SIM"
        }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try { connectivity?.registerDefaultNetworkCallback(netCallback) } catch (_: Exception) {}

        hideSystemBars()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) lockBattery.text = "${level * 100 / scale}%"
        }
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val onWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            runOnUiThread {
                if (onWifi) {
                    val dbm = caps.signalStrength
                    val level = if (dbm == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) 4
                    else @Suppress("DEPRECATION") WifiManager.calculateSignalLevel(dbm, 5).coerceIn(0, 4)
                    lockWifi.setImageResource(WIFI_ICONS[level])
                    lockWifi.visibility = View.VISIBLE
                } else lockWifi.visibility = View.GONE
            }
        }
        override fun onLost(network: Network) {
            runOnUiThread { lockWifi.visibility = View.GONE }
        }
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // Swallow ALL downs; unlock on center key UP. Dismissing on down lets the
    // paired up-event leak to the home window underneath (launched the dialer).
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = true

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> unlock()
        }
        return true
    }

    private fun unlock() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        // If a secure system lock is set, hand off to the REAL keyguard prompt
        // (system PIN). Security stays in the OS; our screen is just the entry.
        if (Build.VERSION.SDK_INT >= 26 && km?.isKeyguardLocked == true && km.isDeviceSecure) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = dismissToHome()
                // onDismissCancelled / onDismissError: stay locked.
            })
        } else {
            dismissToHome()   // no secure lock -> cosmetic dismiss
        }
    }

    private fun dismissToHome() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)   // no flash between lock and home
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* locked: ignore */ }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { connectivity?.unregisterNetworkCallback(netCallback) } catch (_: Exception) {}
    }
}

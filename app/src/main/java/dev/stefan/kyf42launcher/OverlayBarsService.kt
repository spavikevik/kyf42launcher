package dev.stefan.kyf42launcher

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView

/**
 * Draws the launcher's top status bar and bottom bar as system overlays so they
 * appear over other apps too. Hidden while our own launcher/lock is foreground
 * (those draw their own bars). Requires SYSTEM_ALERT_WINDOW.
 */
class OverlayBarsService : Service() {

    private lateinit var wm: WindowManager
    private var topView: View? = null
    private var bottomView: View? = null

    private lateinit var ovWifi: ImageView
    private lateinit var ovSignal: ImageView
    private lateinit var ovCarrier: TextView
    private lateinit var ovBattery: TextView
    private var telephony: TelephonyManager? = null
    private var connectivity: ConnectivityManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addBars()
        wireStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun params(gravity: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { this.gravity = gravity }

    // Native status-bar height, so the opaque top bar reads as a real status bar.
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id)
        else (24 * resources.displayMetrics.density).toInt()
    }

    private fun addBars() {
        val inf = LayoutInflater.from(this)
        val top = inf.inflate(R.layout.overlay_topbar, null)
        val bottom = inf.inflate(R.layout.overlay_bottombar, null)
        ovWifi = top.findViewById(R.id.ovWifi)
        ovSignal = top.findViewById(R.id.ovSignal)
        ovCarrier = top.findViewById(R.id.ovCarrier)
        ovBattery = top.findViewById(R.id.ovBattery)
        try {
            wm.addView(top, params(Gravity.TOP, statusBarHeight()))
            wm.addView(bottom, params(Gravity.BOTTOM, WindowManager.LayoutParams.WRAP_CONTENT))
            topView = top; bottomView = bottom
        } catch (_: Exception) { /* overlay permission missing */ }
    }

    /** Hide over our own launcher/lock (they draw their own bars). */
    fun setHidden(hidden: Boolean) {
        val v = if (hidden) View.GONE else View.VISIBLE
        topView?.visibility = v
        bottomView?.visibility = v
    }

    private fun wireStatus() {
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        telephony = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val sim = telephony?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN
        if (sim == TelephonyManager.SIM_STATE_ABSENT || sim == TelephonyManager.SIM_STATE_UNKNOWN) {
            ovSignal.visibility = View.GONE
            ovCarrier.text = "No SIM"
        } else {
            ovCarrier.text = telephony?.networkOperatorName ?: ""
            try { telephony?.listen(signalListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) }
            catch (_: SecurityException) { ovSignal.visibility = View.GONE }
        }
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try { connectivity?.registerDefaultNetworkCallback(netCallback) } catch (_: Exception) {}
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) ovBattery.text = "${level * 100 / scale}%"
        }
    }

    private val signalListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(s: SignalStrength) {
            ovSignal.setImageResource(CELL_ICONS[s.level.coerceIn(0, 4)])
            ovSignal.visibility = View.VISIBLE
        }
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val onWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            ovWifi.post {
                if (onWifi) {
                    val dbm = caps.signalStrength
                    val level = if (dbm == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) 4
                    else @Suppress("DEPRECATION") WifiManager.calculateSignalLevel(dbm, 5).coerceIn(0, 4)
                    ovWifi.setImageResource(WIFI_ICONS[level])
                    ovWifi.visibility = View.VISIBLE
                } else ovWifi.visibility = View.GONE
            }
        }
        override fun onLost(network: Network) { ovWifi.post { ovWifi.visibility = View.GONE } }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { connectivity?.unregisterNetworkCallback(netCallback) } catch (_: Exception) {}
        telephony?.listen(signalListener, PhoneStateListener.LISTEN_NONE)
        try { topView?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { bottomView?.let { wm.removeView(it) } } catch (_: Exception) {}
    }

    companion object {
        @JvmStatic
        var instance: OverlayBarsService? = null
    }
}

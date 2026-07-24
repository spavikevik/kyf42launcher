package dev.stefan.kyf42launcher

import android.annotation.SuppressLint
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import dev.stefan.kyf42launcher.utils.WifiLevel

/**
 * Draws the launcher's top status bar as a system overlay so it appears over
 * other apps too. Hidden while our own launcher/lock is foreground (those draw
 * their own bars). Requires SYSTEM_ALERT_WINDOW.
 */
class OverlayBarsService : Service() {

    private lateinit var wm: WindowManager
    private var topView: View? = null
    private var probeView: View? = null

    private lateinit var ovWifi: ImageView
    private lateinit var ovSignal: ImageView
    private lateinit var ovCarrier: TextView
    private lateinit var ovBattery: TextView
    private lateinit var ovAppName: TextView
    private var telephony: TelephonyManager? = null
    private var connectivity: ConnectivityManager? = null
    private var wifiManager: WifiManager? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addBars()
        wireStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun params(gravity: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
        ovWifi = top.findViewById(R.id.ovWifi)
        ovSignal = top.findViewById(R.id.ovSignal)
        ovCarrier = top.findViewById(R.id.ovCarrier)
        ovBattery = top.findViewById(R.id.ovBattery)
        ovAppName = top.findViewById(R.id.ovAppName)
        val sbh = statusBarHeight()
        top.findViewById<View>(R.id.ovStatusRow).layoutParams.height = sbh
        val appBar = (19 * resources.displayMetrics.density).toInt()
        // Start hidden: only the poller may show the bar, once it confirms a
        // foreign app is actually foreground (prevents flashes over our home).
        top.visibility = View.GONE
        try {
            // Cover the status bar + the app's action/title bar with the app name.
            wm.addView(top, params(Gravity.TOP, sbh + appBar))
            topView = top
        } catch (_: Exception) { /* overlay permission missing */ }

        // Invisible 1px detector window WITHOUT FLAG_LAYOUT_IN_SCREEN, pinned to the
        // bottom: the window manager lays it out inside the content area, so it sits
        // just above the nav bar normally and drops to the very bottom when the
        // foreground app hides the nav bar (full-immersive). Used by isImmersive().
        // (We key off the nav bar, not the status bar, because the launcher hides the
        // status bar system-wide via policy_control so our overlay owns the top.)
        try {
            val probe = View(this)
            val pp = WindowManager.LayoutParams(
                1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSPARENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.START }
            wm.addView(probe, pp)
            probeView = probe
        } catch (_: Exception) { /* overlay permission missing */ }
    }

    /**
     * Hide over our own launcher/lock (they draw their own bars). Hiding is
     * immediate; showing is deferred to the poller so the bars never flash
     * over our own screens during activity transitions (home -> app launch,
     * lock -> home).
     */
    fun setHidden(hidden: Boolean) {
        handler.removeCallbacks(poller)
        if (hidden) setVisible(false) else poller.run()
    }

    private fun setVisible(visible: Boolean) {
        topView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private val poller = object : Runnable {
        override fun run() {
            currentApp()?.let { pkg ->
                when {
                    // Our own launcher/lock: they draw their own bars.
                    pkg == packageName -> { ovAppName.text = ""; setVisible(false) }
                    // Fullscreen/immersive app (video, games, camera) hides the
                    // system status bar; stay out of its way rather than float on top.
                    isImmersive() -> setVisible(false)
                    else -> { ovAppName.text = labelOf(pkg); setVisible(true) }
                }
            } ?: run {
                // No fresh foreground event: still retreat if the app went immersive.
                if (isImmersive()) setVisible(false)
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    // The foreground app is fullscreen/immersive when it has hidden the system
    // status bar — our overlay window then reports a zero top inset. (API 23+;
    // below that rootWindowInsets is null and we keep showing, as before.)
    // Full-immersive = the foreground app has hidden the nav bar. The bottom probe
    // (no LAYOUT_IN_SCREEN) sits above the nav bar normally and drops to the screen
    // bottom when the nav bar is gone; a near-zero gap means immersive -> hide bars.
    private fun isImmersive(): Boolean {
        val v = probeView ?: return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val real = android.graphics.Point()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(real)
        val gap = real.y - (loc[1] + v.height)
        return gap < navBarHeight() / 2
    }

    private fun navBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id)
        else (48 * resources.displayMetrics.density).toInt()
    }

    private fun currentApp(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 8_000, now)
        var last: String? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.packageName
        }
        return last
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { "" }

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

        // For legacy support
        wifiManager = getSystemService(Context.WIFI_SERVICE) as? WifiManager

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
            // Require validated internet, not just AP association — a captive-portal
            // wifi is linked but offline until sign-in.
            val onWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ovWifi.post {
                if (onWifi) {
                    val level = WifiLevel.calculate(caps, wifiManager)
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
        handler.removeCallbacks(poller)
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { connectivity?.unregisterNetworkCallback(netCallback) } catch (_: Exception) {}
        telephony?.listen(signalListener, PhoneStateListener.LISTEN_NONE)
        try { topView?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { probeView?.let { wm.removeView(it) } } catch (_: Exception) {}
    }

    companion object {
        // Cleared in onDestroy, so the service instance never outlives itself.
        @SuppressLint("StaticFieldLeak")
        @JvmStatic
        var instance: OverlayBarsService? = null
        private const val POLL_MS = 350L
    }
}

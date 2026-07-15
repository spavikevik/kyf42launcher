package dev.stefan.kyf42launcher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** One launchable app. */
data class AppInfo(val label: String, val packageName: String, val icon: Drawable)

private enum class Screen { HOME, GRID }

class MainActivity : AppCompatActivity() {

    private lateinit var homeView: View
    private lateinit var dock: LinearLayout
    private lateinit var appGrid: RecyclerView
    private lateinit var lsk: TextView
    private lateinit var csk: TextView
    private lateinit var rsk: TextView
    private lateinit var ivWifi: ImageView
    private lateinit var ivSignal: ImageView
    private lateinit var tvCarrier: TextView
    private lateinit var tvBattery: TextView
    private var telephony: TelephonyManager? = null
    private var connectivity: ConnectivityManager? = null

    private var screen = Screen.HOME
    private val apps = mutableListOf<AppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        homeView = findViewById(R.id.homeView)
        dock = findViewById(R.id.dock)
        appGrid = findViewById(R.id.appGrid)
        lsk = findViewById(R.id.lsk)
        csk = findViewById(R.id.csk)
        rsk = findViewById(R.id.rsk)
        ivWifi = findViewById(R.id.ivWifi)
        ivSignal = findViewById(R.id.ivSignal)
        tvCarrier = findViewById(R.id.tvCarrier)
        tvBattery = findViewById(R.id.tvBattery)

        appGrid.layoutManager = GridLayoutManager(this, 3)   // 3 columns on 3.4"
        appGrid.adapter = AppAdapter(apps) { app -> launchApp(app) }

        loadApps()
        buildDock()
        showHome()
        setupStatusBar()
        hideSystemBars()
    }

    // --- Favorites dock: default apps + an "All apps" tile ---
    private val dockPkgs = mutableSetOf<String>()

    private fun buildDock() {
        dock.removeAllViews()
        dockPkgs.clear()
        addFavIfResolved(Intent(Intent.ACTION_DIAL))
        addFavIfResolved(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING))
        addFavIfResolved(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS))
        addFavIfResolved(Intent("android.media.action.STILL_IMAGE_CAMERA"))
        addFavIfResolved(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER))
        // Always present: open the full app grid.
        addDockTile(getDrawable(R.drawable.ic_apps), "Apps") { showGrid() }
    }

    private fun addFavIfResolved(intent: Intent) {
        val ri = packageManager.resolveActivity(intent, 0) ?: return
        val ai = ri.activityInfo ?: return
        val pkg = ai.packageName
        if (pkg.isEmpty() || pkg == "android" || pkg == packageName) return  // no real default
        if (!dockPkgs.add(pkg)) return
        val launch = Intent(intent).apply {
            component = ComponentName(pkg, ai.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        addDockTile(ri.loadIcon(packageManager), ri.loadLabel(packageManager).toString()) {
            try { startActivity(launch) } catch (_: Exception) {}
        }
    }

    private fun addDockTile(icon: android.graphics.drawable.Drawable?, label: String, onClick: () -> Unit) {
        val tile = layoutInflater.inflate(R.layout.item_dock, dock, false)
        tile.findViewById<ImageView>(R.id.dockIcon).setImageDrawable(icon)
        tile.findViewById<TextView>(R.id.dockLabel).text = label
        tile.setOnClickListener { onClick() }
        dock.addView(tile)
    }

    // --- Custom status bar: battery + signal + carrier ---
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) tvBattery.text = "${level * 100 / scale}%"
        }
    }

    private val signalListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(s: SignalStrength) {
            ivSignal.setImageResource(CELL_ICONS[s.level.coerceIn(0, 4)])
            ivSignal.visibility = View.VISIBLE
        }
    }

    // ConnectivityManager path: transport + signal work without location permission
    // (unlike WifiManager.getConnectionInfo(), which Android 10 redacts).
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val onWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            runOnUiThread {
                if (onWifi) {
                    ivWifi.setImageResource(WIFI_ICONS[wifiLevel(caps)])
                    ivWifi.visibility = View.VISIBLE
                } else ivWifi.visibility = View.GONE
            }
        }
        override fun onLost(network: Network) {
            runOnUiThread { ivWifi.visibility = View.GONE }
        }
    }

    private fun wifiLevel(caps: NetworkCapabilities): Int {
        val dbm = caps.signalStrength   // API 29; may be SIGNAL_STRENGTH_UNSPECIFIED
        if (dbm == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) return 4
        @Suppress("DEPRECATION")
        return WifiManager.calculateSignalLevel(dbm, 5).coerceIn(0, 4)
    }

    companion object {
        private val WIFI_ICONS = intArrayOf(
            R.drawable.ic_wifi_0, R.drawable.ic_wifi_1, R.drawable.ic_wifi_2,
            R.drawable.ic_wifi_3, R.drawable.ic_wifi_4
        )
        private val CELL_ICONS = intArrayOf(
            R.drawable.ic_cell_0, R.drawable.ic_cell_1, R.drawable.ic_cell_2,
            R.drawable.ic_cell_3, R.drawable.ic_cell_4
        )
    }

    private fun setupStatusBar() {
        // Sticky broadcast returns current battery immediately on register.
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // Cellular: show "No SIM" when no card, else carrier + live signal bars.
        telephony = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val sim = telephony?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN
        if (sim == TelephonyManager.SIM_STATE_ABSENT || sim == TelephonyManager.SIM_STATE_UNKNOWN) {
            ivSignal.visibility = View.GONE
            tvCarrier.text = "No SIM"
        } else {
            tvCarrier.text = telephony?.networkOperatorName ?: ""
            try {
                telephony?.listen(signalListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
            } catch (e: SecurityException) {
                ivSignal.visibility = View.GONE   // no READ_PHONE_STATE -> hide signal
            }
        }

        // WiFi: bars when connected, blank otherwise (via default-network callback).
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        connectivity?.registerDefaultNetworkCallback(netCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { connectivity?.unregisterNetworkCallback(netCallback) } catch (_: Exception) {}
        telephony?.listen(signalListener, PhoneStateListener.LISTEN_NONE)
    }

    // Hide BOTH system bars (nav + status). We draw our own status bar (topBar).
    // Immersive-sticky so they stay hidden. Re-assert whenever we regain focus.
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

    // --- CORE (yours to tune): enumerate launchable apps ---
    private fun loadApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = packageManager.queryIntentActivities(intent, 0)
        apps.clear()
        for (ri in resolved) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName) continue          // hide ourselves
            apps.add(
                AppInfo(
                    label = ri.loadLabel(packageManager).toString(),
                    packageName = pkg,
                    icon = ri.loadIcon(packageManager)
                )
            )
        }
        apps.sortBy { it.label.lowercase() }
        appGrid.adapter?.notifyDataSetChanged()
    }

    private fun launchApp(app: AppInfo) {
        packageManager.getLaunchIntentForPackage(app.packageName)?.let { startActivity(it) }
    }

    // --- Screen state (KaiOS: home clock  <->  app grid) ---
    private fun showHome() {
        screen = Screen.HOME
        homeView.visibility = View.VISIBLE
        appGrid.visibility = View.GONE
        lsk.text = ""
        csk.text = ""
        rsk.text = ""
        dock.post { dock.getChildAt(0)?.requestFocus() }
    }

    private fun showGrid() {
        screen = Screen.GRID
        homeView.visibility = View.GONE
        appGrid.visibility = View.VISIBLE
        lsk.text = ""
        csk.text = "SELECT"
        rsk.text = "Options"
        appGrid.post {
            (appGrid.layoutManager?.findViewByPosition(0))?.requestFocus()
                ?: appGrid.requestFocus()
        }
    }

    // --- KYF42 physical keys (see matrix_keypad.kl) ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (screen) {
            Screen.HOME -> when (keyCode) {
                // Up from the dock opens the full app grid, KaiOS-style.
                // Everything else (left/right/center) falls through to the focus
                // system so the D-pad can traverse the dock and center-click a tile.
                KeyEvent.KEYCODE_DPAD_UP -> { showGrid(); return true }
            }
            Screen.GRID -> when (keyCode) {
                // F2 = right soft key = Options (TODO: app info / uninstall menu)
                KeyEvent.KEYCODE_F2 -> return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // BACK: grid -> home; on home, swallow so we never leave the launcher.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen == Screen.GRID) showHome()
        // else: stay on home (do nothing)
    }
}

/** Grid adapter. DPAD_CENTER on a focused cell fires its click -> launch. */
private class AppAdapter(
    private val apps: List<AppInfo>,
    private val onLaunch: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.label.text = app.label
        holder.itemView.setOnClickListener { onLaunch(app) }
    }

    override fun getItemCount() = apps.size
}

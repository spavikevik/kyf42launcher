package dev.stefan.kyf42launcher

import android.app.KeyguardManager
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
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.graphics.drawable.toBitmap
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** One launchable app. */
data class AppInfo(val label: String, val packageName: String, val icon: Drawable)

/** Status-bar level icons (0..4), shared by home and lock screens. */
internal val WIFI_ICONS = intArrayOf(
    R.drawable.ic_wifi_0, R.drawable.ic_wifi_1, R.drawable.ic_wifi_2,
    R.drawable.ic_wifi_3, R.drawable.ic_wifi_4
)
internal val CELL_ICONS = intArrayOf(
    R.drawable.ic_cell_0, R.drawable.ic_cell_1, R.drawable.ic_cell_2,
    R.drawable.ic_cell_3, R.drawable.ic_cell_4
)

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
    private lateinit var widgets: HomeWidgets

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

        appGrid.layoutManager = GridLayoutManager(this, 4)   // iOS-style 4 columns
        appGrid.adapter = AppAdapter(apps) { app -> launchApp(app) }

        widgets = HomeWidgets(
            this,
            findViewById(R.id.infoCard),
            findViewById(R.id.rowWeather), findViewById(R.id.wxIcon),
            findViewById(R.id.wxTemp), findViewById(R.id.wxCond),
            findViewById(R.id.rowEvent), findViewById(R.id.evText),
            findViewById(R.id.rowAlarm), findViewById(R.id.alText),
        )

        loadApps()
        buildDock()
        showHome()
        setupStatusBar()
        widgets.refresh()
        hideSystemBars()

        // Screen off -> pre-arm our (cosmetic) lock screen for non-secure devices.
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        applyLockWallpaperOnce()
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Only run our cosmetic lock when there's NO secure system lock.
            // A secure keyguard owns the top layer; our activity can't occlude it,
            // so we defer to it (it's the real security) instead of fighting.
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (km?.isDeviceSecure == true) return
            try {
                startActivity(
                    Intent(this@MainActivity, LockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) { /* BAL denied: system keyguard handles it */ }
        }
    }

    // Match the system keyguard to our theme so a secure lock still looks like ours.
    private fun applyLockWallpaperOnce() {
        val prefs = getSharedPreferences("kyf42", Context.MODE_PRIVATE)
        if (prefs.getBoolean("lock_wp_set", false)) return
        try {
            val wm = android.app.WallpaperManager.getInstance(this)
            val bmp = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.wallpaper)
            wm.setBitmap(bmp, null, true, android.app.WallpaperManager.FLAG_LOCK)
            prefs.edit().putBoolean("lock_wp_set", true).apply()
        } catch (_: Exception) { /* SET_WALLPAPER unavailable: skip */ }
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
        addDockTile(squircle(ri.loadIcon(packageManager)), ri.loadLabel(packageManager).toString()) {
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
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
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

    override fun onResume() {
        super.onResume()
        hideSystemBars()   // re-assert before the window is shown (lock dismissal)
        if (::widgets.isInitialized) widgets.refresh()
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
                    icon = squircle(ri.loadIcon(packageManager))
                )
            )
        }
        apps.sortBy { it.label.lowercase() }
        appGrid.adapter?.notifyDataSetChanged()
    }

    private fun launchApp(app: AppInfo) {
        packageManager.getLaunchIntentForPackage(app.packageName)?.let { startActivity(it) }
    }

    // Mask an app icon into an iOS-style squircle (rounded square).
    private fun squircle(d: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable {
        val px = (56 * resources.displayMetrics.density).toInt().coerceAtLeast(48)
        val bmp = d.toBitmap(px, px)
        return RoundedBitmapDrawableFactory.create(resources, bmp).apply {
            cornerRadius = px * 0.225f
        }
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
        // iOS-like: focused cell scales up and lifts above its neighbours.
        v.setOnFocusChangeListener { view, hasFocus ->
            val s = if (hasFocus) 1.12f else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(110).start()
            view.z = if (hasFocus) 8f else 0f
        }
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

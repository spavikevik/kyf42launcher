package dev.stefan.kyf42launcher

import android.annotation.SuppressLint
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Uri
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.graphics.drawable.toBitmap
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.stefan.kyf42launcher.utils.WifiLevel

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

private enum class Screen { HOME, GRID, NOTIF, CONTROL, SETTINGS }

class MainActivity : AppCompatActivity() {

    private lateinit var homeView: View
    private lateinit var dock: LinearLayout
    private lateinit var carousel: LinearLayout
    private lateinit var appGrid: RecyclerView
    private lateinit var notifPanel: View
    private lateinit var notifList: RecyclerView
    private lateinit var notifEmpty: View
    private lateinit var controlPanel: View
    private lateinit var control: ControlCenter
    private lateinit var settingsPanel: View
    private lateinit var settingsRows: LinearLayout
    private val notifData = mutableListOf<StatusBarNotification>()
    private lateinit var lsk: TextView
    private lateinit var csk: TextView
    private lateinit var rsk: TextView
    private lateinit var ivWifi: ImageView
    private lateinit var ivSignal: ImageView
    private lateinit var tvCarrier: TextView
    private lateinit var tvBattery: TextView
    private var telephony: TelephonyManager? = null
    private var connectivity: ConnectivityManager? = null
    private var wifiManager: WifiManager? = null
    private lateinit var widgets: HomeWidgets

    private lateinit var gridContainer: View
    private lateinit var gridSearch: EditText

    private var screen = Screen.HOME
    private val apps = mutableListOf<AppInfo>()        // all launchable apps
    private val gridApps = mutableListOf<AppInfo>()    // currently shown (search-filtered)
    private val keys by lazy { KeyBindings(prefs) }    // raw keycode -> semantic role

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val colorTheme = Themes.apply(this)   // accent overlay, before inflation
        migrateShortcutPrefs()
        // First run: hand off to the setup wizard once home has drawn behind it.
        if (!prefs.getBoolean("setup_done", false)) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.rootMain).setBackgroundResource(colorTheme.wallpaperRes)

        homeView = findViewById(R.id.homeView)
        dock = findViewById(R.id.dock)
        carousel = findViewById(R.id.carousel)
        appGrid = findViewById(R.id.appGrid)
        lsk = findViewById(R.id.lsk)
        csk = findViewById(R.id.csk)
        rsk = findViewById(R.id.rsk)
        ivWifi = findViewById(R.id.ivWifi)
        ivSignal = findViewById(R.id.ivSignal)
        tvCarrier = findViewById(R.id.tvCarrier)
        tvBattery = findViewById(R.id.tvBattery)

        gridContainer = findViewById(R.id.gridContainer)
        gridSearch = findViewById(R.id.gridSearch)
        gridSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                applySearch(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        // Show the keypad IME only while the search field holds focus.
        gridSearch.setOnFocusChangeListener { v, hasFocus -> showIme(v, hasFocus) }
        // Down from the field jumps into the results.
        gridSearch.setOnEditorActionListener { _, _, _ ->
            appGrid.layoutManager?.findViewByPosition(0)?.requestFocus(); true
        }
        appGrid.layoutManager = GridLayoutManager(this, 3)   // 3 columns, roomy spacing
        appGrid.adapter = AppAdapter(gridApps) { app -> launchApp(app) }

        notifPanel = findViewById(R.id.notifPanel)
        notifList = findViewById(R.id.notifList)
        notifEmpty = findViewById(R.id.notifEmpty)
        notifList.layoutManager = LinearLayoutManager(this)
        notifList.adapter = NotifAdapter(this, notifData) { sbn -> openNotif(sbn) }
        LockListenerService.onChange = { runOnUiThread { onNotifsChanged() } }

        controlPanel = findViewById(R.id.controlPanel)
        control = ControlCenter(this, findViewById(R.id.ctrlGrid)) { showSettings() }
        control.build()
        settingsPanel = findViewById(R.id.settingsPanel)
        settingsRows = findViewById(R.id.settingsRows)

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

        // App install/remove/update -> refresh the grid live (data scheme is required
        // for the PACKAGE_* actions to be delivered).
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        applyLockWallpaperOnce()
        updateOverlayBars()
    }

    // Start/stop the system-wide bars per the setting + overlay permission.
    private fun updateOverlayBars() {
        val on = prefs.getBoolean("overlay_bars", true) &&
            android.provider.Settings.canDrawOverlays(this)
        val intent = Intent(this, OverlayBarsService::class.java)
        if (on) startService(intent) else stopService(intent)
        setStockStatusBarHidden(on)
    }

    // Hide the system status bar over other apps (semi-immersive), so our overlay
    // bar is the only top bar — otherwise both stack and their status rows collide.
    // Needs WRITE_SECURE_SETTINGS. Cleared (stock bar returns) when the feature is off.
    private fun setStockStatusBarHidden(hidden: Boolean) = putSecureGlobal {
        android.provider.Settings.Global.putString(
            contentResolver, "policy_control", if (hidden) "immersive.status=*" else null
        )
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Arm our lock screen on every screen-off. It shows over the keyguard
            // (setShowWhenLocked); on a secure device, pressing OK raises the real
            // system PIN via requestDismissKeyguard. Process stays alive via the
            // NotificationListener anchor so this receiver survives.
            try {
                startActivity(
                    Intent(this@MainActivity, LockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) { /* BAL denied: system keyguard handles it */ }
        }
    }

    // Keep the app grid in sync when apps are installed/removed/updated. loadApps()
    // only runs at startup, so without this a freshly installed app (e.g. a new SMS
    // client) stays invisible until the launcher restarts. Context-registered so it
    // receives the PACKAGE_* broadcasts, which manifest receivers no longer get.
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Our own updates don't change the launchable set (we hide ourselves).
            if (intent.data?.schemeSpecificPart == packageName) return
            loadApps()
            // loadApps() resets the grid to the full list; restore an active search
            // filter so an install landing mid-search doesn't clear the query view.
            val query = gridSearch.text?.toString().orEmpty()
            if (query.isNotEmpty()) applySearch(query)
        }
    }

    // Match the system keyguard to our theme so a secure lock still looks like ours.
    private fun applyLockWallpaperOnce() {
        val prefs = getSharedPreferences("kyf42", Context.MODE_PRIVATE)
        if (prefs.getBoolean("lock_wp_set", false)) return
        try {
            val wm = android.app.WallpaperManager.getInstance(this)
            val bmp = android.graphics.BitmapFactory.decodeResource(
                resources, Themes.current(this).wallpaperRes
            )
            wm.setBitmap(bmp, null, true, android.app.WallpaperManager.FLAG_LOCK)
            prefs.edit().putBoolean("lock_wp_set", true).apply()
        } catch (_: Exception) { /* SET_WALLPAPER unavailable: skip */ }
    }

    // --- Favorites dock: default apps + an "All apps" tile ---
    private val dockPkgs = mutableSetOf<String>()
    private val DOCK_MAX = 5
    private val REQ_PICK_CONTACT = 42

    private fun buildDock() {
        dock.removeAllViews()
        dockPkgs.clear()
        val pinned = loadDockPkgs()
        if (pinned.isEmpty()) {
            // Defaults, resolved from system: dialer / messaging / contacts / camera / browser.
            addFavIfResolved(Intent(Intent.ACTION_DIAL))
            addFavIfResolved(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING))
            addFavIfResolved(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS))
            addFavIfResolved(Intent("android.media.action.STILL_IMAGE_CAMERA"))
            addFavIfResolved(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER))
        } else {
            pinned.forEach { addPinnedApp(it) }
        }
        // Always present: open the full app grid.
        addDockTile(getDrawable(R.drawable.ic_apps), "Apps") { showGrid() }
    }

    private fun addPinnedApp(pkg: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        if (!dockPkgs.add(pkg)) return
        val icon = try { squircle(packageManager.getApplicationIcon(pkg)) } catch (_: Exception) { null }
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
        addDockTile(icon, label) { try { startActivity(launch) } catch (_: Exception) {} }
    }

    private val prefs get() = getSharedPreferences("kyf42", Context.MODE_PRIVATE)
    private fun loadDockPkgs(): List<String> =
        prefs.getString("dock_pkgs", "")!!.split("\n").filter { it.isNotBlank() }
    private fun saveDockPkgs(list: List<String>) =
        prefs.edit().putString("dock_pkgs", list.joinToString("\n")).apply()

    // Pin/unpin an app; seeds from the current (default) dock on first change.
    private fun toggleDock(pkg: String) {
        val base = loadDockPkgs().ifEmpty { dockPkgs.toList() }.toMutableList()
        when {
            base.contains(pkg) -> base.remove(pkg)
            base.size >= DOCK_MAX -> { showReplaceChooser(pkg); return }
            else -> base.add(pkg)
        }
        saveDockPkgs(base)
        buildDock()
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
            val onWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && hasInternet(caps)
            runOnUiThread {
                if (onWifi) {
                    ivWifi.setImageResource(WIFI_ICONS[WifiLevel.calculate(caps, wifiManager)])
                    ivWifi.visibility = View.VISIBLE
                } else ivWifi.visibility = View.GONE
            }
        }
        override fun onLost(network: Network) {
            runOnUiThread { ivWifi.visibility = View.GONE }
        }
    }

    // Real internet, not just AP association: a captive-portal wifi (common public
    // hotspot) is linked but has no connectivity until you sign in.
    private fun hasInternet(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)


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

        // For legacy support
        wifiManager = getSystemService(Context.WIFI_SERVICE) as? WifiManager

        // WiFi: bars when connected, blank otherwise (via default-network callback).
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        connectivity?.registerDefaultNetworkCallback(netCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        LockListenerService.onChange = null
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(packageReceiver) } catch (_: Exception) {}
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
        // Recents change while we're away — keep the carousel current.
        if (screen == Screen.HOME && ::carousel.isInitialized) buildCarousel()
        OverlayBarsService.instance?.setHidden(true)   // we draw our own bars
    }

    // onStop (not onPause): it fires only after the covering activity has
    // actually drawn, so the bars never flash over the still-visible home
    // while a slow app is cold-starting.
    override fun onStop() {
        super.onStop()
        OverlayBarsService.instance?.setHidden(false)  // another app -> show overlay bars
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
        gridApps.clear()
        gridApps.addAll(apps)
        appGrid.adapter?.notifyDataSetChanged()
    }

    // Filter the grid by a substring of the app label (works for kana + latin).
    private fun applySearch(query: String) {
        gridApps.clear()
        if (query.isBlank()) {
            gridApps.addAll(apps)
        } else {
            gridApps.addAll(apps.filter { it.label.contains(query.trim(), ignoreCase = true) })
        }
        appGrid.adapter?.notifyDataSetChanged()
    }

    private fun showIme(v: View, show: Boolean) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        if (show) imm?.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        else imm?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun launchApp(app: AppInfo) {
        packageManager.getLaunchIntentForPackage(app.packageName)?.let { startActivity(it) }
    }

    // The grid app currently holding D-pad focus.
    private fun focusedApp(): AppInfo? {
        val fv = appGrid.findFocus() ?: return null
        val pos = appGrid.getChildAdapterPosition(fv)
        return gridApps.getOrNull(pos)
    }

    private fun showOptions(app: AppInfo) {
        val view = layoutInflater.inflate(R.layout.dialog_options, null)
        view.findViewById<TextView>(R.id.optTitle).text = app.label
        val pinRow = view.findViewById<TextView>(R.id.optPin)
        val pinned = dockPkgs.contains(app.packageName)   // what's currently in the dock
        pinRow.text = if (pinned) "Remove from dock" else "Pin to dock"
        val dialog = makeSheet(view)
        view.findViewById<View>(R.id.optInfo).setOnClickListener {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.optUninstall).setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            dialog.dismiss()
        }
        pinRow.setOnClickListener { toggleDock(app.packageName); dialog.dismiss() }
        view.findViewById<View>(R.id.optCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
        pinRow.requestFocus()
    }

    // Bottom action-sheet dialog wrapper.
    private fun makeSheet(view: View): android.app.Dialog =
        android.app.Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(view)
            window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setGravity(android.view.Gravity.BOTTOM)
                setLayout(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT
                )
                decorView.setPadding(18, 0, 18, 18)
            }
        }

    private fun labelFor(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    // A focusable sheet row built in code (for dynamic lists).
    private fun sheetRow(text: CharSequence, accent: Boolean = false): TextView {
        val pad = (14 * resources.displayMetrics.density).toInt()
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(pad, pad, pad, pad)
            isFocusable = true
            setBackgroundResource(R.drawable.opt_row_bg)
            setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    this@MainActivity,
                    if (accent) R.color.text_secondary else R.color.text_primary
                )
            )
            textSize = 15f
            this.text = text
        }
    }

    // Dock full: let the user choose which pinned app to replace.
    private fun showReplaceChooser(newPkg: String) {
        val current = loadDockPkgs().ifEmpty { dockPkgs.toList() }
        val view = layoutInflater.inflate(R.layout.dialog_list, null)
        view.findViewById<TextView>(R.id.listTitle).text = "Dock full — replace which?"
        val rows = view.findViewById<LinearLayout>(R.id.listRows)
        val dialog = makeSheet(view)
        current.forEach { pkg ->
            val row = sheetRow(labelFor(pkg))
            row.setOnClickListener {
                val list = current.toMutableList()
                list.remove(pkg); list.add(newPkg)
                saveDockPkgs(list); buildDock(); dialog.dismiss()
            }
            rows.addView(row)
        }
        val cancel = sheetRow("Cancel", accent = true)
        cancel.setOnClickListener { dialog.dismiss() }
        rows.addView(cancel)
        dialog.show()
        rows.getChildAt(0)?.requestFocus()
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
        gridContainer.visibility = View.GONE
        notifPanel.visibility = View.GONE
        controlPanel.visibility = View.GONE
        settingsPanel.visibility = View.GONE
        val n = LockListenerService.instance?.current()?.size ?: 0
        lsk.text = if (n > 0) "● $n Alerts" else "Alerts"
        csk.text = ""
        rsk.text = "Controls"
        buildCarousel()
        dock.post { dock.getChildAt(0)?.requestFocus() }
    }

    private fun showGrid() {
        screen = Screen.GRID
        homeView.visibility = View.GONE
        notifPanel.visibility = View.GONE
        controlPanel.visibility = View.GONE
        settingsPanel.visibility = View.GONE
        gridContainer.visibility = View.VISIBLE
        // Reset any prior search (without triggering the IME).
        if (gridSearch.text.isNotEmpty()) gridSearch.setText("")
        gridApps.clear(); gridApps.addAll(apps)
        appGrid.adapter?.notifyDataSetChanged()
        appGrid.scheduleLayoutAnimation()   // replay the cascade on each open
        lsk.text = "Search"
        csk.text = "SELECT"
        rsk.text = "Options"
        appGrid.post {
            (appGrid.layoutManager?.findViewByPosition(0))?.requestFocus()
                ?: appGrid.requestFocus()
        }
    }

    // --- Notifications center ---
    private fun showNotifications() {
        screen = Screen.NOTIF
        homeView.visibility = View.GONE
        gridContainer.visibility = View.GONE
        controlPanel.visibility = View.GONE
        settingsPanel.visibility = View.GONE
        notifPanel.visibility = View.VISIBLE
        lsk.text = "Clear all"
        csk.text = "OPEN"
        rsk.text = ""
        refreshNotifs()
        notifList.post {
            notifList.layoutManager?.findViewByPosition(0)?.requestFocus()
                ?: notifPanel.requestFocus()
        }
    }

    private fun refreshNotifs() {
        notifData.clear()
        notifData.addAll(LockListenerService.instance?.current() ?: emptyList())
        notifList.adapter?.notifyDataSetChanged()
        val empty = notifData.isEmpty()
        notifEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        notifList.visibility = if (empty) View.GONE else View.VISIBLE
        // No actions when there's nothing to act on.
        lsk.text = if (empty) "" else "Clear all"
        csk.text = if (empty) "" else "OPEN"
    }

    private fun onNotifsChanged() {
        if (screen == Screen.NOTIF) refreshNotifs()
        if (screen == Screen.HOME) {
            val n = LockListenerService.instance?.current()?.size ?: 0
            lsk.text = if (n > 0) "● $n Alerts" else "Alerts"
        }
    }

    private fun openNotif(sbn: StatusBarNotification) {
        try { sbn.notification.contentIntent?.send() } catch (_: Exception) {}
        try { LockListenerService.instance?.cancelNotification(sbn.key) } catch (_: Exception) {}
        showHome()
    }

    // --- Control center ---
    private fun showControl() {
        screen = Screen.CONTROL
        homeView.visibility = View.GONE
        gridContainer.visibility = View.GONE
        notifPanel.visibility = View.GONE
        settingsPanel.visibility = View.GONE
        controlPanel.visibility = View.VISIBLE
        lsk.text = ""; csk.text = ""; rsk.text = ""
        control.refresh()
        controlPanel.post { control.firstView()?.requestFocus() }
    }

    // --- Settings ---
    private fun showSettings() {
        screen = Screen.SETTINGS
        homeView.visibility = View.GONE
        gridContainer.visibility = View.GONE
        notifPanel.visibility = View.GONE
        controlPanel.visibility = View.GONE
        settingsPanel.visibility = View.GONE
        settingsPanel.visibility = View.VISIBLE
        lsk.text = ""; csk.text = ""; rsk.text = ""
        buildSettings()
        settingsRows.post {
            (0 until settingsRows.childCount).map { settingsRows.getChildAt(it) }
                .firstOrNull { it.isFocusable }?.requestFocus()
        }
    }

    private fun buildSettings() {
        settingsRows.removeAllViews()
        addSettingsHeader("Shortcuts")
        for (role in listOf(LauncherKey.SHORTCUT_A, LauncherKey.SHORTCUT_B)) {
            val target = prefs.getString(shortcutPrefKey(role), shortcutDefault(role))
            addSettingsRow(role.label, targetLabel(target)) { assignShortcut(role) }
        }
        addSettingsHeader("Speed dial")
        for (d in 2..9) {
            val entry = prefs.getString("sd_$d", null)
            addSettingsRow("Key $d", entry?.substringAfter("|") ?: "Not set") { assignSpeedDial(d) }
        }
        addSettingsHeader("Input — key bindings")
        addSettingsRow("Preset", keys.activePresetName()) { showPresetPicker() }
        for (role in LauncherKey.values()) {
            val code = keys.code(role)
            addSettingsRow(role.label, if (code == null) "Not bound" else KeyBindings.keyName(code)) {
                showKeyCapture(role)
            }
        }
        addSettingsHeader("Appearance")
        val cur = Themes.current(this)
        addSettingsRow("Theme", cur.label) { showThemePicker() }
        addSettingsHeader("Dock")
        addSettingsRow("Reset dock to defaults", null) {
            saveDockPkgs(emptyList()); buildDock(); buildSettings()
        }
        addSettingsHeader("System")
        addSettingsRow("Bars over other apps", onOffStr(prefs.getBoolean("overlay_bars", true))) {
            val now = !prefs.getBoolean("overlay_bars", true)
            prefs.edit().putBoolean("overlay_bars", now).apply()
            if (now && !android.provider.Settings.canDrawOverlays(this)) {
                toast("Grant 'Draw over apps' first")
                openSetting(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            }
            updateOverlayBars()
            OverlayBarsService.instance?.setHidden(true)  // launcher is foreground now
            buildSettings()
        }
        addSettingsRow("Notification access", null) { openSetting(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        addSettingsRow("Default home app", null) { openSetting(android.provider.Settings.ACTION_HOME_SETTINGS) }
        addSettingsRow("Run setup wizard", null) { startActivity(Intent(this, SetupActivity::class.java)) }
        addSettingsRow("Launcher app info", null) { openAppDetails(packageName) }
        addSettingsHeader("About")
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        addSettingsRow("About", "${getString(R.string.app_name)} $ver") { onAboutTap() }
        addSettingsRow("Copyright & license", null) { showTextSheet("License", rawText(R.raw.license)) }
        addSettingsRow("Third-party notices", null) { showTextSheet("Third-party notices", rawText(R.raw.third_party)) }
        if (prefs.getBoolean("debug", false)) buildDebugSection()
    }

    // --- Kai carousel: recent apps in a vertical rail above the dock ---
    private fun buildCarousel() {
        val recents = recentApps(4, exclude = dockPkgs)
        carousel.removeAllViews()
        carousel.visibility = if (recents.isEmpty()) View.GONE else View.VISIBLE
        recents.forEach { app ->
            val tile = layoutInflater.inflate(R.layout.item_carousel, carousel, false)
            tile.findViewById<ImageView>(R.id.carIcon).setImageDrawable(app.icon)
            tile.setOnClickListener { launchApp(app) }
            carousel.addView(tile)
        }
    }

    // --- Recents (KaiOS-style app switcher) ---
    // Most-recently-used launchable apps from UsageStats (GET_USAGE_STATS).
    private fun recentApps(max: Int, exclude: Set<String> = emptySet()): List<AppInfo> {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE)
            as? android.app.usage.UsageStatsManager ?: return emptyList()
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 12 * 3600_000L, now)
        val e = android.app.usage.UsageEvents.Event()
        val lastSeen = HashMap<String, Long>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastSeen[e.packageName] = e.timeStamp
            }
        }
        val byPkg = apps.associateBy { it.packageName }   // launchable, excludes us
        return lastSeen.entries.sortedByDescending { it.value }
            .mapNotNull { byPkg[it.key] }
            .filter { it.packageName !in exclude }
            .take(max)
    }

    private fun showRecents() {
        val recents = recentApps(8)
        if (recents.isEmpty()) { toast("No recent apps"); return }
        val view = layoutInflater.inflate(R.layout.dialog_list, null)
        view.findViewById<TextView>(R.id.listTitle).text = "Recent apps"
        val rows = view.findViewById<LinearLayout>(R.id.listRows)
        val dialog = makeSheet(view)
        val d = resources.displayMetrics.density
        val iconPx = (30 * d).toInt()
        val pad = (12 * d).toInt()
        recents.forEach { app ->
            // Use an ImageView (fit-center) rather than a compound drawable: the
            // latter needs setBounds() on app.icon, which is shared with the
            // carousel/grid/dock and would shrink them until they re-layout.
            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
                isFocusable = true
                setBackgroundResource(R.drawable.opt_row_bg)
            }
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconPx, iconPx).apply { marginEnd = pad }
                setImageDrawable(app.icon)
            }
            val label = TextView(this).apply {
                setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 15f
                text = app.label
            }
            row.addView(iv); row.addView(label)
            row.setOnClickListener { launchApp(app); dialog.dismiss() }
            rows.addView(row)
        }
        dialog.show()
        rows.getChildAt(0)?.requestFocus()
    }

    // --- Quick profiles (long-press side manner button) ---
    private fun showProfileSheet() {
        val view = layoutInflater.inflate(R.layout.dialog_list, null)
        view.findViewById<TextView>(R.id.listTitle).text = "Profile"
        val rows = view.findViewById<LinearLayout>(R.id.listRows)
        val dialog = makeSheet(view)
        val cur = Ringer.label(this)
        Ringer.PROFILES.forEach { (name, mode) ->
            if (name == "Silent" && !Ringer.canSilent(this)) return@forEach
            val row = sheetRow(if (name == cur) "● $name" else name)
            if (name == cur) row.setTextColor(Themes.accent(this))
            row.setOnClickListener {
                toast("Profile: " + Ringer.set(this, mode))
                if (screen == Screen.CONTROL) control.refresh()
                dialog.dismiss()
            }
            rows.addView(row)
        }
        dialog.show()
        rows.getChildAt(0)?.requestFocus()
    }

    // --- Color themes ---
    private fun showThemePicker() {
        val view = layoutInflater.inflate(R.layout.dialog_list, null)
        view.findViewById<TextView>(R.id.listTitle).text = "Theme"
        val rows = view.findViewById<LinearLayout>(R.id.listRows)
        val dialog = makeSheet(view)
        val cur = Themes.current(this)
        Themes.ALL.forEach { t ->
            val row = sheetRow(if (t.key == cur.key) "● ${t.label}" else t.label)
            if (t.key == cur.key) row.setTextColor(Themes.accent(this))
            row.setOnClickListener {
                dialog.dismiss()
                if (t.key != cur.key) {
                    Themes.select(this, t.key)
                    applyLockWallpaperOnce()   // re-sync the system keyguard wallpaper
                    recreate()                 // reload with the new accent + wallpaper
                }
            }
            rows.addView(row)
        }
        dialog.show()
        rows.getChildAt(0)?.requestFocus()
    }

    // --- Debug mode ---
    private var aboutTaps = 0
    private fun onAboutTap() {
        if (prefs.getBoolean("debug", false)) return
        if (++aboutTaps >= 7) {
            prefs.edit().putBoolean("debug", true).apply()
            toast("Debug mode enabled")
            buildSettings()
        } else if (aboutTaps >= 4) {
            toast("${7 - aboutTaps} more to enable debug")
        }
    }

    private fun buildDebugSection() {
        addSettingsHeader("Debug")
        addSettingsRow("Stay awake while charging", onOffStr(stayAwakeOn())) {
            setStayAwake(!stayAwakeOn()); buildSettings()
        }
        addSettingsRow("Long screen timeout (10 min)", onOffStr(screenTimeoutLong())) {
            setScreenTimeoutLong(!screenTimeoutLong()); buildSettings()
        }
        addSettingsRow("Lock immediately on sleep", onOffStr(lockImmediate())) {
            setLockImmediate(!lockImmediate()); buildSettings()
        }
        addSettingsRow("Restore device settings to defaults", null) {
            setStayAwake(false); setScreenTimeoutLong(false); setLockImmediate(false)
            toast("Device settings restored"); buildSettings()
        }
        addSettingsRow("Android", android.os.Build.VERSION.RELEASE) {}
        val dm = resources.displayMetrics
        addSettingsRow("Screen", "${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi}dpi") {}
        // NotificationManager#isNotificationListenerAccessGranted is API 27; the
        // compat helper reads the enabled-listeners setting and works on 26.
        addSettingsRow("Notification access",
            if (packageName in androidx.core.app.NotificationManagerCompat
                    .getEnabledListenerPackages(this)) "Granted" else "Not granted"
        ) { openSetting(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        addSettingsRow("Draw over apps",
            if (android.provider.Settings.canDrawOverlays(this)) "Granted" else "Not granted") {}
        addSettingsRow("Disable debug mode", null) {
            prefs.edit().putBoolean("debug", false).apply(); aboutTaps = 0; buildSettings()
        }
    }

    private fun rawText(id: Int): String =
        resources.openRawResource(id).bufferedReader().use { it.readText() }

    // Long-text sheet (license/notices): one scrollable TextView instead of rows.
    // Focusable + ScrollingMovementMethod so the d-pad scrolls the text itself.
    private fun showTextSheet(title: String, body: String) {
        val view = layoutInflater.inflate(R.layout.dialog_list, null)
        view.findViewById<TextView>(R.id.listTitle).text = title
        val rows = view.findViewById<LinearLayout>(R.id.listRows)
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (420 * d).toInt()
            )
            setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            movementMethod = android.text.method.ScrollingMovementMethod()
            isFocusable = true
            isVerticalScrollBarEnabled = true
            text = body
        }
        rows.addView(tv)
        makeSheet(view).show()
        tv.requestFocus()
    }

    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()
    private fun onOffStr(b: Boolean) = if (b) "On" else "Off"

    // Device-setting toggles (need WRITE_SETTINGS / WRITE_SECURE_SETTINGS).
    private fun stayAwakeOn() =
        android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) != 0
    private fun setStayAwake(on: Boolean) = putSecureGlobal {
        android.provider.Settings.Global.putInt(contentResolver, android.provider.Settings.Global.STAY_ON_WHILE_PLUGGED_IN, if (on) 3 else 0)
    }
    private fun screenTimeoutLong() =
        (try { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT) } catch (_: Exception) { 60000 }) >= 300000
    private fun setScreenTimeoutLong(long: Boolean) {
        if (!android.provider.Settings.System.canWrite(this)) { toast("Grant WRITE_SETTINGS"); return }
        android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, if (long) 600000 else 30000)
    }
    private fun lockImmediate() =
        android.provider.Settings.Secure.getLong(contentResolver, "lock_screen_lock_after_timeout", 5000L) == 0L
    private fun setLockImmediate(on: Boolean) = putSecureGlobal {
        android.provider.Settings.Secure.putLong(contentResolver, "lock_screen_lock_after_timeout", if (on) 0L else 5000L)
    }
    private inline fun putSecureGlobal(block: () -> Unit) {
        try { block() } catch (_: SecurityException) {
            toast("Grant: adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
        } catch (_: Exception) {}
    }

    private fun targetLabel(token: String?): String = when {
        token == null -> "Not set"
        token.startsWith("action:") -> shortcutActions.firstOrNull { it.first == token }?.second ?: token
        else -> labelFor(token)
    }

    private fun addSettingsHeader(text: String) {
        val tv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * resources.displayMetrics.density).toInt() }
            setPadding((4 * resources.displayMetrics.density).toInt(), 0,
                0, (4 * resources.displayMetrics.density).toInt())
            setTextColor(Themes.accent(context))
            textSize = 12f
            letterSpacing = 0.06f
            this.text = text
        }
        settingsRows.addView(tv)
    }

    private fun addSettingsRow(title: String, value: String?, onClick: () -> Unit) {
        val v = layoutInflater.inflate(R.layout.item_setting, settingsRows, false)
        v.findViewById<TextView>(R.id.setTitle).text = title
        val valView = v.findViewById<TextView>(R.id.setValue)
        if (value != null) { valView.text = value; valView.visibility = View.VISIBLE }
        v.setOnClickListener { onClick() }
        settingsRows.addView(v)
    }

    private fun openSetting(action: String) =
        try { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}

    private fun openAppDetails(pkg: String) =
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}

    // --- Physical keys, resolved to semantic roles by KeyBindings ---
    // The d-pad, number keys, and Back are universal and matched by raw keycode;
    // the model-specific keys (soft keys, shortcuts, side button, recents) are
    // matched through their role so any d-pad phone can be bound in Settings.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val lk = keys.role(keyCode)
        // Side/profile button: short press cycles profiles, long press opens the picker.
        if (lk == LauncherKey.PROFILE) { event.startTracking(); return true }
        // Dedicated recents key, if the phone has one.
        if (lk == LauncherKey.RECENTS) { showRecents(); return true }
        when (screen) {
            Screen.HOME -> when {
                // Up from the dock steps into the carousel rail (bottom item);
                // inside the rail Up/Down move between items (framework focus
                // traversal), Up past the top opens the grid, Down past the
                // bottom returns to the dock. Without the rail, Up = grid and
                // Down = notifications. Left/right/center fall through to the
                // focus system for dock traversal + launch.
                keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                    val f = currentFocus
                    if (f?.parent === carousel) {
                        if (carousel.indexOfChild(f) == 0) { showGrid(); return true }
                        // inner item: let focus traversal move up the rail
                    } else if (carousel.visibility == View.VISIBLE && carousel.childCount > 0) {
                        carousel.getChildAt(carousel.childCount - 1).requestFocus()
                        return true
                    } else { showGrid(); return true }
                }
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN || lk == LauncherKey.SOFT_LEFT -> {
                    val f = currentFocus
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && f?.parent === carousel) {
                        if (carousel.indexOfChild(f) == carousel.childCount - 1) {
                            dock.getChildAt(0)?.requestFocus()
                            return true
                        }
                        // inner item: let focus traversal move down the rail
                    } else { showNotifications(); return true }
                }
                lk == LauncherKey.SOFT_RIGHT -> { showControl(); return true }
                // Number keys 2-9 = speed dial. Track for long-press (assign).
                keyCode in KeyEvent.KEYCODE_2..KeyEvent.KEYCODE_9 -> {
                    event.startTracking(); return true
                }
                // Shortcut keys A/B. Track for long-press (assign).
                lk == LauncherKey.SHORTCUT_A || lk == LauncherKey.SHORTCUT_B -> {
                    event.startTracking(); return true
                }
            }
            Screen.GRID -> when (lk) {
                // Left soft key = focus the search field (brings up the IME).
                LauncherKey.SOFT_LEFT -> { gridSearch.requestFocus(); return true }
                // Right soft key = Options for the focused app.
                LauncherKey.SOFT_RIGHT -> {
                    focusedApp()?.let { showOptions(it) }
                    return true
                }
                else -> {}
            }
            Screen.CONTROL -> {}   // tiles handle focus/center; Back exits (onBackPressed)
            Screen.SETTINGS -> {}  // rows handle focus/center; Back exits (onBackPressed)
            Screen.NOTIF -> if (lk == LauncherKey.SOFT_LEFT) {   // left soft key = Clear all
                try { LockListenerService.instance?.cancelAllNotifications() } catch (_: Exception) {}
                // cancelAll is async; onChange refreshes as removals land, plus
                // a delayed sweep in case the last callback is missed.
                notifList.postDelayed({ if (screen == Screen.NOTIF) refreshNotifs() }, 250)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // BACK: grid -> home; on home, swallow so we never leave the launcher.
    // --- Speed dial (home number keys 2-9): short = call, long = (re)assign ---
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        val lk = keys.role(keyCode)
        if (lk == LauncherKey.PROFILE) { showProfileSheet(); return true }
        if (screen == Screen.HOME && keyCode in KeyEvent.KEYCODE_2..KeyEvent.KEYCODE_9) {
            assignSpeedDial(keyCode - KeyEvent.KEYCODE_0)
            return true
        }
        if (screen == Screen.HOME && (lk == LauncherKey.SHORTCUT_A || lk == LauncherKey.SHORTCUT_B)) {
            assignShortcut(lk)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val lk = keys.role(keyCode)
        if (lk == LauncherKey.PROFILE && !event.isCanceled) {
            toast("Profile: " + Ringer.cycle(this))
            if (screen == Screen.CONTROL) control.refresh()
            return true
        }
        if (screen == Screen.HOME && keyCode in KeyEvent.KEYCODE_2..KeyEvent.KEYCODE_9 && !event.isCanceled) {
            speedDialShort(keyCode - KeyEvent.KEYCODE_0)
            return true
        }
        if (screen == Screen.HOME && (lk == LauncherKey.SHORTCUT_A || lk == LauncherKey.SHORTCUT_B) && !event.isCanceled) {
            shortcutShort(lk)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // --- Shortcut keys A/B (target = a package name or an "action:" token) ---
    private fun shortcutPrefKey(role: LauncherKey) = "sc_${role.name}"

    // A defaults to the recents switcher; B to the app grid.
    private fun shortcutDefault(role: LauncherKey) =
        if (role == LauncherKey.SHORTCUT_A) "action:recents" else "action:grid"

    // Built-in launcher actions selectable as a shortcut target.
    private val shortcutActions = listOf(
        "action:grid" to "App grid",
        "action:recents" to "Recent apps",
        "action:notif" to "Notifications",
        "action:control" to "Control center",
        "action:settings" to "Settings"
    )

    /** One-time: carry the old F3/F4 shortcut assignments onto the new role keys. */
    private fun migrateShortcutPrefs() {
        if (prefs.getBoolean("kb_migrated", false)) return
        val e = prefs.edit()
        prefs.getString("sc_f3", null)?.let { e.putString(shortcutPrefKey(LauncherKey.SHORTCUT_A), it) }
        prefs.getString("sc_f4", null)?.let { e.putString(shortcutPrefKey(LauncherKey.SHORTCUT_B), it) }
        e.putBoolean("kb_migrated", true).apply()
    }

    private fun shortcutShort(role: LauncherKey) {
        val target = prefs.getString(shortcutPrefKey(role), shortcutDefault(role))
            ?: shortcutDefault(role)
        runShortcut(target) { assignShortcut(role) }
    }

    private fun runShortcut(target: String, onMissing: () -> Unit) {
        when (target) {
            "action:grid" -> showGrid()
            "action:recents" -> showRecents()
            "action:notif" -> showNotifications()
            "action:control" -> showControl()
            "action:settings" -> showSettings()
            else -> packageManager.getLaunchIntentForPackage(target)?.let {
                try { startActivity(it) } catch (_: Exception) {}
            } ?: onMissing()
        }
    }

    private fun assignShortcut(role: LauncherKey) {
        showShortcutPicker("Assign ${role.label} to:") { token, label ->
            prefs.edit().putString(shortcutPrefKey(role), token).apply()
            android.widget.Toast.makeText(this, "${role.label} → $label", android.widget.Toast.LENGTH_SHORT).show()
            if (screen == Screen.SETTINGS) buildSettings()
        }
    }

    private fun showShortcutPicker(title: String, onPick: (String, String) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_picker, null)
        view.findViewById<TextView>(R.id.pickTitle).text = title
        val rows = view.findViewById<LinearLayout>(R.id.pickRows)
        val dialog = makeSheet(view)
        // Built-in actions first (cyan accent), then apps.
        shortcutActions.forEach { (token, label) ->
            val row = sheetRow(label).apply {
                setTextColor(Themes.accent(this@MainActivity))
            }
            row.setOnClickListener { onPick(token, label); dialog.dismiss() }
            rows.addView(row)
        }
        apps.forEach { app ->
            val row = sheetRow(app.label)
            row.setOnClickListener { onPick(app.packageName, app.label); dialog.dismiss() }
            rows.addView(row)
        }
        dialog.show()
        rows.getChildAt(0)?.requestFocus()
    }

    // --- Key bindings: preset picker + learn-mode capture -------------------
    private fun showPresetPicker() {
        val view = layoutInflater.inflate(R.layout.dialog_list, null)
        view.findViewById<TextView>(R.id.listTitle).text = "Key preset"
        val rows = view.findViewById<LinearLayout>(R.id.listRows)
        val dialog = makeSheet(view)
        KeyBindings.PRESETS.keys.forEach { name ->
            val row = sheetRow(name, accent = name == keys.activePresetName())
            row.setOnClickListener {
                keys.applyPreset(name)              // clears per-role overrides
                toast("Preset: $name")
                dialog.dismiss()
                if (screen == Screen.SETTINGS) buildSettings()
            }
            rows.addView(row)
        }
        dialog.show()
        rows.getChildAt(0)?.requestFocus()
    }

    /**
     * Learn-mode: capture whatever raw keycode the phone delivers and bind it to [role].
     * Reserved navigation keys (d-pad, number keys, Back) are let through so the sheet's
     * own rows stay usable; any other key is captured. Works on any d-pad phone.
     */
    private fun showKeyCapture(role: LauncherKey) {
        val view = layoutInflater.inflate(R.layout.dialog_list, null)
        view.findViewById<TextView>(R.id.listTitle).text = "Press the key for “${role.label}”"
        val rows = view.findViewById<LinearLayout>(R.id.listRows)
        val dialog = makeSheet(view)

        sheetRow("Clear binding", accent = true).also { r ->
            r.setOnClickListener {
                keys.bind(role, null)
                toast("${role.label}: cleared")
                dialog.dismiss()
                if (screen == Screen.SETTINGS) buildSettings()
            }
            rows.addView(r)
        }
        sheetRow("Cancel", accent = true).also { r ->
            r.setOnClickListener { dialog.dismiss() }
            rows.addView(r)
        }

        dialog.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            // Back cancels; reserved keys fall through so the rows above stay operable.
            if (code == KeyEvent.KEYCODE_BACK || KeyBindings.isReserved(code)) {
                return@setOnKeyListener false
            }
            keys.bind(role, code)
            toast("${role.label} → ${KeyBindings.keyName(code)}")
            dialog.dismiss()
            if (screen == Screen.SETTINGS) buildSettings()
            true
        }
        dialog.show()
        rows.getChildAt(0)?.requestFocus()
    }

    private fun speedDialShort(digit: Int) {
        val entry = prefs.getString("sd_$digit", null)
        if (entry == null) assignSpeedDial(digit)
        else callNumber(entry.substringBefore("|"))
    }

    private var pendingSpeedDial = 0
    private fun assignSpeedDial(digit: Int) {
        pendingSpeedDial = digit
        try {
            startActivityForResult(
                Intent(Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
                REQ_PICK_CONTACT
            )
        } catch (_: Exception) {}
    }

    private fun callNumber(number: String) {
        val hasCall = checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val action = if (hasCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        try {
            startActivity(Intent(action, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_CONTACT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.query(
                uri,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val number = c.getString(0) ?: return
                    val name = c.getString(1) ?: number
                    prefs.edit().putString("sd_$pendingSpeedDial", "$number|$name").apply()
                    android.widget.Toast.makeText(
                        this, "Speed dial $pendingSpeedDial → $name", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    if (screen == Screen.SETTINGS) buildSettings()
                }
            }
        } catch (_: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        // In the grid: Back first clears an active search, then exits to home.
        if (screen == Screen.GRID && gridSearch.text.isNotEmpty()) {
            gridSearch.setText("")
            appGrid.layoutManager?.findViewByPosition(0)?.requestFocus()
            return
        }
        if (screen == Screen.GRID || screen == Screen.NOTIF ||
            screen == Screen.CONTROL || screen == Screen.SETTINGS) showHome()
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
        // Selection: soft highlight + the focused icon at full brightness,
        // the rest dimmed.
        v.setOnFocusChangeListener { view, hasFocus ->
            view.animate().alpha(if (hasFocus) 1f else DIM).setDuration(150).start()
        }
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.label.text = app.label
        holder.itemView.alpha = if (holder.itemView.hasFocus()) 1f else DIM
        holder.itemView.setOnClickListener { onLaunch(app) }
    }

    override fun getItemCount() = apps.size

    companion object {
        private const val DIM = 0.5f
    }
}

/** Notifications-center rows. */
private class NotifAdapter(
    private val ctx: android.content.Context,
    private val items: List<StatusBarNotification>,
    private val onOpen: (StatusBarNotification) -> Unit
) : RecyclerView.Adapter<NotifAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.notifIcon)
        val title: TextView = view.findViewById(R.id.notifTitle)
        val text: TextView = view.findViewById(R.id.notifText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_notif, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val sbn = items[position]
        val extras = sbn.notification.extras
        holder.title.text = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: appLabel(sbn.packageName)
        holder.text.text = extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""
        holder.icon.setImageDrawable(iconFor(sbn))
        holder.itemView.setOnClickListener { onOpen(sbn) }
    }

    override fun getItemCount() = items.size

    private fun appLabel(pkg: String): CharSequence = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0))
    } catch (_: Exception) { pkg }

    private fun iconFor(sbn: StatusBarNotification): android.graphics.drawable.Drawable? = try {
        ctx.packageManager.getApplicationIcon(sbn.packageName)
    } catch (_: Exception) {
        try { sbn.notification.smallIcon?.loadDrawable(ctx) } catch (_: Exception) { null }
    }
}

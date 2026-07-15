package dev.stefan.kyf42launcher

import android.app.KeyguardManager
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

private enum class Screen { HOME, GRID, NOTIF }

class MainActivity : AppCompatActivity() {

    private lateinit var homeView: View
    private lateinit var dock: LinearLayout
    private lateinit var appGrid: RecyclerView
    private lateinit var notifPanel: View
    private lateinit var notifList: RecyclerView
    private lateinit var notifEmpty: View
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
    private lateinit var widgets: HomeWidgets

    private lateinit var gridContainer: View
    private lateinit var gridSearch: EditText

    private var screen = Screen.HOME
    private val apps = mutableListOf<AppInfo>()        // all launchable apps
    private val gridApps = mutableListOf<AppInfo>()    // currently shown (search-filtered)

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
    private val DOCK_MAX = 5

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
        LockListenerService.onChange = null
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
        val n = LockListenerService.instance?.current()?.size ?: 0
        lsk.text = if (n > 0) "● $n Alerts" else "Alerts"
        csk.text = ""
        rsk.text = ""
        dock.post { dock.getChildAt(0)?.requestFocus() }
    }

    private fun showGrid() {
        screen = Screen.GRID
        homeView.visibility = View.GONE
        notifPanel.visibility = View.GONE
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

    // --- KYF42 physical keys (see matrix_keypad.kl) ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (screen) {
            Screen.HOME -> when (keyCode) {
                // Up from the dock opens the app grid; Down opens notifications
                // (F1/left soft key also opens notifications). Left/right/center
                // fall through to the focus system for dock traversal + launch.
                KeyEvent.KEYCODE_DPAD_UP -> { showGrid(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_F1 -> {
                    showNotifications(); return true
                }
            }
            Screen.GRID -> when (keyCode) {
                // F1 = left soft key = focus the search field (brings up the IME).
                KeyEvent.KEYCODE_F1 -> { gridSearch.requestFocus(); return true }
                // F2 = right soft key = Options for the focused app.
                KeyEvent.KEYCODE_F2 -> {
                    focusedApp()?.let { showOptions(it) }
                    return true
                }
            }
            Screen.NOTIF -> when (keyCode) {
                KeyEvent.KEYCODE_F1 -> {   // left soft key = Clear all
                    try { LockListenerService.instance?.cancelAllNotifications() } catch (_: Exception) {}
                    // cancelAll is async; onChange refreshes as removals land, plus
                    // a delayed sweep in case the last callback is missed.
                    notifList.postDelayed({ if (screen == Screen.NOTIF) refreshNotifs() }, 250)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // BACK: grid -> home; on home, swallow so we never leave the launcher.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // In the grid: Back first clears an active search, then exits to home.
        if (screen == Screen.GRID && gridSearch.text.isNotEmpty()) {
            gridSearch.setText("")
            appGrid.layoutManager?.findViewByPosition(0)?.requestFocus()
            return
        }
        if (screen == Screen.GRID || screen == Screen.NOTIF) showHome()
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

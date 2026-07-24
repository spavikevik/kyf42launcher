package dev.stefan.kyf42launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import dev.stefan.kyf42launcher.utils.Permissions
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * First-run setup wizard. Walks a new user through key bindings, permissions, and
 * theme, then drops to the home screen. Everything here is driven by the d-pad and
 * the center key only — soft keys aren't bound yet on the first run, so the wizard
 * can never depend on them.
 *
 * Launched by [MainActivity] when prefs "setup_done" is false; sets it true on finish.
 */
class SetupActivity : AppCompatActivity() {

    private enum class Step { WELCOME, KEYS, PERMISSIONS, THEME, DONE }
    private val steps = Step.values()
    private var index = 0
    private val step get() = steps[index]

    private val prefs by lazy { getSharedPreferences("kyf42", Context.MODE_PRIVATE) }
    private val keys by lazy { KeyBindings(prefs) }

    private lateinit var dots: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var content: LinearLayout
    private lateinit var backBtn: TextView
    private lateinit var nextBtn: TextView

    // Soft-key learn (KEYS step): the role currently awaiting a key press, plus the
    // queue of roles still to capture. Null = not capturing.
    private var capturing: LauncherKey? = null
    private var captureQueue = mutableListOf<LauncherKey>()
    private var captureStatus: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val theme = Themes.apply(this)   // accent overlay, before inflation
        setContentView(R.layout.activity_setup)
        findViewById<View>(R.id.rootSetup).setBackgroundResource(theme.wallpaperRes)

        index = savedInstanceState?.getInt(KEY_INDEX, 0) ?: 0

        dots = findViewById(R.id.setupDots)
        titleView = findViewById(R.id.setupTitle)
        subtitleView = findViewById(R.id.setupSubtitle)
        content = findViewById(R.id.setupContent)
        backBtn = findViewById(R.id.setupBack)
        nextBtn = findViewById(R.id.setupNext)
        backBtn.setOnClickListener { goBack() }
        nextBtn.setOnClickListener { goNext() }

        render()
        hideSystemBars()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_INDEX, index)
    }

    // Returning from a system permission screen: re-tick the checklist.
    override fun onResume() {
        super.onResume()
        if (step == Step.PERMISSIONS) render()
    }

    private fun accent() = Themes.accent(this)

    // --- Navigation ---------------------------------------------------------
    private fun goNext() {
        if (step == Step.DONE) { finishSetup(); return }
        index++; render()
    }

    private fun goBack() {
        if (capturing != null) { cancelCapture(); return }
        if (index > 0) { index--; render() }
    }

    private fun render() {
        capturing = null
        captureStatus = null
        buildDots()
        content.removeAllViews()
        when (step) {
            Step.WELCOME -> renderWelcome()
            Step.KEYS -> renderKeys()
            Step.PERMISSIONS -> renderPermissions()
            Step.THEME -> renderTheme()
            Step.DONE -> renderDone()
        }
        backBtn.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
        nextBtn.text = if (step == Step.DONE) "Finish" else "Next"
        // Focus the first actionable row, or the Next button if the step has none.
        content.post {
            val firstRow = (0 until content.childCount)
                .map { content.getChildAt(it) }.firstOrNull { it.isFocusable }
            (firstRow ?: nextBtn).requestFocus()
        }
    }

    // --- Steps --------------------------------------------------------------
    private fun renderWelcome() {
        titleView.text = getString(R.string.app_name)
        subtitleView.text = "A fast, KaiOS-style home for d-pad phones. Let's get it set up — takes a minute."
        content.addView(bodyText("Move with the d-pad. Press the center key (OK) to choose.", accent = true))
    }

    private fun renderKeys() {
        titleView.text = "Your keys"
        subtitleView.text = "Pick your phone, then test the two soft keys under the screen."
        KeyBindings.PRESETS.keys.forEach { name ->
            val selected = keys.activePresetName() == name
            val label = if (name == "Standard") "Standard  (other phones)" else name
            contentRow(label, if (selected) "Selected" else null) {
                keys.applyPreset(name)
                render()
            }
        }
        content.addView(sectionLabel("Soft keys"))
        captureStatus = bodyText(softKeyStatus()).also { content.addView(it) }
        contentRow("Test / learn soft keys", "Press each key when asked") { startSoftKeyTest() }
    }

    private fun renderPermissions() {
        titleView.text = "Permissions"
        subtitleView.text = "Grant these so notifications, recents, and the bars work. Open each, allow it, then press Back to return."
        permissions().forEach { p ->
            contentRow(p.label, if (p.granted()) "Granted ✓" else "Open to grant") { p.open() }
        }
    }

    private fun renderTheme() {
        titleView.text = "Theme"
        subtitleView.text = "Pick an accent. You can change it anytime in Settings."
        val curKey = Themes.current(this).key
        Themes.ALL.forEach { t ->
            themeRow(t, selected = t.key == curKey) {
                if (t.key != curKey) {
                    Themes.select(this, t.key)
                    recreate()   // re-apply accent everywhere; index is restored from state
                }
            }
        }
    }

    private fun renderDone() {
        titleView.text = "You're all set"
        subtitleView.text = "Enjoy your phone."
        val granted = permissions().count { it.granted() }
        content.addView(bodyText("• Keys:  ${keys.activePresetName()} preset"))
        content.addView(bodyText("• Soft keys:  ${softKeyStatus()}"))
        content.addView(bodyText("• Permissions:  $granted of ${permissions().size} granted"))
        content.addView(bodyText("• Theme:  ${Themes.current(this).label}"))
    }

    // --- Soft-key learn -----------------------------------------------------
    private fun softKeyStatus(): String {
        fun name(r: LauncherKey) = keys.code(r)?.let { KeyBindings.keyName(it) } ?: "unset"
        return "Left: ${name(LauncherKey.SOFT_LEFT)}    Right: ${name(LauncherKey.SOFT_RIGHT)}"
    }

    private fun startSoftKeyTest() {
        captureQueue = mutableListOf(LauncherKey.SOFT_LEFT, LauncherKey.SOFT_RIGHT)
        nextCapture()
    }

    private fun nextCapture() {
        capturing = captureQueue.removeFirstOrNull()
        val role = capturing
        if (role == null) { finishCapture(); return }
        captureStatus?.apply {
            text = "Press your ${role.label} now…  (OK to skip)"
            setTextColor(accent())
        }
    }

    private fun finishCapture() {
        toast("Soft keys set")
        render()   // refresh bindings display + selection
    }

    private fun cancelCapture() {
        capturing = null
        captureQueue.clear()
        render()
    }

    // --- Permissions model --------------------------------------------------
    private data class Perm(val label: String, val granted: () -> Boolean, val open: () -> Unit)

    private fun permissions(): List<Perm> = listOf(
        Perm("Set as default home", { isDefaultHome() }) { open(Settings.ACTION_HOME_SETTINGS) },
        Perm("Notification access", { hasNotificationAccess() }) {
            open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        },
        Perm("Usage access (recents)", { hasUsageAccess() }) {
            open(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        },
        Perm("Draw over apps (bars)", { Settings.canDrawOverlays(this) }) {
            open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        },
    )

    private fun isDefaultHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val res = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return res?.activityInfo?.packageName == packageName
    }

    private fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }

    private fun hasUsageAccess(): Boolean = Permissions.hasUsageAccess(this)

    private fun open(action: String, data: Uri? = null) {
        // No NEW_TASK: keep it in our task so Back returns here and onResume re-ticks.
        try {
            startActivity(Intent(action).also { if (data != null) it.data = data })
        } catch (_: Exception) {
            toast("Not available on this phone")
        }
    }

    // --- View helpers -------------------------------------------------------
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun contentRow(title: String, value: String?, onClick: () -> Unit): View {
        val v = layoutInflater.inflate(R.layout.item_setting, content, false)
        v.findViewById<TextView>(R.id.setTitle).text = title
        val valView = v.findViewById<TextView>(R.id.setValue)
        if (value != null) { valView.text = value; valView.visibility = View.VISIBLE }
        v.setOnClickListener { onClick() }
        content.addView(v)
        return v
    }

    private fun bodyText(s: String, accent: Boolean = false): TextView = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2); bottomMargin = dp(6) }
        text = s
        textSize = 13f
        setTextColor(if (accent) accent() else ContextCompat.getColor(context, R.color.text_secondary))
        setLineSpacing(0f, 1.15f)
    }

    private fun sectionLabel(s: String): TextView = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10); bottomMargin = dp(4) }
        text = s.uppercase()
        textSize = 11f
        setTextColor(accent())
        letterSpacing = 0.06f
    }

    private fun themeRow(t: LauncherTheme, selected: Boolean, onClick: () -> Unit): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            isFocusable = true
            setBackgroundResource(R.drawable.notif_bg)
            setOnClickListener { onClick() }
        }
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(12) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@SetupActivity, themeSwatchRes(t.key)))
            }
        })
        root.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = t.label
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })
        if (selected) root.addView(TextView(this).apply {
            text = "Selected"
            textSize = 11f
            setTextColor(accent())
        })
        content.addView(root)
        return root
    }

    private fun themeSwatchRes(key: String): Int = when (key) {
        "sunset" -> R.color.accent_sunset
        "forest" -> R.color.accent_forest
        "sakura" -> R.color.accent_sakura
        "mono" -> R.color.accent_mono
        else -> R.color.kai_blue
    }

    private fun buildDots() {
        dots.removeAllViews()
        val size = dp(7)
        for (i in steps.indices) {
            dots.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = if (i == 0) 0 else dp(5)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (i == index) accent() else 0x40FFFFFF)
                }
            })
        }
    }

    private fun toast(m: String) =
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    // --- Keys ---------------------------------------------------------------
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val role = capturing
        if (role != null) {
            when {
                keyCode == KeyEvent.KEYCODE_BACK -> cancelCapture()
                // Center/OK skips this role, keeping whatever it's already bound to.
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ->
                    nextCapture()
                // D-pad moves are ignored while capturing; any other key binds.
                KeyBindings.isReserved(keyCode) -> {}
                else -> { keys.bind(role, keyCode); nextCapture() }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun finishSetup() {
        prefs.edit().putBoolean("setup_done", true).apply()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    @Deprecated("Deprecated in Java")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        if (capturing != null) { cancelCapture(); return }
        if (index > 0) goBack()   // first step: stay in setup
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

    companion object { private const val KEY_INDEX = "setup_index" }
}

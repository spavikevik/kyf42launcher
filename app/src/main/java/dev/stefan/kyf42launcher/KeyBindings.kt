package dev.stefan.kyf42launcher

import android.content.SharedPreferences
import android.view.KeyEvent

/**
 * Physical keys that vary by phone model, addressed by role instead of raw keycode.
 *
 * The d-pad, number keys, and Back are universal across d-pad phones and are handled
 * directly by the activity — they never pass through here. Only these model-specific
 * keys need mapping.
 */
enum class LauncherKey(val label: String) {
    SOFT_LEFT("Left soft key"),
    SOFT_RIGHT("Right soft key"),
    SHORTCUT_A("Shortcut key A"),
    SHORTCUT_B("Shortcut key B"),
    PROFILE("Side / profile button"),
    RECENTS("Recents key"),
}

/**
 * Resolves raw Android keycodes to semantic [LauncherKey]s so the launcher runs on any
 * d-pad phone, not just the KYF42.
 *
 * Two layers, later wins:
 *   1. a named preset — the factory default map for a known phone ([PRESETS]).
 *   2. per-role overrides captured in learn-mode, stored as "kb_<ROLE>" = keycode
 *      (sentinel [UNBOUND] = "explicitly none").
 *
 * A private reverse map (keycode -> role) is cached and rebuilt whenever bindings change.
 */
class KeyBindings(private val prefs: SharedPreferences) {

    private var reverse: Map<Int, LauncherKey> = emptyMap()

    init { rebuild() }

    /** Semantic role for a raw keycode, or null if this keycode maps to nothing. */
    fun role(keyCode: Int): LauncherKey? = reverse[keyCode]

    /** Effective keycode bound to a role, or null if unbound. */
    fun code(role: LauncherKey): Int? {
        val override = prefs.getInt(prefKey(role), NONE)
        val c = if (override != NONE) override else preset()[role] ?: NONE
        return if (c == NONE || c == UNBOUND) null else c
    }

    /** Bind a role to a keycode (learn-mode result); pass null to explicitly unbind. */
    fun bind(role: LauncherKey, keyCode: Int?) {
        prefs.edit().putInt(prefKey(role), keyCode ?: UNBOUND).apply()
        rebuild()
    }

    fun activePresetName(): String = prefs.getString(PRESET_KEY, DEFAULT_PRESET) ?: DEFAULT_PRESET

    /** Switch to a named preset and drop every per-role override. */
    fun applyPreset(name: String) {
        val e = prefs.edit().putString(PRESET_KEY, name)
        LauncherKey.values().forEach { e.remove(prefKey(it)) }
        e.apply()
        rebuild()
    }

    private fun preset(): Map<LauncherKey, Int> = PRESETS[activePresetName()] ?: KYF42

    private fun rebuild() {
        // Later roles win a keycode collision; order follows enum declaration.
        reverse = LauncherKey.values()
            .mapNotNull { r -> code(r)?.let { it to r } }
            .toMap()
    }

    private fun prefKey(role: LauncherKey) = "kb_${role.name}"

    companion object {
        const val NONE = Int.MIN_VALUE   // no override stored for this role
        const val UNBOUND = -1           // stored "explicitly none"
        const val PRESET_KEY = "kb_preset"
        const val DEFAULT_PRESET = "KYF42"

        /** KYF42 factory mapping (see the device's matrix_keypad.kl). */
        val KYF42 = mapOf(
            LauncherKey.SOFT_LEFT to KeyEvent.KEYCODE_F1,
            LauncherKey.SOFT_RIGHT to KeyEvent.KEYCODE_F2,
            LauncherKey.SHORTCUT_A to KeyEvent.KEYCODE_F3,
            LauncherKey.SHORTCUT_B to KeyEvent.KEYCODE_F4,
            LauncherKey.PROFILE to KeyEvent.KEYCODE_CAMERA,
            LauncherKey.RECENTS to KeyEvent.KEYCODE_APP_SWITCH,
        )

        /**
         * Standard Android soft keys. Optional hardware keys (shortcuts, side button)
         * are left unbound so they never collide — add them per phone via learn-mode.
         */
        val STANDARD = mapOf(
            LauncherKey.SOFT_LEFT to KeyEvent.KEYCODE_SOFT_LEFT,
            LauncherKey.SOFT_RIGHT to KeyEvent.KEYCODE_SOFT_RIGHT,
            LauncherKey.RECENTS to KeyEvent.KEYCODE_APP_SWITCH,
        )

        val PRESETS = linkedMapOf("KYF42" to KYF42, "Standard" to STANDARD)

        /** Navigation/reserved keys that must never be rebound to a role. */
        fun isReserved(keyCode: Int): Boolean =
            keyCode in RESERVED || keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9

        private val RESERVED = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
        )

        /** Human-readable keycode name, e.g. 132 -> "F1". */
        fun keyName(keyCode: Int): String =
            KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    }
}

package dev.stefan.kyf42launcher

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.core.content.edit

/** One selectable color theme: accent overlay + matching mesh wallpaper. */
data class LauncherTheme(
    val key: String,
    val label: String,
    val styleRes: Int,
    val wallpaperRes: Int,
)

/**
 * Color themes. The accent travels as the ?attr/accentColor theme attribute
 * (see themes.xml overlays); the wallpaper is set on each screen's root view.
 * Switching themes recreates the activity.
 */
object Themes {
    val ALL = listOf(
        LauncherTheme("kai", "Ocean", R.style.Accent_Kai, R.drawable.wallpaper),
        LauncherTheme("sunset", "Sunset", R.style.Accent_Sunset, R.drawable.wallpaper_sunset),
        LauncherTheme("forest", "Forest", R.style.Accent_Forest, R.drawable.wallpaper_forest),
        LauncherTheme("sakura", "Sakura", R.style.Accent_Sakura, R.drawable.wallpaper_sakura),
        LauncherTheme("mono", "Graphite", R.style.Accent_Mono, R.drawable.wallpaper_mono),
    )

    fun current(ctx: Context): LauncherTheme {
        val key = ctx.getSharedPreferences("kyf42", Context.MODE_PRIVATE)
            .getString("theme", "kai")
        return ALL.firstOrNull { it.key == key } ?: ALL[0]
    }

    /** Call before setContentView; returns the theme so callers can set the wallpaper. */
    fun apply(activity: Activity): LauncherTheme {
        val t = current(activity)
        activity.theme.applyStyle(t.styleRes, true)
        return t
    }

    fun select(ctx: Context, key: String) {
        ctx.getSharedPreferences("kyf42", Context.MODE_PRIVATE).edit {
            putString("theme", key)
                .putBoolean("lock_wp_set", false)   // re-sync the system lock wallpaper
        }
    }

    /** Resolved accent for code that colors views programmatically. */
    fun accent(ctx: Context): Int {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(R.attr.accentColor, tv, true)
        return tv.data
    }
}

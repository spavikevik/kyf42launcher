package dev.stefan.kyf42launcher.utils

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/** Special-access checks that have no runtime-permission equivalent. */
object Permissions {

    /**
     * Usage access (Settings > Special app access), required by UsageStatsManager
     * for the recents rail and the app switcher. Without it queryEvents returns
     * an empty stream rather than throwing, so callers must check first to tell
     * "no recent apps" apart from "not allowed to know".
     */
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

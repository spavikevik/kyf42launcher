package dev.stefan.kyf42launcher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * NotificationListenerService: doubles as (1) a process anchor so the lock
 * screen's SCREEN_OFF receiver survives, and (2) the source for the launcher's
 * notifications center. Requires Notification Access (granted in Settings / adb).
 */
class LockListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        onChange?.invoke()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) { onChange?.invoke() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { onChange?.invoke() }

    /** Current dismissable notifications, newest first. */
    fun current(): List<StatusBarNotification> = try {
        (activeNotifications ?: emptyArray())
            .filter { it.isClearable }
            .sortedByDescending { it.postTime }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        @JvmStatic
        var instance: LockListenerService? = null

        /** Invoked (on a binder thread) whenever notifications change. */
        var onChange: (() -> Unit)? = null
    }
}

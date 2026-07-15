package dev.stefan.kyf42launcher

import android.service.notification.NotificationListenerService

/**
 * Empty NotificationListenerService used purely as a process anchor: once the
 * user grants Notification Access, NotificationManagerService binds to it and
 * the OS won't kill our process, so the SCREEN_OFF receiver that arms the
 * lock screen stays registered across recents-swipes and idle.
 */
class LockListenerService : NotificationListenerService()

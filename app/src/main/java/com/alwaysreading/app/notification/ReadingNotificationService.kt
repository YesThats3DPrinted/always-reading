package com.alwaysreading.app.notification

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps reading notifications alive and non-swipeable.
 *
 * Android requires that a service started with startForegroundService() calls
 * startForeground() within 5 seconds — otherwise the app crashes. This service
 * satisfies that requirement by calling startForeground() in onStartCommand()
 * using the notification passed through Intent extras.
 *
 * NotificationHelper.show() builds the notification, passes it here via extras,
 * and this service calls startForeground(id, notification) to attach it.
 *
 * NotificationHelper.hide() cancels the notification and calls stopService().
 * If multiple books are active, the service is kept alive — the last hide() call
 * (when notificationActive goes false on the last book) stops the service.
 */
class ReadingNotificationService : Service() {

    companion object {
        const val EXTRA_NOTIF_ID     = "notif_id"
        const val EXTRA_NOTIFICATION = "notification"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Restarted by system without our intent (e.g. after being killed).
            // No notification to attach — stop cleanly. BootReceiver handles
            // re-posting notifications after a full reboot.
            stopSelf()
            return START_NOT_STICKY
        }

        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 1)
        val notification: Notification? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_NOTIFICATION, Notification::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_NOTIFICATION)
        }

        if (notification != null) {
            // Must be called within 5 seconds of startForegroundService().
            startForeground(notifId, notification)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }
}

package com.notibook.app.notification

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service that keeps reading notifications alive.
 *
 * Starting a foreground service requires calling startForeground() within
 * a few seconds of onStartCommand(). NotificationHelper.show() starts this
 * service and immediately posts the notification via startForeground().
 *
 * The service stays alive until NotificationHelper.hide() stops it, or until
 * there are no more active book notifications (checked by NotificationHelper).
 *
 * This makes notifications:
 *  - Non-swipeable (setOngoing(true) prevents user dismissal)
 *  - Always expanded (foreground services are never collapsed by the OS)
 */
class ReadingNotificationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground() is called externally by NotificationHelper.show()
        // via startForeground(notifId, notification) below.
        // Returning START_STICKY ensures Android restarts the service if it is
        // killed, though BootReceiver handles the notification restore case.
        return START_STICKY
    }
}

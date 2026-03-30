package com.notibook.app.notification

/**
 * NotificationService has been removed.
 *
 * All notification posting is now done directly via NotificationHelper.show() /
 * NotificationHelper.hide(), which call NotificationManagerCompat and require no
 * persistent service. This eliminates the foreground-service constraint that caused
 * MIUI to permanently force-expand notifications and prevent swiping them away.
 *
 * This stub exists only to satisfy any IDE caches; it is not referenced anywhere
 * and is not declared in AndroidManifest.xml.
 */
internal object NotificationService

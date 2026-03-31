package com.notibook.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.notibook.app.data.BookRepository
import com.notibook.app.data.db.AppDatabase
import com.notibook.app.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class NotiBookApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val repository: BookRepository by lazy {
        BookRepository(database.bookDao(), database.sentenceDao(), this)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "notibook_persistent"
    }

    /** Used to communicate a notification-tap bookId to AppNavigation when the app is already open. */
    val pendingOpenBookId = MutableStateFlow(-1L)

    val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        restoreActiveNotifications()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "NotiBook Reading",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Persistent sentence-by-sentence reading notifications"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Re-posts notifications for any books that were active when the process was last killed.
     * (Device reboots are handled separately by BootReceiver.)
     */
    private fun restoreActiveNotifications() {
        appScope.launch {
            val activeBooks = repository.getActiveBooks()
            for (book in activeBooks) {
                val sentence = repository.getSentence(book.id, book.currentIndex) ?: continue
                NotificationHelper.show(this@NotiBookApp, book, sentence)
            }
        }
    }

    fun requestBatteryOptimisationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}

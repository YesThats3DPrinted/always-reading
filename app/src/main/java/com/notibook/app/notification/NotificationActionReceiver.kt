package com.notibook.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notibook.app.NotiBookApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles taps on notification buttons (←, →, Dismiss) and swipe-to-dismiss.
 * Posts notification updates directly via NotificationHelper — no service required.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PREV    = "com.notibook.PREV"
        const val ACTION_NEXT    = "com.notibook.NEXT"
        const val ACTION_SNOOZE  = "com.notibook.SNOOZE"
        const val ACTION_DISMISS = "com.notibook.DISMISS"
        const val EXTRA_BOOK_ID  = "book_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
        if (bookId == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handle(context, intent.action, bookId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(context: Context, action: String?, bookId: Long) {
        val repo = (context.applicationContext as NotiBookApp).repository
        val book = repo.getBook(bookId) ?: return

        when (action) {
            ACTION_PREV -> {
                if (book.currentIndex > 0) {
                    val newIndex = book.currentIndex - 1
                    val sentence = repo.getSentence(bookId, newIndex) ?: return
                    repo.updatePosition(bookId, newIndex, sentence.chapter)
                    NotificationHelper.show(context, book, sentence)
                }
            }
            ACTION_NEXT -> {
                if (book.currentIndex < book.totalSentences - 1) {
                    val newIndex = book.currentIndex + 1
                    val sentence = repo.getSentence(bookId, newIndex) ?: return
                    repo.updatePosition(bookId, newIndex, sentence.chapter)
                    NotificationHelper.show(context, book, sentence)
                }
            }
            ACTION_SNOOZE -> {
                NotificationHelper.hide(context, bookId)
                scheduleSnooze(context, bookId)
            }
            ACTION_DISMISS -> {
                repo.updateNotificationActive(bookId, false)
                NotificationHelper.hide(context, bookId)
            }
        }
    }

    private fun scheduleSnooze(context: Context, bookId: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, SnoozeAlarmReceiver::class.java).apply {
            putExtra(SnoozeAlarmReceiver.EXTRA_BOOK_ID, bookId)
        }
        val pi = PendingIntent.getBroadcast(
            context, bookId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + 60L * 60_000L
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }
}

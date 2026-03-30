package com.notibook.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notibook.app.NotiBookApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Fired by AlarmManager 1 hour after the user snoozes a notification. */
class SnoozeAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
        if (bookId == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = (context.applicationContext as NotiBookApp).repository
                val book = repo.getBook(bookId) ?: return@launch
                if (!book.notificationActive) return@launch
                val sentence = repo.getSentence(bookId, book.currentIndex) ?: return@launch
                NotificationHelper.show(context, book, sentence)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

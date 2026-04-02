package com.notibook.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notibook.app.AlwaysReadingApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles taps on notification buttons (←, →, Dismiss).
 * Posts notification updates directly via NotificationHelper — no service required.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PREV    = "com.notibook.PREV"
        const val ACTION_NEXT    = "com.notibook.NEXT"
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
        val repo = (context.applicationContext as AlwaysReadingApp).repository
        val book = repo.getBook(bookId) ?: return

        when (action) {
            ACTION_PREV -> {
                var newIndex = book.currentIndex - 1
                while (newIndex >= 0) {
                    val s = repo.getSentence(bookId, newIndex) ?: break
                    if (s.type != "DIVIDER") {
                        repo.updatePosition(bookId, newIndex, s.chapter)
                        // Clear reader char offset so reader reopens at notification position
                        repo.clearReaderCharOffset(bookId)
                        NotificationHelper.show(context, book, s)
                        break
                    }
                    newIndex--
                }
            }
            ACTION_NEXT -> {
                var newIndex = book.currentIndex + 1
                while (newIndex < book.totalSentences) {
                    val s = repo.getSentence(bookId, newIndex) ?: break
                    if (s.type != "DIVIDER") {
                        repo.updatePosition(bookId, newIndex, s.chapter)
                        // Clear reader char offset so reader reopens at notification position
                        repo.clearReaderCharOffset(bookId)
                        NotificationHelper.show(context, book, s)
                        break
                    }
                    newIndex++
                }
            }
            ACTION_DISMISS -> {
                repo.updateNotificationActive(bookId, false)
                NotificationHelper.hide(context, bookId)
            }
        }
    }
}

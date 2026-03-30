package com.notibook.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notibook.app.NotiBookApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Restores active book notifications after device reboot. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = (context.applicationContext as NotiBookApp).repository
                val books = repo.getActiveBooks()
                for (book in books) {
                    val sentence = repo.getSentence(book.id, book.currentIndex) ?: continue
                    NotificationHelper.show(context, book, sentence)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

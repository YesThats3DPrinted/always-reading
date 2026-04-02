package com.notibook.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.notibook.app.notification.NotificationActionReceiver
import com.notibook.app.ui.navigation.AppNavigation
import com.notibook.app.ui.theme.AlwaysReadingTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startBookId = intent.getLongExtra(NotificationActionReceiver.EXTRA_BOOK_ID, -1L)
        setContent {
            AlwaysReadingTheme {
                AppNavigation(startBookId = startBookId)
            }
        }
    }

    /** Called when the app is already open and a notification is tapped. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val bookId = intent.getLongExtra(NotificationActionReceiver.EXTRA_BOOK_ID, -1L)
        if (bookId != -1L) {
            (application as AlwaysReadingApp).pendingOpenBookId.value = bookId
        }
    }
}

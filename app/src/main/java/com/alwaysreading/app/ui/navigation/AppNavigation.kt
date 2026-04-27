package com.alwaysreading.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.alwaysreading.app.AlwaysReadingApp
import com.alwaysreading.app.ui.library.LibraryScreen
import com.alwaysreading.app.ui.reader.EpubReaderScreen

@Composable
fun AppNavigation(startBookId: Long = -1L) {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as AlwaysReadingApp

    // Navigate to a specific book when the app is launched from a notification
    LaunchedEffect(startBookId) {
        if (startBookId != -1L) nav.navigate("reader/$startBookId")
    }

    // Navigate to a specific book when a notification is tapped while the app is already open
    val pendingBookId by app.pendingOpenBookId.collectAsState()
    LaunchedEffect(pendingBookId) {
        if (pendingBookId != -1L) {
            nav.navigate("reader/$pendingBookId") { popUpTo("library") }
            app.pendingOpenBookId.value = -1L
        }
    }

    NavHost(navController = nav, startDestination = "library") {

        composable("library") {
            LibraryScreen(
                onBookClick = { bookId -> nav.navigate("reader/$bookId") }
            )
        }

        composable(
            route = "reader/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
        ) { backStack ->
            val bookId = backStack.arguments!!.getLong("bookId")
            EpubReaderScreen(bookId = bookId, onClose = { nav.popBackStack() })
        }
    }
}

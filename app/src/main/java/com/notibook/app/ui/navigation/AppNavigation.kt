package com.notibook.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.notibook.app.ui.detail.BookDetailScreen
import com.notibook.app.ui.library.LibraryScreen

@Composable
fun AppNavigation() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = "library") {

        composable("library") {
            LibraryScreen(onBookClick = { bookId -> nav.navigate("detail/$bookId") })
        }

        composable(
            route = "detail/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
        ) { backStack ->
            val bookId = backStack.arguments!!.getLong("bookId")
            BookDetailScreen(bookId = bookId, onBack = { nav.popBackStack() })
        }
    }
}

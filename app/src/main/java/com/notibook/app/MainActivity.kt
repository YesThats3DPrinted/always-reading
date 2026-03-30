package com.notibook.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.notibook.app.ui.navigation.AppNavigation
import com.notibook.app.ui.theme.NotiBookTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotiBookTheme {
                AppNavigation()
            }
        }
    }
}

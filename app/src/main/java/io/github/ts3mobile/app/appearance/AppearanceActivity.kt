package io.github.ts3mobile.app.appearance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ts3mobile.app.Ts3MobileApplication
import io.github.ts3mobile.app.ui.theme.ApplicationTheme

class AppearanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = (application as Ts3MobileApplication).appearance
        setContent {
            ApplicationTheme {
                val state by preferences.state.collectAsStateWithLifecycle()
                AppearanceScreen(state, preferences::select, preferences::retry, preferences::reset, ::finish)
            }
        }
    }
}

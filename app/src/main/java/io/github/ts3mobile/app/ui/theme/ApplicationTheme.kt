package io.github.ts3mobile.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ts3mobile.app.Ts3MobileApplication

@Composable
internal fun ApplicationTheme(content: @Composable () -> Unit) {
    val application = LocalContext.current.applicationContext as Ts3MobileApplication
    val preference by application.appearance.state.collectAsStateWithLifecycle()
    Ts3MobileTheme(darkTheme = preference.mode.isDark(isSystemInDarkTheme()), content = content)
}

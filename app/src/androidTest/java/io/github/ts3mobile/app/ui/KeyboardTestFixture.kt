package io.github.ts3mobile.app.ui

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

internal fun AndroidComposeTestRule<*, *>.hideKeyboardAndWait() {
    val window = activity.window
    val ime = WindowInsetsCompat.Type.ime()
    runOnUiThread { WindowInsetsControllerCompat(window, window.decorView).hide(ime) }
    waitUntil(5_000) {
        runOnUiThread { ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(ime) == false }
    }
}

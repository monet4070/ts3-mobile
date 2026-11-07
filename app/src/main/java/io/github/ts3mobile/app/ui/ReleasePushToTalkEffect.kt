package io.github.ts3mobile.app.ui

import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView

/** Dialogs have their own window; loss of its focus must release a held/accessible PTT. */
@Composable
internal fun ReleasePushToTalkEffect(onPushToTalkChanged: (Boolean) -> Unit) {
    val view = LocalView.current
    val currentRelease by rememberUpdatedState(onPushToTalkChanged)
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (!focused) currentRelease(false) }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose {
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
            currentRelease(false)
        }
    }
}

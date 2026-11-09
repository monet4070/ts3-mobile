package io.github.ts3mobile.app.ui

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView

/** Dialog view/window offsets can differ from Activity insets, especially after the IME settles. */
@Composable
internal fun dialogVisibleBottomInsets(): WindowInsets {
    val view = LocalView.current
    var bottom by remember(view) { mutableIntStateOf(0) }
    DisposableEffect(view) {
        val frame = Rect()
        val location = IntArray(2)
        val listener =
            ViewTreeObserver.OnGlobalLayoutListener {
                view.getWindowVisibleDisplayFrame(frame)
                if (!frame.isEmpty && view.height > 0) {
                    view.getLocationOnScreen(location)
                    bottom = dialogBottomOcclusion(location[1], view.height, frame.bottom)
                }
            }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()
        onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return WindowInsets(bottom = bottom)
}

internal fun dialogBottomOcclusion(
    viewTop: Int,
    viewHeight: Int,
    visibleBottom: Int,
): Int {
    require(viewHeight >= 0)
    return (viewTop + viewHeight - visibleBottom).coerceIn(0, viewHeight)
}

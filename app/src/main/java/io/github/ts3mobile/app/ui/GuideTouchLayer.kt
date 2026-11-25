package io.github.ts3mobile.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

@Composable
internal fun GuideTouchLayer(
    anchor: Rect?,
    onBack: () -> Unit,
    card: @Composable () -> Unit,
) {
    BackHandler(onBack = onBack)
    var origin by remember { mutableStateOf(Offset.Unspecified) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.localToScreen(Offset.Zero) }) {
        val window = Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val target =
            anchor?.takeIf { origin.isSpecified }?.translate(-origin)?.intersect(window)
                ?.takeIf { it.width > 0f && it.height > 0f }
        GuideScrim(target)
        if (target == null) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures {} })
        } else {
            GuideTouchShield(Rect(window.left, window.top, window.right, target.top))
            GuideTouchShield(Rect(window.left, target.bottom, window.right, window.bottom))
            GuideTouchShield(Rect(window.left, target.top, target.left, target.bottom))
            GuideTouchShield(Rect(target.right, target.top, window.right, target.bottom))
        }
        val placement =
            when {
                target == null -> Alignment.BottomCenter
                target.center.y > window.center.y -> Alignment.TopCenter
                else -> Alignment.BottomCenter
            }
        val density = LocalDensity.current
        val insets = WindowInsets.safeDrawing
        val freeSpace =
            target?.let {
                if (placement == Alignment.TopCenter) {
                    it.top - insets.getTop(density)
                } else {
                    window.bottom - it.bottom - insets.getBottom(density)
                }
            } ?: window.height
        val height = minOf(maxHeight * 0.58f, (with(density) { freeSpace.toDp() } - 34.dp).coerceAtLeast(1.dp))
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = placement) {
            Panel(
                Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = height)
                    .pointerInput(Unit) { detectTapGestures {} },
            ) { card() }
        }
    }
}

@Composable
private fun GuideTouchShield(bounds: Rect) {
    if (bounds.width < 1f || bounds.height < 1f) return
    val density = LocalDensity.current
    Box(
        Modifier.absoluteOffset { IntOffset(bounds.left.toInt(), bounds.top.toInt()) }
            .requiredSize(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
            .pointerInput(Unit) { detectTapGestures {} },
    )
}

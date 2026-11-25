package io.github.ts3mobile.app.probe

import kotlin.math.roundToInt

internal data class OverlayDock(val right: Boolean, val height: Float)

internal data class OverlayPosition(val left: Int, val top: Int)

internal class OverlayLimits(
    private val left: Int,
    private val top: Int,
    right: Int,
    bottom: Int,
    width: Int,
    height: Int,
    headerHeight: Int,
) {
    private val lastLeft = (right - width).coerceAtLeast(left)
    private val lastTop = (bottom - height).coerceAtLeast(top)
    private val headerTravel = (bottom - headerHeight - top).coerceAtLeast(0)

    fun clamp(position: OverlayPosition): OverlayPosition =
        OverlayPosition(position.left.coerceIn(left, lastLeft), position.top.coerceIn(top, lastTop))

    fun place(dock: OverlayDock): OverlayPosition {
        val fraction = if (dock.height.isFinite()) dock.height.coerceIn(0f, 1f) else .5f
        return OverlayPosition(if (dock.right) lastLeft else left, (top + (headerTravel * fraction).roundToInt()).coerceAtMost(lastTop))
    }

    fun snap(position: OverlayPosition): OverlayDock {
        val current = clamp(position)
        val right = current.left - left >= lastLeft - current.left
        val fraction = if (headerTravel == 0) .5f else (current.top - top).toFloat() / headerTravel
        return OverlayDock(right, fraction)
    }
}

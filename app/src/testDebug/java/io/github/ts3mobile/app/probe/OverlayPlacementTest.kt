package io.github.ts3mobile.app.probe

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPlacementTest {
    @Test fun dragClampsToUsableBoundsAndSnapsToNearestEdge() {
        val limits = OverlayLimits(20, 50, 380, 770, 120, 52, 52)
        val anchor = limits.snap(OverlayPosition(-100, 800))
        assertEquals(OverlayDock(false, 1f), anchor)
        assertEquals(OverlayPosition(20, 718), limits.place(anchor))
        assertEquals(OverlayPosition(260, 50), limits.place(limits.snap(OverlayPosition(300, -10))))
    }

    @Test fun expansionAndRotationKeepTheSameEdgeAndRelativeHeight() {
        val compact = OverlayLimits(10, 25, 390, 780, 140, 54, 54)
        val original = OverlayPosition(250, 350)
        val anchor = compact.snap(original)
        assertEquals(original, compact.place(anchor))
        val expanded = OverlayLimits(10, 25, 390, 780, 254, 186, 54)
        assertEquals(OverlayPosition(136, 350), expanded.place(anchor))
        val wide = OverlayLimits(50, 10, 760, 375, 254, 186, 54)
        assertEquals(OverlayPosition(506, 154), wide.place(anchor))
    }

    @Test fun expandingNearTheBottomRestoresTheOriginalPositionOnCollapse() {
        val compact = OverlayLimits(10, 25, 390, 780, 140, 54, 54)
        val expanded = OverlayLimits(10, 25, 390, 780, 254, 186, 54)
        val original = OverlayPosition(250, 726)
        val anchor = compact.snap(original)
        assertEquals(OverlayPosition(136, 594), expanded.place(anchor))
        assertEquals(original, compact.place(anchor))
    }

    @Test fun oversizedCardAndInvalidDockNeverReturnAnOffscreenOrigin() {
        val limits = OverlayLimits(10, 40, 180, 150, 250, 200, 200)
        assertEquals(OverlayPosition(10, 40), limits.place(OverlayDock(true, Float.NaN)))
        assertEquals(OverlayPosition(10, 40), limits.clamp(OverlayPosition(80, 5)))
    }
}

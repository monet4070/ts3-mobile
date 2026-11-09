package io.github.ts3mobile.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DialogViewportTest {
    @Test fun dialogOffsetAndSettledKeyboardAreMeasuredInTheSameScreenCoordinates() {
        assertEquals(963, dialogBottomOcclusion(viewTop = 63, viewHeight = 1919, visibleBottom = 1019))
        assertEquals(188, dialogBottomOcclusion(viewTop = 63, viewHeight = 1919, visibleBottom = 1794))
    }

    @Test fun anAlreadyResizedWindowDoesNotReceiveTheKeyboardHeightAgain() {
        assertEquals(0, dialogBottomOcclusion(viewTop = 63, viewHeight = 956, visibleBottom = 1019))
        assertEquals(0, dialogBottomOcclusion(viewTop = 0, viewHeight = 1794, visibleBottom = 1794))
    }

    @Test fun offscreenAndEmptyViewsCannotProduceNegativeOrOversizedPadding() {
        assertEquals(0, dialogBottomOcclusion(-100, 500, 600))
        assertEquals(500, dialogBottomOcclusion(100, 500, 50))
        assertEquals(0, dialogBottomOcclusion(0, 0, 0))
    }
}

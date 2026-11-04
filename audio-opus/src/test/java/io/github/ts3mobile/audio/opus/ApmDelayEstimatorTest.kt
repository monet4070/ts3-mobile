package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApmDelayEstimatorTest {
    @Test fun combinesActualRenderAndCaptureQueuesWithTheDocumentedSigns() {
        val estimate = ApmDelayEstimator().estimate(RenderTiming(130 * MS, AudioTimingQuality.MEASURED), 100 * MS, 80 * MS, 100 * MS)
        assertEquals(50, estimate.milliseconds)
        assertEquals(AudioTimingQuality.MEASURED, estimate.quality)
        assertEquals(110 * MS, AudioSampleClock(480, 100 * MS).timeAt(960))
        assertNull(AudioSampleClock(0, 0).timeAt(96001))
    }

    @Test fun knownDelayFixtureSeparatesQueuesFromAcousticEchoDelay() {
        for (renderQueue in listOf(0L, 10L, 60L)) {
            for (captureQueue in listOf(0L, 20L, 40L)) {
                val estimate =
                    ApmDelayEstimator().estimate(
                        RenderTiming((100 + renderQueue) * MS, AudioTimingQuality.MEASURED),
                        100 * MS,
                        (100 - captureQueue) * MS,
                        100 * MS,
                    )
                assertEquals((renderQueue + captureQueue).toInt(), estimate.milliseconds)
            }
        }
    }

    @Test fun smoothsSmallChangesRejectsJumpsAndBoundsMissingClockEstimates() {
        val estimator = ApmDelayEstimator()
        val render = RenderTiming(130 * MS, AudioTimingQuality.MEASURED)
        assertEquals(50, estimator.estimate(render, 100 * MS, 80 * MS, 100 * MS).milliseconds)
        assertEquals(53, estimator.estimate(render.copy(presentationNanos = 140 * MS), 100 * MS, 80 * MS, 100 * MS).milliseconds)
        assertEquals(AudioTimingQuality.ESTIMATED, estimator.estimate(null, 0, null, 200 * MS).quality)
        assertNull(estimator.estimate(null, 0, null, 201 * MS).milliseconds)
        val jumped = estimator.estimate(render.copy(presentationNanos = 300 * MS), 100 * MS, 80 * MS, 100 * MS)
        assertTrue(jumped.discontinuity)
        assertNull(jumped.milliseconds)
    }

    @Test fun invalidFutureCaptureAndDelaysOutsideApiRangeAreUnavailable() {
        val estimator = ApmDelayEstimator()
        assertTrue(estimator.estimate(RenderTiming(0, AudioTimingQuality.MEASURED), 0, MS, 0).discontinuity)
        assertTrue(estimator.estimate(RenderTiming(501 * MS, AudioTimingQuality.MEASURED), 0, 0, 0).discontinuity)
        assertNull(estimator.estimate(null, 0, null, 0).milliseconds)
    }

    private companion object {
        const val MS = 1_000_000L
    }
}

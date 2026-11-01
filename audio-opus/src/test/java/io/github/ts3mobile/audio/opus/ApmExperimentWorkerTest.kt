package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApmExperimentWorkerTest {
    @Test fun reverseIsBoundedAndAlwaysRunsBeforeCaptureOnTheDspWorker() {
        val reference = RenderReferenceBuffer()
        val engine = Engine()
        val now = 100_000_000L
        worker(reference, engine, { now }).use { worker ->
            reference.start()
            repeat(3) { offer(reference, it * 480L, now) }
            val output = worker.process(ShortArray(480) { 1000 }, now - 20_000_000)!!
            assertArrayEquals(FloatArray(480) { 1000 / 32768f }, output, 0f)
            assertEquals(listOf("reset", "reverse", "reverse", "reverse", "capture:50"), engine.calls)
        }
        assertEquals(1, engine.closes)
    }

    @Test fun referenceBudgetFailuresResetAndFallBackOnceWithoutUnboundedReverseCalls() {
        val reference = RenderReferenceBuffer()
        val engine = Engine()
        val statuses = mutableListOf<ApmExperimentStatus>()
        worker(reference, engine, { 100_000_000L }, statuses).use { worker ->
            reference.start()
            repeat(3) {
                repeat(6) { index -> offer(reference, index * 480L, 100_000_000L) }
                worker.process(ShortArray(480), 100_000_000L)
            }
            assertEquals(ApmFallbackReason.REFERENCE, worker.status.reason)
            assertEquals(0, engine.calls.count { it == "reverse" })
            repeat(20) { worker.process(ShortArray(480), null) }
            assertEquals(1, statuses.count { it.reason == ApmFallbackReason.REFERENCE })
            assertEquals(1, engine.closes)
        }
    }

    @Test fun unavailableClockCannotRunExperimentalCaptureIndefinitely() {
        val reference = RenderReferenceBuffer()
        val engine = Engine()
        worker(reference, engine, { 100_000_000L }).use { worker ->
            repeat(9) { assertNull(worker.process(ShortArray(480), null)) }
            assertEquals(480, worker.process(ShortArray(480), null)!!.size)
            assertEquals(ApmFallbackReason.CLOCK, worker.status.reason)
            assertTrue(engine.calls.none { it.startsWith("capture") })
        }
    }

    @Test fun nativeFailureRestoresBaselineFromOriginalPcmAndNeverRetriesEngine() {
        val reference = RenderReferenceBuffer()
        val engine = Engine().apply { captureAction = { throw IllegalStateException("Synthetic native failure") } }
        worker(reference, engine, { 100_000_000L }).use { worker ->
            val input = ShortArray(480) { 1234 }
            assertArrayEquals(FloatArray(480) { 1234 / 32768f }, worker.process(input, 100_000_000L), 0f)
            assertEquals(ApmFallbackReason.NATIVE, worker.status.reason)
            worker.process(input, null)
            assertEquals(1, engine.calls.count { it.startsWith("capture") })
        }
    }

    @Test fun repeatedCpuOverrunsStopTheExperimentWithoutRestartingCapture() {
        var now = 100_000_000L
        val reference = RenderReferenceBuffer()
        val engine = Engine().apply { captureAction = { now += 6_000_000 } }
        worker(reference, engine, { now }).use { worker ->
            repeat(3) { worker.process(ShortArray(480), now) }
            assertEquals(ApmFallbackReason.CPU, worker.status.reason)
            assertEquals(1, engine.closes)
        }
    }

    @Test fun outputGenerationChangeDiscardsInFlightCaptureAndIdleNeverReplaysOldSound() {
        val reference = RenderReferenceBuffer()
        val engine = Engine()
        worker(reference, engine, { 100_000_000L }).use { worker ->
            reference.start()
            offer(reference, 0, 100_000_000L)
            engine.captureAction = reference::discard
            assertNull(worker.process(ShortArray(480), 100_000_000L))
            engine.captureAction = {}
            worker.process(ShortArray(480), 100_000_000L)
            assertEquals(2, engine.calls.count { it == "reset" })
            assertTrue(engine.reversed.last().all { it == 0f })
        }
    }

    @Test fun bluetoothUnknownSourceOrPlatformEffectsPreventNativeConstruction() {
        for (policy in listOf(
            ApmExperimentPolicy(ApmRoute.BLUETOOTH, true, true),
            ApmExperimentPolicy(ApmRoute.UNKNOWN, true, true),
            ApmExperimentPolicy(ApmRoute.BUILTIN, false, true),
            ApmExperimentPolicy(ApmRoute.BUILTIN, true, false),
        )) {
            ApmExperimentWorker(RenderReferenceBuffer(), policy, { error("Native must not start") }, { { FloatArray(480) } }, {}).use {
                assertEquals(ApmFallbackReason.POLICY, it.status.reason)
            }
        }
    }

    @Test fun initializationFailureFallsBackButBaselineFailurePropagatesWithoutRetry() {
        val reference = RenderReferenceBuffer()
        ApmExperimentWorker(reference, ELIGIBLE, { throw UnsatisfiedLinkError("Synthetic") }, { { FloatArray(480) } }, {}).use {
            assertEquals(ApmFallbackReason.NATIVE, it.status.reason)
        }
        var attempts = 0
        val engine = Engine().apply { captureAction = { error("Synthetic") } }
        val worker =
            ApmExperimentWorker(reference, ELIGIBLE, { engine }, {
                attempts++
                error("Baseline initialization failed")
            }, {}, { 100_000_000L })
        worker.use {
            assertThrows(IllegalStateException::class.java) { it.process(ShortArray(480), 100_000_000L) }
            assertEquals(1, attempts)
        }
    }

    @Test fun routePolicyChangeClosesApmAndDoesNotAutomaticallyReenableOrRestartCapture() {
        val reference = RenderReferenceBuffer()
        val engine = Engine()
        worker(reference, engine, { 100_000_000L }).use { worker ->
            worker.updatePolicy(ApmExperimentPolicy(ApmRoute.BLUETOOTH, true, true))
            assertEquals(ApmFallbackReason.POLICY, worker.status.reason)
            assertEquals(1, engine.closes)
            worker.updatePolicy(ELIGIBLE)
            worker.process(ShortArray(480), 100_000_000L)
            assertTrue(engine.calls.isEmpty())
        }
    }

    @Test fun sustainedEstimatedRenderClockFallsBackRatherThanPresentingItAsMeasured() {
        val reference = RenderReferenceBuffer()
        val engine = Engine()
        var now = 100_000_000L
        worker(reference, engine, { now }).use { worker ->
            reference.start()
            repeat(10) { frame ->
                reference.accepted(
                    ShortArray(480),
                    0,
                    480,
                    frame * 480L,
                    now,
                    RenderTiming(now + 10_000_000, AudioTimingQuality.ESTIMATED),
                )
                worker.process(ShortArray(480), now)
                now += 10_000_000
            }
            assertEquals(ApmFallbackReason.CLOCK, worker.status.reason)
            assertEquals(AudioTimingQuality.UNAVAILABLE, worker.delayEstimate.quality)
        }
    }

    private fun worker(
        reference: RenderReferenceBuffer,
        engine: Engine,
        clock: () -> Long,
        statuses: MutableList<ApmExperimentStatus> = mutableListOf(),
    ) = ApmExperimentWorker(reference, ELIGIBLE, { engine }, { { pcm -> FloatArray(pcm.size) { pcm[it] / 32768f } } }, statuses::add, clock)

    private fun offer(
        reference: RenderReferenceBuffer,
        frame: Long,
        now: Long,
    ) {
        reference.accepted(
            ShortArray(480) { 1234 },
            0,
            480,
            frame,
            now,
            RenderTiming(now + 10_000_000L + frame * 1_000_000_000L / 48_000, AudioTimingQuality.MEASURED),
        )
    }

    private class Engine : ApmProcessor {
        val calls = mutableListOf<String>()
        val reversed = mutableListOf<FloatArray>()
        var closes = 0
        var captureAction: () -> Unit = {}

        override fun reverse(pcm: FloatArray) {
            calls += "reverse"
            reversed += pcm.copyOf()
        }

        override fun capture(
            pcm: FloatArray,
            delayMs: Int,
        ): FloatArray {
            calls += "capture:$delayMs"
            captureAction()
            return pcm
        }

        override fun reset() {
            calls += "reset"
        }

        override fun close() {
            closes++
        }
    }

    private companion object {
        val ELIGIBLE = ApmExperimentPolicy(ApmRoute.BUILTIN, true, true)
    }
}

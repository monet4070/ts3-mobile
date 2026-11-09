package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.AudioDiagnosticEventKind
import io.github.ts3mobile.audio.opus.AudioReceiveCounter
import io.github.ts3mobile.audio.opus.AudioReceiveDiagnostics
import io.github.ts3mobile.audio.opus.AudioReceivePeak
import io.github.ts3mobile.audio.opus.AudioRouteKind
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AudioDiagnosticControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun automaticSamplingSurvivesAbruptRecreationAndExplicitFinalSnapshotSurvivesDisconnect() =
        runTest {
            val file = temporary.newFile()
            val clock = { 1_000 + testScheduler.currentTime }
            val repository = AudioDiagnosticRepository(file, backgroundScope, clock)
            var count = 0L
            val controller =
                AudioDiagnosticController(
                    repository,
                    { frame(++count) },
                    { AudioDiagnosticContext() },
                    clock,
                    { testScheduler.currentTime },
                )
            controller.start(backgroundScope)
            runCurrent()
            advanceTimeBy(5_101)
            runCurrent()
            val saved = AudioDiagnosticStorage(file).read()
            assertEquals(2, saved.samples.size)
            assertEquals(5_000L, saved.samples.last().windowMs)
            controller.onStopped(frame(3).copy(active = false))
            controller.serviceStopped()
            advanceTimeBy(1_001)
            runCurrent()
            val complete = AudioDiagnosticStorage(file).read()
            assertEquals(3L, complete.samples.last().snapshot.counters[AudioReceiveCounter.SUBMITTED])
            assertTrue(complete.events.any { it.kind == AudioDiagnosticEvent.STOPPED })
            assertTrue(complete.sessions.single().endedMs > 0)
        }

    @Test fun finalCallbackFromOldServiceCannotEndOrOverwriteReplacementSession() =
        runTest {
            val repository = AudioDiagnosticRepository(temporary.newFile(), backgroundScope, { 1_000 })
            val old = AudioDiagnosticController(repository, { frame(3) }, { AudioDiagnosticContext() }, { 1_000 }, { 1_000 })
            val next = AudioDiagnosticController(repository, { frame(7) }, { AudioDiagnosticContext() }, { 2_000 }, { 2_000 })
            old.sampleNow()
            old.serviceStopped()
            next.sampleNow()
            old.onStopped(frame(5).copy(active = false))
            assertEquals(2, repository.status.value.sessions)
            val report = repository.export()
            assertTrue(report.contains("\"id\":2,\"startedAtMs\":1000,\"endedAtMs\":0"))
            assertTrue(report.contains("\"session\":1"))
            assertTrue(report.contains("\"session\":2"))
        }

    @Test fun markWorksAfterDisconnectAndFixedRouteEventsNeverIncludeDeviceOrServerNames() =
        runTest {
            val repository = AudioDiagnosticRepository(temporary.newFile(), backgroundScope, { 1_000 })
            assertFalse(repository.mark())
            val controller = AudioDiagnosticController(repository, { frame(1) }, { AudioDiagnosticContext() }, { 1_000 }, { 1_000 })
            controller.sampleNow()
            controller.observe(AudioDiagnosticContext(selectedRoute = AudioRouteKind.BLUETOOTH, switching = true))
            controller.onEvent(AudioDiagnosticEventKind.ACTUAL_ROUTE, AudioRouteKind.BLUETOOTH.ordinal.toLong())
            controller.onStopped(frame(2).copy(active = false))
            assertTrue(repository.mark())
            val report = repository.export()
            assertTrue(report.contains("USER_MARK"))
            assertTrue(report.contains("ACTUAL_ROUTE"))
            assertFalse(report.contains("deviceName"))
            assertFalse(report.contains("networkKey"))
            assertFalse(report.contains("encodedData"))
        }

    @Test fun storageFailureIsVisibleAndDoesNotDropTheInMemoryReportOrThrowIntoAudioCallback() =
        runTest {
            val parent = temporary.newFile("not-a-directory")
            val repository = AudioDiagnosticRepository(java.io.File(parent, "audio.tsv"), backgroundScope, { 1_000 })
            val controller = AudioDiagnosticController(repository, { frame(12) }, { AudioDiagnosticContext() }, { 1_000 }, { 1_000 })
            controller.sampleNow()
            advanceTimeBy(101)
            runCurrent()
            assertTrue(repository.status.value.failedWrites > 0)
            assertTrue(repository.export().contains("\"counters\":[12,"))
        }

    @Test fun transportOnlyObservationCannotChangeNetworkIdentityOrForceReconnect() {
        val tracker = DefaultNetworkStateTracker()
        tracker.onAvailable("opaque")
        val first = tracker.onCapabilitiesChanged("opaque", true, DiagnosticNetwork.CELLULAR)
        val monitor = ConnectionMonitor { 0 }
        assertEquals(NetworkTransition.UNCHANGED, monitor.networkChanged(first))
        val second = tracker.onCapabilitiesChanged("opaque", true, DiagnosticNetwork.VPN)
        assertEquals(first.generation, second.generation)
        assertEquals(NetworkTransition.UNCHANGED, monitor.networkChanged(second))
        assertTrue(second.isAvailable)
    }

    @Test fun polledFinalSnapshotBeforeCallbackClosesOnceAndRetainsItsPeak() =
        runTest {
            val file = temporary.newFile()
            val clock = { 1_000 + testScheduler.currentTime }
            val repository = AudioDiagnosticRepository(file, backgroundScope, clock)
            var snapshot = frame(1)
            val controller =
                AudioDiagnosticController(repository, { snapshot }, { AudioDiagnosticContext() }, clock, { testScheduler.currentTime })
            controller.onStarted(snapshot)
            advanceTimeBy(5_000)
            snapshot = frame(10).copy(active = false)
            controller.sampleNow()
            controller.onStopped(snapshot)
            advanceTimeBy(1_001)
            runCurrent()
            val stored = AudioDiagnosticStorage(file).read()
            assertEquals(2, stored.samples.size)
            assertEquals(150_000_000L, stored.samples.last().snapshot.peaks[AudioReceivePeak.ARRIVAL_GAP_NS])
            assertEquals(1, stored.events.count { it.kind == AudioDiagnosticEvent.STOPPED })
        }

    private fun frame(count: Long) =
        AudioReceiveDiagnostics(
            1,
            true,
            mapOf(AudioReceiveCounter.SUBMITTED to count),
            mapOf(AudioReceivePeak.ARRIVAL_GAP_NS to 150_000_000),
        )
}

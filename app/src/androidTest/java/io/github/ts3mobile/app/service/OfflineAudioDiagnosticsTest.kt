package io.github.ts3mobile.app.service

import android.content.ContextWrapper
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.Ts3MobileApplication
import io.github.ts3mobile.audio.opus.AudioReceiveCounter
import io.github.ts3mobile.audio.opus.AudioReceiveDiagnostics
import io.github.ts3mobile.audio.opus.OpusAudioPlayer
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OfflineAudioDiagnosticsTest {
    @Test fun retiringServiceDoesNotWaitForAnInFlightWriteAndReplacementHistoryStaysOrdered() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = context.cacheDir.resolve("journal-order-${System.nanoTime()}").apply { mkdirs() }
            val file = directory.resolve("events.tsv")
            val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val retiringScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val journal = DiagnosticJournal(file)
            val blockedWrite = CompletableDeferred<Unit>()
            val replacementSaved = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val firstWriteReturned = AtomicBoolean(false)
            val writer =
                SessionDiagnosticWriter(journal, processScope) {
                    if (blockedWrite.complete(Unit)) {
                        release.await(10, TimeUnit.SECONDS)
                        firstWriteReturned.set(true)
                    }
                    journal.persist()
                    if (file.readText().contains("SERVICE_CREATED\t2")) replacementSaved.complete(Unit)
                }
            try {
                val old = SessionDiagnostics(context, retiringScope, writer)
                old.record(DiagnosticEventKind.SERVICE_CREATED, 1)
                withTimeout(3_000) { blockedWrite.await() }
                withContext(Dispatchers.Main) {
                    old.close()
                    retiringScope.cancel()
                    old.record(DiagnosticEventKind.TASK_REMOVED)
                    old.close()
                    val replacement = SessionDiagnostics(context, processScope, writer)
                    replacement.record(DiagnosticEventKind.SERVICE_CREATED, 2)
                    assertFalse(firstWriteReturned.get())
                }
                release.countDown()
                withTimeout(3_000) { replacementSaved.await() }
                assertEquals(
                    listOf("SERVICE_CREATED\t1", "SERVICE_DESTROYED\t0", "SERVICE_CREATED\t2"),
                    file.readLines().map { it.substringAfter('\t') },
                )
            } finally {
                release.countDown()
                retiringScope.cancel()
                processScope.cancel()
                directory.deleteRecursively()
            }
        }

    @Test fun checkpointReopensOfflineAndCombinedReportKeepsOldFieldsAndNumericHistory() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "offline-audio-${System.nanoTime()}").apply { mkdirs() }
        val file = File(folder, "audio.tsv")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val first = AudioDiagnosticRepository(file, firstScope)
            val id = first.begin()
            first.sample(
                AudioDiagnosticSample(
                    System.currentTimeMillis(),
                    1_000,
                    5_000,
                    id,
                    AudioReceiveDiagnostics(1, true, mapOf(AudioReceiveCounter.SUBMITTED to 42)),
                    AudioDiagnosticContext(network = DiagnosticNetwork.CELLULAR),
                ),
            )
            assertTrue(first.mark())
            runBlocking { withTimeout(3_000) { first.status.first { it.lastPersistedTimeMs > 0 } } }
            firstScope.cancel() // Simulate an exit without a final callback.
            val restarted = AudioDiagnosticRepository(file, secondScope)
            val context =
                object : ContextWrapper(base) {
                    override fun getNoBackupFilesDir(): File = folder
                }
            val journal = DiagnosticJournal(File(folder, "session-events.tsv"))
            val diagnostics = SessionDiagnostics(context, secondScope, SessionDiagnosticWriter(journal, secondScope))
            val report = JSONObject(diagnostics.export(DiagnosticsSnapshot(voiceFramesReceived = 42), restarted.export()))
            assertEquals("ts3-mobile-report/v2", report.getString("schema"))
            assertEquals(42L, report.getJSONObject("session").getLong("voiceFramesReceived"))
            assertTrue(report.has("history"))
            val audio = report.getJSONObject("audio")
            assertEquals("ts3-mobile-audio/v1", audio.getString("schema"))
            val keys = audio.getJSONArray("counterKeys")
            val submitted = (0 until keys.length()).first { keys.getString(it) == "SUBMITTED" }
            assertEquals(42L, audio.getJSONArray("samples").getJSONObject(0).getJSONArray("counters").getLong(submitted))
            assertTrue(audio.getJSONArray("events").toString().contains("PREVIOUS_RUN_INTERRUPTED"))
            assertTrue(audio.getJSONArray("events").toString().contains("USER_MARK"))
            assertFalse(report.toString().contains("encodedData"))
            diagnostics.close()
        } finally {
            firstScope.cancel()
            secondScope.cancel()
            folder.deleteRecursively()
        }
    }

    @Test fun theProcessOwnedArchiveIsLoadedOffTheCallerAndStaysShared() =
        runBlocking {
            val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Ts3MobileApplication
            // The lazy load starts on first access, on the application's IO dispatcher.
            val repository = withTimeout(5_000) { application.audioDiagnostics.await() }
            assertSame(repository, withTimeout(5_000) { application.audioDiagnostics.await() })
            assertTrue(repository.export().contains("\"schema\":\"ts3-mobile-audio/v1\""))
            val onMainThread =
                withContext(application.audioDiagnosticDispatcher) { Looper.myLooper() == Looper.getMainLooper() }
            assertFalse("The archive read must run outside the main thread", onMainThread)
        }

    @Test fun twoPlayerGenerationsBeforeRepositoryAttachmentProduceAnExplicitGap() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val folder = context.cacheDir.resolve("offline-delayed-${System.nanoTime()}").apply { mkdirs() }
            val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val file = File(folder, "audio.tsv")
            val player = OpusAudioPlayer(context)
            val loading = CompletableDeferred<AudioDiagnosticRepository>()
            try {
                lateinit var adapter: ServiceAudioDiagnostics
                val attachment =
                    AudioDiagnosticsAttachment(loading, Dispatchers.Main.immediate, { false }) { repository ->
                        adapter =
                            ServiceAudioDiagnostics(
                                repository, serviceScope, MutableStateFlow(TeamSpeakServiceState()),
                                MutableStateFlow(DefaultNetworkState()), player,
                            )
                    }
                attachment.start(serviceScope)
                repeat(2) {
                    player.start()
                    player.stop()
                }
                val repository = AudioDiagnosticRepository(file, processScope)
                loading.complete(repository)
                withTimeout(3_000) { attachment.awaitReady() }
                val report = JSONObject(adapter.export())
                assertEquals(1, report.getJSONArray("sessions").length())
                val events = report.getJSONArray("events")
                val gap =
                    (0 until events.length()).map(events::getJSONObject).single { it.getString("kind") == "PLAYBACK_GENERATIONS_MISSED" }
                assertEquals(1L, gap.getLong("value"))
                adapter.close()
            } finally {
                player.close()
                serviceScope.cancel()
                processScope.cancel()
                folder.deleteRecursively()
            }
        }

    @Test fun productionServiceAdapterRetainsStoppedPlayerWithoutAnyServerMicrophoneOrAudibleOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "offline-adapter-${System.nanoTime()}").apply { mkdirs() }
        val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val services = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val player = OpusAudioPlayer(context)
        try {
            val repository = AudioDiagnosticRepository(File(folder, "audio.tsv"), io)
            val state = MutableStateFlow(TeamSpeakServiceState(status = ConnectionStatus(ConnectionPhase.CONNECTED)))
            val network = MutableStateFlow(DefaultNetworkState(isAvailable = true, transport = DiagnosticNetwork.CELLULAR))
            val adapter = ServiceAudioDiagnostics(repository, services, state, network, player)
            player.start() // No frames: no AudioTrack is created and no microphone is opened.
            assertTrue(adapter.mark())
            player.stop()
            runBlocking { withTimeout(3_000) { repository.status.first { it.lastPersistedTimeMs > 0 } } }
            adapter.close()
            val audio = JSONObject(adapter.export())
            assertEquals(1, audio.getJSONArray("sessions").length())
            assertTrue(audio.getJSONArray("events").toString().contains("STOPPED"))
            assertTrue(audio.getJSONArray("events").toString().contains("USER_MARK"))
        } finally {
            player.close()
            services.cancel()
            io.cancel()
            folder.deleteRecursively()
        }
    }
}

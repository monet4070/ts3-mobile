package io.github.ts3mobile.app.service

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.audio.opus.AudioReceiveCounter
import io.github.ts3mobile.audio.opus.AudioReceiveDiagnostics
import io.github.ts3mobile.audio.opus.OpusAudioPlayer
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OfflineAudioDiagnosticsTest {
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
            val diagnostics = SessionDiagnostics(context, secondScope)
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

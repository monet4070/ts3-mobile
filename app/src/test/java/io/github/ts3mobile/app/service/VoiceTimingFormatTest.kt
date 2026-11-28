package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.ActualAudioDevice
import io.github.ts3mobile.audio.opus.AudioReceiveDiagnostics
import io.github.ts3mobile.protocol.VoiceDelayWindow
import io.github.ts3mobile.protocol.VoiceTimingCollector
import io.github.ts3mobile.protocol.VoiceTimingWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTimingFormatTest {
    private val histogram = VoiceDelayWindow(List(VoiceTimingCollector.delayUpperBoundsUs.size + 1) { if (it == 2) 5L else 0L })

    @Test fun optionalTimingRoundTripsAndDoesNotChangeLegacySamples() {
        val base = AudioDiagnosticSample(10, 20, 5, 1, AudioReceiveDiagnostics(1, true), AudioDiagnosticContext())
        val legacy = AudioDiagnosticFormat.sample(base)
        assertEquals(20, legacy.split('\t').size)
        val parsedLegacy = AudioDiagnosticFormat.parseSample(legacy.split('\t'))
        assertEquals(legacy, AudioDiagnosticFormat.sample(parsedLegacy))
        val withTiming = base.copy(voiceTiming = VoiceTimingWindow(true, histogram, histogram, 3, 4, 1))
        val parsedWithTiming = AudioDiagnosticFormat.parseSample(AudioDiagnosticFormat.sample(withTiming).split('\t'))
        assertEquals(parsedLegacy.copy(voiceTiming = withTiming.voiceTiming), parsedWithTiming)
        val report = AudioDiagnosticFormat.json(AudioDiagnosticArchiveState(samples = listOf(base, withTiming)), 10L)
        assertTrue(report.contains("\"p95UpperUs\":2000"))
        assertTrue(report.contains("\"voiceTimingUpperBoundsUs\""))
        assertFalse(report.contains("encodedAtNanos"))
    }

    @Test fun corruptedHistogramAndUnsupportedTimestampClaimsAreRejected() {
        val valid = VoiceTimingFormat.row(VoiceTimingWindow(true, histogram, histogram, 3, 4, 1))
        listOf(valid.replaceFirst("true", "false"), valid.replaceFirst("0", "10000"), valid + ";extra").forEach { row ->
            assertThrows(IllegalArgumentException::class.java) { VoiceTimingFormat.parse(row) }
        }
    }

    @Test fun persistedDeviceSnapshotDistinguishesUnknownInputAndNotRecording() {
        val devices = ActualAudioDevices(output = ActualAudioDevice.BLUETOOTH_A2DP, recording = true)
        val context = AudioDiagnosticContext(actualDevices = devices)
        val sample = AudioDiagnosticSample(10, 20, 5, 1, AudioReceiveDiagnostics(1, true), context)
        val roundTrip = AudioDiagnosticFormat.parseSample(AudioDiagnosticFormat.sample(sample).split('\t'))
        assertEquals(devices, roundTrip.context.actualDevices)
        assertEquals(ActualAudioDevices(output = devices.output), devices.activeInput(capturing = false))
        val report = AudioDiagnosticFormat.json(AudioDiagnosticArchiveState(samples = listOf(sample)), 10)
        assertTrue(report.contains("\"actualOutputDevice\":\"BLUETOOTH_A2DP\""))
        assertTrue(report.contains("\"actualInputDevice\":\"UNKNOWN\""))
        assertTrue(report.contains("\"systemMicMuted\":null"))
    }
}

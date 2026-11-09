package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.AudioReceiveCounter
import io.github.ts3mobile.audio.opus.AudioReceiveDiagnostics
import io.github.ts3mobile.audio.opus.AudioReceivePeak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioDiagnosticArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun checkpointRoundTripsCountersPeaksMarksAndCompletedSessionAfterRecreation() {
        val archive = AudioDiagnosticArchive()
        val id = archive.begin(1_000)
        archive.sample(sample(id, 6_000, 17))
        archive.event(AudioDiagnosticEntry(6_001, id, AudioDiagnosticEvent.USER_MARK))
        archive.end(id, 6_100)
        val storage = AudioDiagnosticStorage(temporary.newFile())
        val checkpoint = archive.checkpoint()
        storage.write(checkpoint, 6_200)
        archive.saved(checkpoint, 6_200)
        val restored = AudioDiagnosticArchive(storage.read())
        assertEquals(archive.state(), restored.state())
        assertEquals(1, restored.status().marks)
        assertEquals(17L, restored.state().samples.single().snapshot.counters[AudioReceiveCounter.SUBMITTED])
        assertTrue(AudioDiagnosticFormat.json(restored.state(), 7_000).contains("USER_MARK"))
    }

    @Test fun interruptedProcessRetainsLastCheckpointAndMarksUnknownEndTimeAsInterrupted() {
        val archive = AudioDiagnosticArchive()
        val id = archive.begin(1_000)
        archive.sample(sample(id, 6_000, 20))
        val storage = AudioDiagnosticStorage(temporary.newFile())
        storage.write(archive.checkpoint(), 6_100)
        val restarted = AudioDiagnosticArchive(storage.read())
        restarted.recoverInterrupted()
        assertEquals(6_000L, restarted.state().sessions.single().endedMs)
        assertEquals(AudioDiagnosticEvent.PREVIOUS_RUN_INTERRUPTED, restarted.state().events.last().kind)
        val next = restarted.begin(100_000)
        assertTrue(next > id)
        assertEquals(20L, restarted.state().samples.single().snapshot.counters[AudioReceiveCounter.SUBMITTED])
    }

    @Test fun retentionKeepsThreeSessionsAndEachLastTenMinutesWithoutUnboundedGrowth() {
        val archive = AudioDiagnosticArchive()
        repeat(4) { number ->
            val id = archive.begin((number + 1) * 1_000L)
            repeat(200) { index -> archive.sample(sample(id, 10_000L + index * 5_000, index.toLong())) }
            repeat(100) { archive.event(AudioDiagnosticEntry(999_998, id, AudioDiagnosticEvent.OUTPUT_CREATED)) }
            repeat(100) { archive.event(AudioDiagnosticEntry(999_999, id, AudioDiagnosticEvent.USER_MARK)) }
            archive.end(id, 1_100_000)
        }
        val state = archive.state()
        assertEquals(listOf(2L, 3L, 4L), state.sessions.map { it.id })
        assertEquals(360, state.samples.size)
        assertEquals(192, state.events.size)
        assertTrue(state.samples.all { it.snapshot.counters[AudioReceiveCounter.SUBMITTED]!! >= 80 })
        assertEquals(320L, state.trimmedSamples)
        val storage = AudioDiagnosticStorage(temporary.newFile())
        storage.write(archive.checkpoint(), 1_200_000)
        assertEquals(state.samples, storage.read().samples)
    }

    @Test fun appendCheckpointsAndTruncatedOrMaliciousTailDoNotDestroyEarlierValidEvidence() {
        val archive = AudioDiagnosticArchive()
        val id = archive.begin(1_000)
        val file = temporary.newFile()
        val storage = AudioDiagnosticStorage(file)
        var checkpoint = archive.checkpoint()
        storage.write(checkpoint, 2_000)
        archive.saved(checkpoint, 2_000)
        archive.sample(sample(id, 6_000, 1))
        checkpoint = archive.checkpoint()
        assertFalse(checkpoint.rewrite)
        storage.write(checkpoint, 6_100)
        file.appendText("E\t6200\t1\tpassword=secret.invalid\t0\nP\tpartial")
        val restored = storage.read()
        assertEquals(1, restored.samples.size)
        assertTrue(restored.invalidRecords >= 2)
        val report = AudioDiagnosticFormat.json(restored, 7_000)
        assertFalse(report.contains("password"))
        assertFalse(report.contains("secret.invalid"))
    }

    @Test fun maximumNumericExportFitsClipboardTransactionAndFileRotationBound() {
        val archive = AudioDiagnosticArchive()
        repeat(3) {
            val id = archive.begin(it + 1L)
            repeat(120) { index ->
                archive.sample(
                    sample(id, index + 1L, Long.MAX_VALUE).copy(
                        snapshot =
                            AudioReceiveDiagnostics(
                                1,
                                true,
                                AudioReceiveCounter.entries.associateWith { Long.MAX_VALUE },
                                AudioReceivePeak.entries.associateWith { Long.MAX_VALUE },
                            ),
                    ),
                )
            }
        }
        val report = AudioDiagnosticFormat.json(archive.state(), Long.MAX_VALUE)
        assertTrue(
            "UTF-16 clipboard payload plus existing report must remain below Binder limit: ${report.length}",
            report.length * 2 < 850_000,
        )
        val file = temporary.newFile()
        AudioDiagnosticStorage(file).write(archive.checkpoint(), Long.MAX_VALUE)
        assertTrue(file.length() <= AudioDiagnosticStorage.MAX_FILE_BYTES)
    }

    @Test fun oversizedOrUnknownSchemaIsReportedWithoutEchoingPrivateText() {
        val file = temporary.newFile()
        file.writeText("private-secret")
        assertEquals(1L, AudioDiagnosticStorage(file).read().invalidRecords)
        file.writeBytes(ByteArray(AudioDiagnosticStorage.MAX_FILE_BYTES.toInt() + 1))
        assertEquals(1L, AudioDiagnosticStorage(file).read().invalidRecords)
    }

    @Test fun acknowledgingAnOlderWriteCannotDiscardNewerInMemoryEvidence() {
        val archive = AudioDiagnosticArchive()
        val id = archive.begin(1_000)
        val writing = archive.checkpoint()
        archive.sample(sample(id, 6_000, 10))
        archive.saved(writing, 2_000)
        val next = archive.checkpoint()
        assertTrue(next.rows.any { it.startsWith("P\t") })
        assertEquals(10L, next.state.samples.single().snapshot.counters[AudioReceiveCounter.SUBMITTED])
    }

    @Test fun fullAppendFileCompactsToTheRetainedArchiveInsteadOfGrowingWithoutBound() {
        val archive = AudioDiagnosticArchive()
        val id = archive.begin(1_000)
        val file = temporary.newFile()
        val storage = AudioDiagnosticStorage(file)
        val first = archive.checkpoint()
        storage.write(first, 2_000)
        archive.saved(first, 2_000)
        val filler = "E\t2000\t1\tOUTPUT_CREATED\t0\n"
        file.appendText(filler.repeat(((AudioDiagnosticStorage.MAX_FILE_BYTES - file.length()) / filler.length).toInt()))
        archive.sample(sample(id, 6_000, 12))
        storage.write(archive.checkpoint(), 6_100)
        assertTrue(file.length() < 16_384)
        assertEquals(12L, storage.read().samples.single().snapshot.counters[AudioReceiveCounter.SUBMITTED])
        assertEquals(0L, storage.read().invalidRecords)
    }

    @Test fun outputEventChurnCannotEvictMarksAndRecoveryKeepsTheirReservation() {
        val archive = AudioDiagnosticArchive()
        val id = archive.begin(1_000)
        archive.event(AudioDiagnosticEntry(1_500, id, AudioDiagnosticEvent.USER_MARK))
        repeat(200) { archive.event(AudioDiagnosticEntry(2_000L + it, id, AudioDiagnosticEvent.OUTPUT_CREATED)) }
        assertEquals(1, archive.status().marks)
        assertTrue(archive.state().events.any { it.kind == AudioDiagnosticEvent.USER_MARK && it.timeMs == 1_500L })
        val storage = AudioDiagnosticStorage(temporary.newFile())
        storage.write(archive.checkpoint(), 3_000)
        val reopened = AudioDiagnosticArchive(storage.read())
        assertEquals(1, reopened.status().marks)
        assertTrue(reopened.state().events.size <= AudioDiagnosticArchive.MAX_EVENTS_PER_SESSION)
        repeat(20) { reopened.event(AudioDiagnosticEntry(4_000L + it, id, AudioDiagnosticEvent.USER_MARK)) }
        assertEquals(16, reopened.status().marks)
        assertFalse(reopened.state().events.any { it.kind == AudioDiagnosticEvent.USER_MARK && it.timeMs == 1_500L })
    }

    private fun sample(
        id: Long,
        now: Long,
        submitted: Long,
    ) = AudioDiagnosticSample(
        now,
        now,
        5_000,
        id,
        AudioReceiveDiagnostics(
            1,
            true,
            mapOf(AudioReceiveCounter.SUBMITTED to submitted),
            mapOf(AudioReceivePeak.WORKER_GAP_NS to 90_000_000),
        ),
        AudioDiagnosticContext(network = DiagnosticNetwork.CELLULAR, cpuLockHeld = true),
    )
}

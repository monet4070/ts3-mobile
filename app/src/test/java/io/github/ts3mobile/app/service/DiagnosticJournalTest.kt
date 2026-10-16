package io.github.ts3mobile.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun callbackAppendIsExportableBeforeItsCoalescedDiskFlush() {
        val file = temporaryFolder.newFile("events.tsv")
        val journal = DiagnosticJournal(file)
        journal.append(DiagnosticEventKind.CONNECTED)
        assertTrue(journal.toRedactedJson().contains("CONNECTED"))
        assertTrue(file.readText().isEmpty())
        journal.persist()
        assertTrue(DiagnosticJournal(file).toRedactedJson().contains("CONNECTED"))
    }

    @Test
    fun eventsAreBoundedAndSurviveRecreation() {
        val file = temporaryFolder.newFile("events.tsv")
        val journal = DiagnosticJournal(file, { 100L }, capacity = 2)
        journal.record(DiagnosticEventKind.SERVICE_CREATED)
        journal.record(DiagnosticEventKind.CONNECTED)
        journal.record(DiagnosticEventKind.USER_DISCONNECT)
        val export = DiagnosticJournal(file).toRedactedJson()
        assertFalse(export.contains("SERVICE_CREATED"))
        assertTrue(export.contains("CONNECTED"))
        assertTrue(export.contains("USER_DISCONNECT"))
        assertTrue(file.length() < 32_768L)
    }

    @Test
    fun corruptAndUnknownTextNeverEntersExport() {
        val file = temporaryFolder.newFile("events.tsv")
        file.writeText("0\tCONNECTED\t0\n1\tpassword=secret.invalid\t0\nnot a timestamp\tERROR\t0\n")
        val export = DiagnosticJournal(file).toRedactedJson()
        assertTrue(export.contains("CONNECTED"))
        assertFalse(export.contains("secret"))
        assertFalse(export.contains("password"))
    }

    @Test
    fun failedPersistenceDoesNotBreakTheVoiceSession() {
        val parentIsFile = temporaryFolder.newFile("not-directory")
        val journal = DiagnosticJournal(java.io.File(parentIsFile, "events.tsv"))
        journal.record(DiagnosticEventKind.CONNECTED)
        assertTrue(journal.failedWrites > 0)
        assertTrue(journal.toRedactedJson().contains("CONNECTED"))
    }

    @Test
    fun exitCategoriesRoundTripWithoutCarryingText() {
        val file = temporaryFolder.newFile("events.tsv")
        val exitKinds =
            listOf(
                DiagnosticEventKind.TASK_REMOVED,
                DiagnosticEventKind.EXIT_STARTED,
                DiagnosticEventKind.EXIT_REQUEST_SENT,
                DiagnosticEventKind.EXIT_SERVER_CONFIRMED,
                DiagnosticEventKind.EXIT_TIMED_OUT,
                DiagnosticEventKind.EXIT_FAILED,
                DiagnosticEventKind.EXIT_NO_ACTIVE_SESSION,
                DiagnosticEventKind.EXIT_UNSUPPORTED,
                DiagnosticEventKind.EXIT_CLEANUP_OVERRUN,
            )
        val journal = DiagnosticJournal(file)
        exitKinds.forEach { journal.record(it) }
        journal.persist()

        val export = DiagnosticJournal(file).toRedactedJson()

        exitKinds.forEach { assertTrue(export.contains("\"kind\":\"${it.name}\"")) }
        // Only fixed categories and numbers survive: no reason text, address or name.
        assertFalse(export.contains("Task removed"))
        assertFalse(export.contains("Disconnected by user"))
    }

    @Test
    fun clonesRecoveryCategoriesCarryOnlyFixedNumbers() {
        val file = temporaryFolder.newFile("events.tsv")
        val journal = DiagnosticJournal(file)
        journal.record(DiagnosticEventKind.CLONES_RECOVERY_STARTED)
        ClonesRecoveryResult.entries.forEach { journal.record(DiagnosticEventKind.CLONES_RECOVERY_ENDED, it.code) }
        journal.persist()

        val export = DiagnosticJournal(file).toRedactedJson()

        assertTrue(export.contains("\"kind\":\"CLONES_RECOVERY_STARTED\""))
        ClonesRecoveryResult.entries.forEach {
            assertTrue(export.contains("\"kind\":\"CLONES_RECOVERY_ENDED\",\"code\":${it.code}"))
        }
        // Only categories and numbers: never the refusal text or any identity.
        assertFalse(export.contains("clones already connected"))
        assertFalse(export.contains("example.invalid"))
    }
}

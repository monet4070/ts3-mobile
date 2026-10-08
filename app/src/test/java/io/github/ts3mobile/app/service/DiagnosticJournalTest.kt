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
}

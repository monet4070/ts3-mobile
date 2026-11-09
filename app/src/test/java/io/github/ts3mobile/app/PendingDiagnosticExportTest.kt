package io.github.ts3mobile.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PendingDiagnosticExportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun preparedReportCanBeSavedAfterTheProducerAndActivityHaveGoneAway() {
        val file = File(temporary.root, "pending.json")
        val report = "{\"schema\":\"ts3-mobile-report/v2\",\"audio\":{\"schema\":\"ts3-mobile-audio/v1\"}}"
        PendingDiagnosticExport(file).prepare(report)
        val recreated = PendingDiagnosticExport(file)
        assertArrayEquals(report.toByteArray(Charsets.UTF_8), recreated.read())
        recreated.clear()
        assertFalse(file.exists())
    }

    @Test fun oversizedPreparationCannotReplaceAnExistingValidReport() {
        val file = temporary.newFile()
        val pending = PendingDiagnosticExport(file)
        pending.prepare("{}")
        assertThrows(IOException::class.java) { pending.prepare("x".repeat(PendingDiagnosticExport.MAX_BYTES + 1)) }
        assertArrayEquals("{}".toByteArray(), pending.read())
    }

    @Test fun canceledPickerRemovesOnlyItsPreparedFileAndClearIsIdempotent() {
        val file = File(temporary.root, "pending.json")
        val independent = temporary.newFile("independent")
        val pending = PendingDiagnosticExport(file)
        pending.prepare("{}")
        pending.clear()
        pending.clear()
        assertFalse(file.exists())
        org.junit.Assert.assertTrue(independent.exists())
    }

    @Test fun failedPreparationOrMissingAndOversizedCacheAreExplicitFailures() {
        val parent = temporary.newFile("not-a-directory")
        assertThrows(IOException::class.java) { PendingDiagnosticExport(File(parent, "pending.json")).prepare("{}") }
        val file = File(temporary.root, "missing.json")
        val pending = PendingDiagnosticExport(file)
        assertThrows(IOException::class.java) { pending.read() }
        file.writeBytes(ByteArray(PendingDiagnosticExport.MAX_BYTES + 1))
        assertThrows(IOException::class.java) { pending.read() }
    }
}

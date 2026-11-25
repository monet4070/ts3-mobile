package io.github.ts3mobile.app.documents

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

class DiagnosticExportOperationsTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun writingAndCleanupHoldTheSlotAndOldCallbackCannotConsumeANewReport() =
        runBlocking {
            val operations = DiagnosticExportOperations(this, directory.root)
            val first = checkNotNull(operations.reserve())
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CompletableDeferred<Boolean?>()
            operations.prepare(first, "{\"report\":1}")
            operations.picking(first)
            assertNull(operations.reserve())
            val write: (ByteArray) -> Unit = { bytes ->
                assertEquals("{\"report\":1}", bytes.decodeToString())
                started.countDown()
                check(release.await(5, SECONDS))
            }
            assertTrue(operations.resolve(first, write) { finished.complete(it) })
            try {
                assertTrue(withContext(Dispatchers.IO) { started.await(5, SECONDS) })
                assertNull(operations.reserve())
            } finally {
                release.countDown()
            }
            assertEquals(true, withTimeout(5_000) { finished.await() })
            assertFalse(directory.root.resolve("$first.json").exists())

            val second = checkNotNull(operations.reserve())
            operations.prepare(second, "{\"report\":2}")
            operations.picking(second)
            assertFalse(operations.resolve(first, null) { error("Stale picker result") })
            assertEquals(second, operations.pickerId)
            val next = CompletableDeferred<Boolean?>()
            val nextWrite: (ByteArray) -> Unit = { bytes -> assertEquals("{\"report\":2}", bytes.decodeToString()) }
            assertTrue(operations.resolve(second, nextWrite) { next.complete(it) })
            assertEquals(true, withTimeout(5_000) { next.await() })
        }

    @Test fun recreatedPickerResolvesOnlyItsOwnFileAndReleasesAfterProviderFailure() =
        runBlocking {
            val original = DiagnosticExportOperations(this, directory.root)
            val id = checkNotNull(original.reserve())
            original.prepare(id, "{\"fixture\":true}")
            original.picking(id)
            val other = directory.newFile("unrelated.json")

            val restored = DiagnosticExportOperations(this, directory.root)
            assertFalse(restored.restorePicking("not an operation ID"))
            assertTrue(restored.restorePicking(id))
            val finished = CompletableDeferred<Boolean?>()
            val write: (ByteArray) -> Unit = { bytes ->
                assertEquals("{\"fixture\":true}", bytes.decodeToString())
                throw IOException("Fixture destination failed")
            }
            assertTrue(restored.resolve(id, write) { finished.complete(it) })
            assertEquals(false, withTimeout(5_000) { finished.await() })
            assertFalse(directory.root.resolve("$id.json").exists())
            assertTrue(other.exists())
            assertTrue(restored.reserve() != null)
        }
}

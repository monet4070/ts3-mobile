package io.github.ts3mobile.app.documents

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedFileSavesTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun detachedDialogCannotDiscardAValidPickerResultOrConsumeItTwice() =
        runTest {
            val path = directory.newFile().toPath()
            Files.write(path, byteArrayOf(1, 2, 3))
            val output = ByteArrayOutputStream()
            val saves =
                VerifiedFileSaves<String>(
                    backgroundScope,
                    { source, _ -> copyVerifiedDocument(source) { output } },
                    ::removeVerifiedStaging,
                )
            val owner = Any()
            lateinit var request: DocumentPickerRequest
            assertTrue(
                saves.start(owner, VerifiedDocument("fixture.bin", path) { true }, { error("Old dialog is detached") }) {
                    request = it
                    true
                },
            )

            saves.detach(owner)
            assertTrue(Files.exists(path))
            assertTrue(saves.accept(request.id, "first target"))
            assertFalse(saves.accept(request.id, "duplicate result"))
            runCurrent()

            assertEquals(DocumentSaveResult.SAVED, saves.notice)
            assertFalse(Files.exists(path))
            assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        }

    @Test fun changedChannelNeverOpensTheSelectedTargetAndOldResultCannotTouchNewSource() =
        runTest {
            val path = directory.newFile().toPath()
            var valid = true
            var completions = 0
            var result: DocumentSaveResult? = null
            val saves =
                VerifiedFileSaves<String>(
                    backgroundScope,
                    { _, _ -> error("Stale destination must not open") },
                    ::removeVerifiedStaging,
                )
            val owner = Any()
            lateinit var old: DocumentPickerRequest
            assertTrue(
                saves.start(owner, VerifiedDocument("old.bin", path) { valid }, { result = it }) {
                    old = it
                    true
                },
            )
            valid = false

            assertTrue(saves.accept(old.id, "empty target"))
            assertEquals(DocumentSaveResult.SESSION_CHANGED_WITH_TARGET, result)
            assertFalse(Files.exists(path))

            val replacement = directory.newFile().toPath()
            lateinit var new: DocumentPickerRequest
            assertTrue(
                saves.start(Any(), VerifiedDocument("new.bin", replacement) { true }, { completions++ }) {
                    new = it
                    true
                },
            )
            assertFalse(saves.accept(old.id, "stale result"))
            assertTrue(Files.exists(replacement))
            assertTrue(saves.accept(new.id, null))
            assertEquals(1, completions)
            assertFalse(Files.exists(replacement))
        }

    @Test fun failedPrefixKeepsTheSourceAndRequiresASeparateDestination() =
        runTest {
            val source = directory.newFile().toPath()
            val bytes = byteArrayOf(1, 2, 3, 4)
            Files.write(source, bytes)
            val partial = ByteArrayOutputStream()
            val complete = ByteArrayOutputStream()
            val destinations = mutableListOf<String>()
            val saves =
                VerifiedFileSaves<String>(
                    backgroundScope,
                    copy = { document, destination ->
                        destinations += destination
                        copyVerifiedDocument(document) { if (destination == "partial") prefixThenFail(partial) else complete }
                    },
                    discard = ::removeVerifiedStaging,
                    nowMs = { testScheduler.currentTime },
                )
            val owner = Any()
            lateinit var request: DocumentPickerRequest
            val launch: (DocumentPickerRequest) -> Boolean = {
                request = it
                true
            }
            var result: DocumentSaveResult? = null
            assertTrue(saves.start(owner, VerifiedDocument("fixture.bin", source) { true }, { result = it }, launch))
            saves.accept(request.id, "partial")
            runCurrent()

            assertTrue(saves.canRetry(owner))
            assertEquals(DocumentSaveResult.TARGET_MAY_BE_INCOMPLETE, saves.issue(owner))
            assertNull(result)
            assertArrayEquals(bytes, Files.readAllBytes(source))
            saves.detach(owner)
            assertTrue(saves.detachedRetry === owner)
            saves.retry(owner, launch)
            saves.accept(request.id, "partial")
            assertEquals(listOf("partial"), destinations)
            saves.retry(owner, launch)
            saves.accept(request.id, "separate")
            runCurrent()

            assertArrayEquals(byteArrayOf(1, 2), partial.toByteArray())
            assertArrayEquals(bytes, complete.toByteArray())
            assertFalse(Files.exists(source))
            assertEquals(DocumentSaveResult.SAVED, saves.notice)
        }

    @Test fun retryStageExpiresAndIsCleanedWithoutOpeningANewTarget() =
        runTest {
            val source = directory.newFile().toPath()
            var outcome: DocumentSaveResult? = null
            val saves =
                VerifiedFileSaves<String>(
                    backgroundScope,
                    { _, _ -> error("No destination was chosen") },
                    ::removeVerifiedStaging,
                    retryLifetimeMs = 100,
                    nowMs = { testScheduler.currentTime },
                )
            assertTrue(saves.start(Any(), VerifiedDocument("fixture.bin", source) { true }, { outcome = it }, { false }))
            advanceTimeBy(101)
            runCurrent()

            assertEquals(DocumentSaveResult.SOURCE_UNAVAILABLE, outcome)
            assertFalse(Files.exists(source))
            assertTrue(saves.available)
        }

    private fun prefixThenFail(prefix: ByteArrayOutputStream) =
        object : OutputStream() {
            override fun write(value: Int) {
                if (prefix.size() == 2) throw IOException("Fixture provider failed after writing a prefix")
                prefix.write(value)
            }
        }
}

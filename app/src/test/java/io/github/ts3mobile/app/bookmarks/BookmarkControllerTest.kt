package io.github.ts3mobile.app.bookmarks

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkControllerTest {
    private val entry = ServerBookmark("fixture", name = "Fixture", host = "voice.example.com")

    @Test fun delayedInitialLoadCannotOverwriteQueuedEdits() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val store = FakeStore().apply { readGate = gate }
            val controller = BookmarkController(backgroundScope, store)
            runCurrent()
            controller.save(entry)
            runCurrent()
            assertTrue(controller.state.value.loading)
            gate.complete(Unit)
            runCurrent()
            assertEquals("Fixture", controller.state.value.entries.single().name)
            assertEquals(1, controller.state.value.entries.single().revision)
        }

    @Test fun failedSaveAndStaleDeleteKeepPreviousSnapshotAndExposeFailure() =
        runTest {
            val store = FakeStore()
            val controller = BookmarkController(backgroundScope, store)
            runCurrent()
            controller.save(entry)
            runCurrent()
            val saved = controller.state.value.entries.single()
            store.fail = true
            controller.save(saved.copy(name = "Lost"))
            runCurrent()
            assertTrue(controller.state.value.failed)
            assertEquals(saved, controller.state.value.entries.single())
            store.fail = false
            controller.save(saved.copy(name = "New"))
            runCurrent()
            controller.remove(saved)
            runCurrent()
            assertTrue(controller.state.value.failed)
            assertEquals("New", controller.state.value.entries.single().name)
            controller.reload()
            runCurrent()
            assertFalse(controller.state.value.failed)
        }

    @Test fun queuedSaveChecksConnectionAfterWaitingForTheInitialLoad() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val store = FakeStore().apply { readGate = gate }
            val controller = BookmarkController(backgroundScope, store)
            var valid = true
            runCurrent()
            controller.saveValidated(entry) { valid }
            runCurrent()
            valid = false
            gate.complete(Unit)
            runCurrent()
            assertTrue(controller.state.value.contextChanged)
            assertTrue(controller.state.value.failed)
            assertTrue(store.entries.isEmpty())
            valid = true
            controller.saveValidated(entry) { valid }
            runCurrent()
            assertEquals("Fixture", store.entries.single().name)
            assertFalse(controller.state.value.contextChanged)
        }

    private class FakeStore : BookmarkStore {
        var entries = emptyList<ServerBookmark>()
        var readGate: CompletableDeferred<Unit>? = null
        var fail = false

        override suspend fun read(): List<ServerBookmark> {
            readGate?.await()
            if (fail) throw IOException()
            return entries
        }

        override suspend fun save(entry: ServerBookmark): List<ServerBookmark> {
            if (fail) throw IOException()
            entries = BookmarkCodec.save(entries, entry)
            return entries
        }

        override suspend fun remove(entry: ServerBookmark): List<ServerBookmark> {
            entries = BookmarkCodec.remove(entries, entry)
            return entries
        }
    }
}

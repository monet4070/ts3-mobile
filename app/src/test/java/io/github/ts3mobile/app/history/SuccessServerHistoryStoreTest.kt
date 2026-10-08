package io.github.ts3mobile.app.history

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Drives the store against a real file-backed DataStore in a temp folder, so
 * persistence and reopen behavior are verified without the Android framework.
 */
class SuccessServerHistoryStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val historyFile: File
        get() = File(tempFolder.root, "server_history.preferences_pb")

    private fun storeIn(scope: CoroutineScope): SuccessServerHistoryStore =
        SuccessServerHistoryStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { historyFile }),
        )

    @Test
    fun aBackgroundSuccessIsSavedAndReadableAfterReopen() {
        val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        runBlocking {
            val store = storeIn(writeScope)
            store.recordSuccess("voice.example.com", 9987)
            store.recordSuccess("2001:db8::1", 10011)

            assertEquals(
                listOf(ServerHistoryEntry("2001:db8::1", 10011), ServerHistoryEntry("voice.example.com", 9987)),
                store.entries.first(),
            )
        }
        runBlocking { writeScope.coroutineContext[Job]!!.cancelAndJoin() }

        val readScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        runBlocking {
            assertEquals(
                listOf(ServerHistoryEntry("2001:db8::1", 10011), ServerHistoryEntry("voice.example.com", 9987)),
                storeIn(readScope).entries.first(),
            )
        }
        runBlocking { readScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test
    fun aRemovedEndpointStaysRemovedAfterReopen() {
        val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        runBlocking {
            val store = storeIn(writeScope)
            store.recordSuccess("voice.example.com", 9987)
            store.recordSuccess("other.example.com", 9987)
            store.remove(ServerHistoryEntry("voice.example.com", 9987))

            assertEquals(listOf(ServerHistoryEntry("other.example.com", 9987)), store.entries.first())
        }
        runBlocking { writeScope.coroutineContext[Job]!!.cancelAndJoin() }

        val readScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        runBlocking {
            assertEquals(
                listOf(ServerHistoryEntry("other.example.com", 9987)),
                storeIn(readScope).entries.first(),
            )
        }
        runBlocking { readScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test
    fun anInvalidEndpointIsNotPersisted() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        runBlocking {
            val store = storeIn(scope)
            store.recordSuccess("", 9987)
            store.recordSuccess("voice.example.com", 0)

            assertEquals(emptyList<ServerHistoryEntry>(), store.entries.first())
        }
        runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}

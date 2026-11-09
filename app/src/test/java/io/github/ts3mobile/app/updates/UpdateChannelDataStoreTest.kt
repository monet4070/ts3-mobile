package io.github.ts3mobile.app.updates

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateChannelDataStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun defaultsToStableAndTheExplicitChoiceSurvivesReconstruction() =
        runBlocking {
            val file = File(folder.root, "updates.preferences_pb")
            var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                var store = UpdateChannelDataStore(PreferenceDataStoreFactory.create(scope = scope) { file })
                assertEquals(UpdateChannel.STABLE, store.read())
                store.write(UpdateChannel.INCLUDE_PRERELEASES)
                scope.coroutineContext.job.cancelAndJoin()
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                store = UpdateChannelDataStore(PreferenceDataStoreFactory.create(scope = scope) { file })
                assertEquals(UpdateChannel.INCLUDE_PRERELEASES, store.read())
                store.write(UpdateChannel.STABLE)
                assertEquals(UpdateChannel.STABLE, store.read())
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }
}

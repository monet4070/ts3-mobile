package io.github.ts3mobile.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ts3mobile.audio.opus.ReceiveDelayProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaybackPreferencesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun newInstallDefaultsToStabilityAndExplicitBalancedSurvivesReconstruction() =
        runBlocking {
            val file = File(folder.root, "playback.preferences_pb")
            var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val initial = PlaybackPreferences(PreferenceDataStoreFactory.create(scope = scope) { file }, scope)
                assertEquals(ReceiveDelayProfile.STABILITY, initial.read())
                assertEquals(ReceiveDelayProfile.STABILITY, initial.awaitIdle().profile)
                initial.select(ReceiveDelayProfile.BALANCED)
                assertEquals(ReceiveDelayProfile.BALANCED, initial.awaitIdle().profile)
                assertEquals(ReceiveDelayProfile.BALANCED, initial.read())

                scope.coroutineContext.job.cancelAndJoin()
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val reopened = PlaybackPreferences(PreferenceDataStoreFactory.create(scope = scope) { file }, scope)
                assertEquals(ReceiveDelayProfile.BALANCED, reopened.awaitIdle().profile)
                reopened.select(ReceiveDelayProfile.STABILITY)
                assertEquals(ReceiveDelayProfile.STABILITY, reopened.awaitIdle().profile)
                assertEquals(ReceiveDelayProfile.STABILITY, reopened.read())
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    @Test fun unknownSelectionIsReportedWithoutOverwritingTheStoredValue() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "unknown.preferences_pb") }
                val key = stringPreferencesKey("receive_delay_profile")
                store.edit { it[key] = "NOT_A_PROFILE" }
                val preferences = PlaybackPreferences(store, scope)
                assertNull(withTimeout(5000) { preferences.state.first { it.failed } }.profile)
                preferences.select(ReceiveDelayProfile.STABILITY)
                assertEquals("NOT_A_PROFILE", store.data.first()[key])
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    private suspend fun PlaybackPreferences.awaitIdle(): PlaybackPreferenceState = withTimeout(5000) { state.first { !it.busy } }
}

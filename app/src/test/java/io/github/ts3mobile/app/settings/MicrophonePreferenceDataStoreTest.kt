package io.github.ts3mobile.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MicrophonePreferenceDataStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun allFourPreferencesSurviveStoreReconstruction() =
        runBlocking {
            val file = File(folder.root, "microphone.preferences_pb")
            val expected = MicrophoneSettings(165, false, false, -23)
            var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                var preferences = PreferenceDataStoreFactory.create(scope = scope) { file }
                var store = MicrophonePreferenceDataStore(preferences)
                assertEquals(MicrophoneSettings(), store.read())
                store.write(expected)
                assertEquals(5, preferences.data.first().asMap().size)
                scope.coroutineContext.job.cancelAndJoin()
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                preferences = PreferenceDataStoreFactory.create(scope = scope) { file }
                store = MicrophonePreferenceDataStore(preferences)
                assertEquals(expected, store.read())
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    @Test fun missingKnownFieldsUseDefaultsButUnknownVersionCannotBeAutomaticallyOverwritten() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val preferences = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "version.preferences_pb") }
                val store = MicrophonePreferenceDataStore(preferences)
                val version = intPreferencesKey("version")
                preferences.edit { it[version] = 1 }
                assertEquals(MicrophoneSettings(), store.read())
                preferences.edit { it[version] = 42 }
                val before = preferences.data.first()
                assertTrue(runCatching { store.read() }.isFailure)
                assertTrue(runCatching { store.write(MicrophoneSettings()) }.isFailure)
                assertEquals(before, preferences.data.first())
                store.write(MicrophoneSettings(), reset = true)
                assertEquals(MicrophoneSettings(), store.read())
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    @Test fun physicallyCorruptStoreIsNotDeletedOrReplacedByWriteOrReset() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val file = File(folder.root, "corrupt.preferences_pb")
            val original = byteArrayOf(0x0f, 0x7f, 0x01)
            file.writeBytes(original)
            try {
                val store = MicrophonePreferenceDataStore(PreferenceDataStoreFactory.create(scope = scope) { file })
                assertTrue(runCatching { store.read() }.isFailure)
                assertTrue(runCatching { store.write(MicrophoneSettings()) }.isFailure)
                assertTrue(runCatching { store.write(MicrophoneSettings(), reset = true) }.isFailure)
                assertArrayEquals(original, file.readBytes())
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    @Test fun outOfRangeValuesStayIntactUntilExplicitReset() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val preferences = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "invalid.preferences_pb") }
                preferences.edit {
                    it[intPreferencesKey("version")] = 1
                    it[intPreferencesKey("volume_percent")] = 999
                }
                val store = MicrophonePreferenceDataStore(preferences)
                assertTrue(runCatching { store.read() }.isFailure)
                assertTrue(runCatching { store.write(MicrophoneSettings()) }.isFailure)
                assertEquals(999, preferences.data.first()[intPreferencesKey("volume_percent")])
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }
}

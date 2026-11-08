package io.github.ts3mobile.app.appearance

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

class ThemePreferenceDataStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun emptyStoreUsesLightAndEveryModeSurvivesReconstruction() =
        runBlocking {
            val file = File(folder.root, "appearance.preferences_pb")
            for (mode in ThemeMode.entries) {
                var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    var store = ThemePreferenceDataStore(PreferenceDataStoreFactory.create(scope = scope) { file })
                    if (!file.exists()) assertEquals(ThemeMode.LIGHT, store.read())
                    store.write(mode)
                    scope.coroutineContext.job.cancelAndJoin()
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    store = ThemePreferenceDataStore(PreferenceDataStoreFactory.create(scope = scope) { file })
                    assertEquals(mode, store.read())
                } finally {
                    scope.coroutineContext.job.cancelAndJoin()
                }
            }
        }

    @Test fun unsupportedVersionRemainsIntactUntilAnExplicitReset() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val data = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "unsupported.preferences_pb") }
                data.edit {
                    it[intPreferencesKey("version")] = 42
                    it[stringPreferencesKey("theme")] = "future"
                }
                val before = data.data.first()
                val store = ThemePreferenceDataStore(data)
                assertTrue(runCatching { store.read() }.isFailure)
                assertTrue(runCatching { store.write(ThemeMode.DARK) }.isFailure)
                assertEquals(before, data.data.first())
                store.write(ThemeMode.LIGHT, reset = true)
                assertEquals(ThemeMode.LIGHT, store.read())
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    @Test fun missingVersionInvalidModeAndWrongTypeAreNotSilentlyOverwritten() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val data = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "invalid.preferences_pb") }
                val store = ThemePreferenceDataStore(data)
                for (case in 0..2) {
                    data.edit {
                        it.clear()
                        if (case != 0) it[intPreferencesKey("version")] = 1
                        if (case == 2) it[intPreferencesKey("theme")] = 10 else it[stringPreferencesKey("theme")] = "invalid"
                    }
                    val before = data.data.first()
                    assertTrue(runCatching { store.read() }.isFailure)
                    assertTrue(runCatching { store.write(ThemeMode.SYSTEM) }.isFailure)
                    assertEquals(before, data.data.first())
                }
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    @Test fun physicalCorruptionCannotBeDeletedByReadWriteOrReset() =
        runBlocking {
            val file = File(folder.root, "corrupt.preferences_pb")
            val original = byteArrayOf(0x0f, 0x7f, 0x01)
            file.writeBytes(original)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store = ThemePreferenceDataStore(PreferenceDataStoreFactory.create(scope = scope) { file })
                assertTrue(runCatching { store.read() }.isFailure)
                assertTrue(runCatching { store.write(ThemeMode.DARK) }.isFailure)
                assertTrue(runCatching { store.write(ThemeMode.LIGHT, reset = true) }.isFailure)
                assertArrayEquals(original, file.readBytes())
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }
}

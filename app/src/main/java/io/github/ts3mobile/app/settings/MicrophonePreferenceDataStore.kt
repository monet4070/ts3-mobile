package io.github.ts3mobile.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.microphonePreferenceDataStore by preferencesDataStore(name = "ts3_microphone_preferences")

internal fun microphonePreferenceStore(context: Context): MicrophonePreferenceStore =
    MicrophonePreferenceDataStore(context.applicationContext.microphonePreferenceDataStore)

/** No corruption handler: unreadable or unknown preferences must remain intact until explicit reset. */
internal class MicrophonePreferenceDataStore(private val store: DataStore<Preferences>) : MicrophonePreferenceStore {
    override suspend fun read(): MicrophoneSettings = withContext(Dispatchers.IO) { decode(store.data.first()) }

    override suspend fun write(
        settings: MicrophoneSettings,
        reset: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            store.edit { preferences ->
                if (reset) preferences.clear() else decode(preferences)
                preferences[version] = 1
                preferences[volume] = settings.volumePercent
                preferences[noise] = settings.noiseSuppression
                preferences[echo] = settings.echoCancellation
                preferences[threshold] = settings.gateThresholdDb
            }
        }
    }

    private fun decode(preferences: Preferences): MicrophoneSettings {
        val schema = preferences[version]
        if (schema == null) {
            require(preferences.asMap().isEmpty()) { "Missing microphone preference version" }
            return MicrophoneSettings()
        }
        require(schema == 1) { "Unsupported microphone preference version" }
        val defaults = MicrophoneSettings()
        return MicrophoneSettings(
            volumePercent = preferences[volume] ?: defaults.volumePercent,
            noiseSuppression = preferences[noise] ?: defaults.noiseSuppression,
            echoCancellation = preferences[echo] ?: defaults.echoCancellation,
            gateThresholdDb = preferences[threshold] ?: defaults.gateThresholdDb,
        )
    }

    private companion object {
        val version = intPreferencesKey("version")
        val volume = intPreferencesKey("volume_percent")
        val noise = booleanPreferencesKey("noise_suppression")
        val echo = booleanPreferencesKey("echo_cancellation")
        val threshold = intPreferencesKey("gate_threshold_db")
    }
}

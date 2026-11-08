package io.github.ts3mobile.app.appearance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.appearanceDataStore by preferencesDataStore(name = "ts3_appearance")

internal fun themePreferenceStore(context: Context): ThemePreferenceStore =
    ThemePreferenceDataStore(context.applicationContext.appearanceDataStore)

/** Preserve unsupported/corrupt preferences; only explicit reset replaces a logical schema. */
internal class ThemePreferenceDataStore(private val store: DataStore<Preferences>) : ThemePreferenceStore {
    override suspend fun read(): ThemeMode = withContext(Dispatchers.IO) { decode(store.data.first()) }

    override suspend fun write(
        mode: ThemeMode,
        reset: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            store.edit {
                if (reset) it.clear() else decode(it)
                it[version] = 1
                it[theme] = mode.stored
            }
        }
    }

    private fun decode(preferences: Preferences): ThemeMode {
        if (preferences[version] == null) {
            require(preferences.asMap().isEmpty()) { "Missing appearance version" }
            return ThemeMode.LIGHT
        }
        require(preferences[version] == 1) { "Unsupported appearance version" }
        val value = preferences[theme] ?: return ThemeMode.LIGHT
        return requireNotNull(ThemeMode.entries.firstOrNull { it.stored == value }) { "Unsupported appearance mode" }
    }

    private companion object {
        val version = intPreferencesKey("version")
        val theme = stringPreferencesKey("theme")
    }
}

package io.github.ts3mobile.app.updates

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.updateDataStore by preferencesDataStore(name = "ts3_updates")

internal fun updateChannelStore(context: Context): UpdateChannelStore = UpdateChannelDataStore(context.applicationContext.updateDataStore)

internal class UpdateChannelDataStore(private val store: DataStore<Preferences>) : UpdateChannelStore {
    override suspend fun read(): UpdateChannel =
        withContext(Dispatchers.IO) {
            if (store.data.first()[includePrereleases] == true) UpdateChannel.INCLUDE_PRERELEASES else UpdateChannel.STABLE
        }

    override suspend fun write(channel: UpdateChannel) {
        withContext(Dispatchers.IO) {
            store.edit { it[includePrereleases] = channel == UpdateChannel.INCLUDE_PRERELEASES }
        }
    }

    private companion object {
        val includePrereleases = booleanPreferencesKey("include_prereleases")
    }
}

package io.github.ts3mobile.app.history

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore

private val Context.serverHistoryDataStore by preferencesDataStore(
    name = "ts3_server_history",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Returns the process-wide history store. The [preferencesDataStore] delegate
 * caches one DataStore per process, so the service and the view model share the
 * same underlying file and observe each other's writes.
 */
fun successServerHistoryStore(context: Context): SuccessServerHistoryStore =
    SuccessServerHistoryStore(context.applicationContext.serverHistoryDataStore)

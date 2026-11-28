package io.github.ts3mobile.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ts3mobile.audio.opus.ReceiveDelayProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.playbackDataStore by preferencesDataStore(name = "ts3_playback")

internal fun playbackPreferences(
    context: Context,
    scope: CoroutineScope,
): PlaybackPreferences = PlaybackPreferences(context.applicationContext.playbackDataStore, scope)

internal data class PlaybackPreferenceState(
    val profile: ReceiveDelayProfile? = null,
    val busy: Boolean = false,
    val failed: Boolean = false,
)

internal class PlaybackPreferences(private val store: DataStore<Preferences>, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(PlaybackPreferenceState())
    val state = mutableState.asStateFlow()

    init {
        reload()
    }

    suspend fun read(): ReceiveDelayProfile {
        val stored = store.data.first()[profileKey] ?: return DEFAULT_PROFILE
        return ReceiveDelayProfile.entries.firstOrNull { it.name == stored }
            ?: throw IOException("Unsupported receive delay preference")
    }

    fun reload() = update { read() }

    fun select(profile: ReceiveDelayProfile) {
        val previous = state.value.profile ?: return
        if (profile == previous) return
        update {
            store.edit { it[profileKey] = profile.name }
            profile
        }
    }

    private fun update(operation: suspend () -> ReceiveDelayProfile) {
        val previous = mutableState.value
        if (previous.busy) return
        mutableState.value = previous.copy(busy = true, failed = false)
        scope.launch {
            mutableState.value =
                try {
                    PlaybackPreferenceState(profile = operation())
                } catch (_: IOException) {
                    previous.copy(busy = false, failed = true)
                }
        }
    }

    companion object {
        val DEFAULT_PROFILE = ReceiveDelayProfile.STABILITY
        private val profileKey = stringPreferencesKey("receive_delay_profile")
    }
}

package io.github.ts3mobile.app

import android.app.Application
import io.github.ts3mobile.app.appearance.ThemePreferenceController
import io.github.ts3mobile.app.appearance.themePreferenceStore
import io.github.ts3mobile.app.chat.ChatHistoryController
import io.github.ts3mobile.app.chat.ChatHistoryVault
import io.github.ts3mobile.app.identity.IdentityRepository
import io.github.ts3mobile.app.identity.IdentityVault
import io.github.ts3mobile.app.service.AudioDiagnosticRepository
import io.github.ts3mobile.app.service.DiagnosticJournal
import io.github.ts3mobile.app.service.SessionDiagnosticWriter
import io.github.ts3mobile.app.settings.playbackPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File

/** Explicit process container shared by all Android components, including replacement instances. */
class Ts3MobileApplication : Application() {
    internal val identities by lazy { IdentityRepository(IdentityVault(this).storage) }
    internal val chatHistory by lazy { ChatHistoryController(CoroutineScope(SupervisorJob() + Dispatchers.IO), ChatHistoryVault(this)) }

    /** The archive read runs here, never on the caller's thread; see OfflineAudioDiagnosticsTest. */
    internal val audioDiagnosticDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val audioDiagnosticScope = CoroutineScope(SupervisorJob() + audioDiagnosticDispatcher)

    internal val sessionDiagnosticWriter by lazy {
        SessionDiagnosticWriter(
            DiagnosticJournal(File(noBackupFilesDir, "session-events.tsv"), loadImmediately = false),
            audioDiagnosticScope,
        )
    }

    /**
     * Reading up to 1 MiB of history is bounded but never belongs on the service's main thread.
     * Lazy so the first access happens after the application context is attached; the read
     * itself still runs on the IO dispatcher.
     */
    internal val audioDiagnostics: Deferred<AudioDiagnosticRepository> by lazy {
        audioDiagnosticScope.async {
            AudioDiagnosticRepository(File(noBackupFilesDir, "audio-diagnostics.tsv"), audioDiagnosticScope)
        }
    }

    private val appearanceScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    internal val appearance by lazy { ThemePreferenceController(appearanceScope, themePreferenceStore(this)) }
    internal val playbackPreferences by lazy { playbackPreferences(this, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
}

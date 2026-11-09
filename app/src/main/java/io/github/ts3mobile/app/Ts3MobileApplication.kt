package io.github.ts3mobile.app

import android.app.Application
import io.github.ts3mobile.app.appearance.ThemePreferenceController
import io.github.ts3mobile.app.appearance.themePreferenceStore
import io.github.ts3mobile.app.identity.IdentityRepository
import io.github.ts3mobile.app.identity.IdentityVault
import io.github.ts3mobile.app.service.AudioDiagnosticRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Explicit process container shared by all Android components, including replacement instances. */
class Ts3MobileApplication : Application() {
    internal val identities by lazy { IdentityRepository(IdentityVault(this).storage) }
    private val audioDiagnosticScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    internal val audioDiagnostics by lazy {
        AudioDiagnosticRepository(File(noBackupFilesDir, "audio-diagnostics.tsv"), audioDiagnosticScope)
    }
    private val appearanceScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    internal val appearance by lazy { ThemePreferenceController(appearanceScope, themePreferenceStore(this)) }
}

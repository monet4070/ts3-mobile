package io.github.ts3mobile.app.updates

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.ui.theme.ApplicationTheme

class UpdatesActivity : ComponentActivity() {
    private val model: UpdatesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val installed = installedAppVersion(this)
        setContent {
            ApplicationTheme {
                val state by model.updates.state.collectAsStateWithLifecycle()
                UpdatesScreen(
                    state,
                    installed,
                    model.updates::select,
                    model.updates::check,
                    model.updates::retryPreference,
                    ::openReleasePage,
                    ::openApk,
                    ::finish,
                )
            }
        }
    }

    private fun openReleasePage(url: String) {
        if (isOfficialReleasePage(url)) openBrowser(url)
    }

    private fun openApk(release: UpdateRelease) {
        // Only the current completed result can authorize a download after a channel switch.
        if ((model.updates.state.value.check as? UpdateCheck.Complete)?.release != release) return
        val installed = installedAppVersion(this)
        if (!release.isNewerThan(installed) || release.compatibilityWith(installed) == UpdateCompatibility.ANDROID_TOO_OLD) return
        if (!isTrustedUpdateRedirect(release.apkUrl)) return
        openBrowser(release.apkUrl)
    }

    private fun openBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.updates_browser_failed, Toast.LENGTH_SHORT).show()
        }
    }
}

internal class UpdatesViewModel(application: Application) : AndroidViewModel(application) {
    val updates = UpdateController(viewModelScope, GitHubUpdateSource(), updateChannelStore(application))

    override fun onCleared() {
        updates.close()
        super.onCleared()
    }
}

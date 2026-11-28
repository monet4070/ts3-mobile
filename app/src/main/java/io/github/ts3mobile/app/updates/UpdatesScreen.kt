package io.github.ts3mobile.app.updates

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.ui.FeatureAction
import io.github.ts3mobile.app.ui.FeatureNotice
import io.github.ts3mobile.app.ui.Panel
import io.github.ts3mobile.app.ui.SettingsScaffold
import io.github.ts3mobile.app.ui.SettingsSection
import io.github.ts3mobile.app.ui.SettingsToggleRow
import io.github.ts3mobile.app.ui.UiIcon

@Composable
internal fun UpdatesScreen(
    state: UpdateState,
    installed: InstalledAppVersion,
    onSelect: (UpdateChannel) -> Unit,
    onCheck: () -> Unit,
    onRetryPreference: () -> Unit,
    onReleasePage: (String) -> Unit,
    onDownload: (UpdateRelease) -> Unit,
    onClose: () -> Unit,
) {
    val release = (state.check as? UpdateCheck.Complete)?.release
    var readingNotes by rememberSaveable(release) { mutableStateOf(false) }
    val summaryScroll = rememberScrollState()
    val navigateBack: () -> Unit = { if (readingNotes) readingNotes = false else onClose() }
    BackHandler(onBack = navigateBack)
    SettingsScaffold(R.string.updates_title, onBack = navigateBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                if (readingNotes && release != null) {
                    ReleaseNotesReader(release, { readingNotes = false }, Modifier.weight(1f))
                } else {
                    Column(
                        Modifier.weight(1f).verticalScroll(summaryScroll).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        UpdateChannelSection(state, onSelect, onCheck, onRetryPreference)
                        FeatureNotice(UiIcon.Info, stringResource(R.string.updates_current, installed.name, installed.code))
                        when (val check = state.check) {
                            UpdateCheck.Checking ->
                                Panel(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Text(stringResource(R.string.updates_checking), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            is UpdateCheck.Complete -> {
                                UpdateReleaseCard(check.release, installed, onReleasePage, onDownload)
                                if (check.release.notes.isNotBlank()) ReleaseNotesPreview(check.release) { readingNotes = true }
                            }
                            UpdateCheck.NoRelease -> FeatureNotice(UiIcon.Info, stringResource(R.string.updates_no_release))
                            is UpdateCheck.Failed ->
                                FeatureNotice(UiIcon.Info, stringResource(check.failure.message()), error = true)
                            UpdateCheck.Idle -> Unit
                        }
                        FeatureAction(
                            UiIcon.ExternalLink,
                            stringResource(R.string.updates_all_releases),
                            { onReleasePage(RELEASES_PAGE_URL) },
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateChannelSection(
    state: UpdateState,
    onSelect: (UpdateChannel) -> Unit,
    onCheck: () -> Unit,
    onRetryPreference: () -> Unit,
) {
    val busy = state.preferenceStatus in setOf(UpdatePreferenceStatus.LOADING, UpdatePreferenceStatus.SAVING)
    val label = stringResource(R.string.updates_include_prereleases)
    SettingsSection(stringResource(R.string.updates_channel_title), UiIcon.Sliders) {
        SettingsToggleRow(
            label,
            state.channel == UpdateChannel.INCLUDE_PRERELEASES,
            { onSelect(if (it) UpdateChannel.INCLUDE_PRERELEASES else UpdateChannel.STABLE) },
            enabled = !busy && state.preferenceStatus != UpdatePreferenceStatus.READ_FAILED,
        )
        if (state.channel == UpdateChannel.INCLUDE_PRERELEASES) {
            FeatureNotice(
                UiIcon.Info,
                stringResource(R.string.updates_prerelease_warning),
            )
        }
        if (busy) FeatureNotice(UiIcon.Refresh, stringResource(R.string.updates_loading_choice))
        if (state.preferenceStatus in setOf(UpdatePreferenceStatus.READ_FAILED, UpdatePreferenceStatus.WRITE_FAILED)) {
            FeatureNotice(UiIcon.Info, stringResource(R.string.updates_preference_failed), error = true)
            OutlinedButton(onClick = onRetryPreference) { Text(stringResource(R.string.action_retry_control)) }
        }
        OutlinedButton(
            onClick = onCheck,
            enabled = state.preferenceStatus == UpdatePreferenceStatus.SAVED && state.check != UpdateCheck.Checking,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.updates_check_again)) }
    }
}

private fun UpdateFailure.message(): Int =
    when (this) {
        UpdateFailure.NETWORK -> R.string.updates_network_failed
        UpdateFailure.TIMEOUT -> R.string.updates_timeout
        UpdateFailure.RATE_LIMITED -> R.string.updates_rate_limited
        UpdateFailure.SERVER -> R.string.updates_server_failed
        UpdateFailure.INVALID_RELEASE -> R.string.updates_invalid_release
    }

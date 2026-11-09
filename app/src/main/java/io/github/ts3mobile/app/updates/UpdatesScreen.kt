package io.github.ts3mobile.app.updates

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.ui.SettingsSection
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
    BackHandler(enabled = readingNotes) { readingNotes = false }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.updates_title),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            stringResource(R.string.updates_current, installed.name, installed.code),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (readingNotes && release != null) {
                    ReleaseNotesReader(release, { readingNotes = false }, Modifier.weight(1f))
                } else {
                    Column(
                        Modifier.weight(1f).verticalScroll(summaryScroll).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        UpdateChannelSection(state, onSelect, onCheck, onRetryPreference)
                        when (val check = state.check) {
                            UpdateCheck.Checking ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CircularProgressIndicator()
                                    Text(stringResource(R.string.updates_checking))
                                }
                            is UpdateCheck.Complete -> {
                                UpdateReleaseCard(check.release, installed, onReleasePage, onDownload)
                                if (check.release.notes.isNotBlank()) ReleaseNotesPreview(check.release) { readingNotes = true }
                            }
                            UpdateCheck.NoRelease -> Text(stringResource(R.string.updates_no_release))
                            is UpdateCheck.Failed ->
                                Surface(
                                    shape = MaterialTheme.shapes.large,
                                    color = MaterialTheme.colorScheme.errorContainer,
                                ) {
                                    Text(
                                        stringResource(check.failure.message()),
                                        Modifier.padding(16.dp),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            UpdateCheck.Idle -> Unit
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.updates_manual_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { onReleasePage(RELEASES_PAGE_URL) }) {
                                Text(stringResource(R.string.updates_all_releases))
                            }
                        }
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.updates_prerelease_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                modifier = Modifier.semantics { contentDescription = label },
                checked = state.channel == UpdateChannel.INCLUDE_PRERELEASES,
                onCheckedChange = { onSelect(if (it) UpdateChannel.INCLUDE_PRERELEASES else UpdateChannel.STABLE) },
                enabled = !busy && state.preferenceStatus != UpdatePreferenceStatus.READ_FAILED,
            )
        }
        if (busy) Text(stringResource(R.string.updates_loading_choice), style = MaterialTheme.typography.bodySmall)
        if (state.preferenceStatus in setOf(UpdatePreferenceStatus.READ_FAILED, UpdatePreferenceStatus.WRITE_FAILED)) {
            Text(stringResource(R.string.updates_preference_failed), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetryPreference) { Text(stringResource(R.string.action_retry_control)) }
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

package io.github.ts3mobile.app.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.ui.SettingsSection
import io.github.ts3mobile.app.ui.UiIcon

@Composable
internal fun ReleaseNotesPreview(
    release: UpdateRelease,
    onExpand: () -> Unit,
) {
    SettingsSection(stringResource(R.string.updates_release_notes), UiIcon.Info) {
        ReleaseNotesMarkdown(release.notes, Modifier.fillMaxWidth(), preview = true)
        OutlinedButton(onClick = onExpand, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.updates_expand_notes))
        }
    }
}

/** The reader replaces the body, keeping close and collapse outside the scrollable notes. */
@Composable
internal fun ReleaseNotesReader(
    release: UpdateRelease,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.updates_release_notes),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        release.versionName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onCollapse) { Text(stringResource(R.string.updates_collapse_notes)) }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
            ReleaseNotesMarkdown(release.notes, Modifier.fillMaxWidth())
        }
    }
}

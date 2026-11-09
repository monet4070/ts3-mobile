package io.github.ts3mobile.app.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R

@Composable
internal fun UpdateReleaseCard(
    release: UpdateRelease,
    installed: InstalledAppVersion,
    onReleasePage: (String) -> Unit,
    onDownload: (UpdateRelease) -> Unit,
) {
    val newer = release.isNewerThan(installed)
    val compatibility = release.compatibilityWith(installed)
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (newer) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (newer) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(if (newer) R.string.updates_available else R.string.updates_current_or_newer),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(release.versionName, style = MaterialTheme.typography.headlineMedium)
            Text(release.name, style = MaterialTheme.typography.titleSmall)
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)) {
                Text(
                    stringResource(if (release.prerelease) R.string.updates_prerelease else R.string.updates_stable),
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.updates_release_version, release.versionName, release.versionCode),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.updates_published, release.publishedAt.take(10)), style = MaterialTheme.typography.bodySmall)
            }
            if (newer && compatibility != UpdateCompatibility.MATCHING_INSTALL) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        stringResource(
                            if (compatibility == UpdateCompatibility.ANDROID_TOO_OLD) {
                                R.string.updates_android_too_old
                            } else {
                                R.string.updates_different_install
                            },
                        ),
                        Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (newer) {
                Button(
                    onClick = { onDownload(release) },
                    enabled = compatibility != UpdateCompatibility.ANDROID_TOO_OLD,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.updates_download)) }
            }
            TextButton(onClick = { onReleasePage(release.pageUrl) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.updates_release_page))
            }
        }
    }
}

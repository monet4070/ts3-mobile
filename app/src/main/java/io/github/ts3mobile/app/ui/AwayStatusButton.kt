package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.PresenceSyncState

@Composable
internal fun AwayStatusButton(
    presence: PresenceSyncState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val away = presence.confirmed?.away == true
    val description =
        stringResource(
            when {
                presence.pending -> R.string.presence_pending
                presence.confirmed == null -> R.string.presence_unconfirmed
                away -> R.string.presence_state_away
                else -> R.string.presence_state_available
            },
        )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { stateDescription = description },
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = MaterialTheme.shapes.small,
            color = if (away) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        ) {
            Box(contentAlignment = Alignment.Center) {
                AppIcon(
                    UiIcon.Away,
                    stringResource(R.string.presence_title),
                    Modifier.size(24.dp),
                    tint = if (away) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

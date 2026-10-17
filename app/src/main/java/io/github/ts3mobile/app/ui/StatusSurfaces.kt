package io.github.ts3mobile.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.protocol.ConnectionPhase

@Composable
internal fun StatusIndicator(phase: ConnectionPhase) {
    val (labelId, color) =
        when (phase) {
            ConnectionPhase.DISCONNECTED -> R.string.status_disconnected to MaterialTheme.colorScheme.onSurfaceVariant
            ConnectionPhase.CONNECTING -> R.string.status_connecting to MaterialTheme.colorScheme.tertiary
            ConnectionPhase.RECONNECTING -> R.string.status_reconnecting to MaterialTheme.colorScheme.tertiary
            ConnectionPhase.CONNECTED ->
                R.string.status_connected to
                    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
                        Color(0xFF238340)
                    } else {
                        Color(0xFF6ED58B)
                    }
            ConnectionPhase.DISCONNECTING -> R.string.status_disconnecting to MaterialTheme.colorScheme.tertiary
            ConnectionPhase.ERROR -> R.string.status_error to MaterialTheme.colorScheme.error
        }

    androidx.compose.material3.Surface(
        color = color.copy(alpha = 0.10f),
        shape = MaterialTheme.shapes.small,
        contentColor = color,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(7.dp))
            Text(stringResource(labelId), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun StatusMessage(
    phase: ConnectionPhase,
    detail: String,
) {
    val background =
        if (phase == ConnectionPhase.ERROR) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val foreground =
        if (phase == ConnectionPhase.ERROR) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Text(
        text = detail,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .background(background, MaterialTheme.shapes.small)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        color = foreground,
        style = MaterialTheme.typography.bodySmall,
    )
}

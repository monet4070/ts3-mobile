package io.github.ts3mobile.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.protocol.Ts3Participant

@Composable
internal fun ChannelParticipantRow(
    participant: Ts3Participant,
    isOwnClient: Boolean,
    depth: Int,
    onPoke: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).heightIn(min = 48.dp)
            .padding(start = (48 + depth * 20).coerceAtMost(112).dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            icon =
                when {
                    participant.isTalking -> UiIcon.Audio
                    participant.isInputMuted -> UiIcon.MicOff
                    participant.isOutputMuted -> UiIcon.VolumeOff
                    else -> UiIcon.User
                },
            contentDescription =
                when {
                    participant.isTalking -> stringResource(R.string.participant_talking)
                    participant.isInputMuted -> stringResource(R.string.participant_input_muted)
                    participant.isOutputMuted -> stringResource(R.string.participant_output_muted)
                    else -> null
                },
            modifier = Modifier.size(19.dp),
            tint = if (participant.isTalking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (participant.isAway) stringResource(R.string.participant_away_name, participant.nickname) else participant.nickname,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isOwnClient) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isOwnClient) {
            Text(
                stringResource(R.string.participant_self),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            val description = stringResource(R.string.poke_participant, participant.nickname)
            IconButton(
                onClick = onPoke,
                modifier = Modifier.size(48.dp),
            ) {
                AppIcon(UiIcon.Poke, description, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
            }
        }
    }
}

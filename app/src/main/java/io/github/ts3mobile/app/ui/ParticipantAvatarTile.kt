package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.protocol.Ts3Participant

@Composable
internal fun ParticipantAvatarTile(
    participant: Ts3Participant,
    previews: ServerImagePreviewScope?,
    visible: Boolean,
) {
    val (icon, statusLabel) =
        when {
            participant.isTalking -> UiIcon.Audio to R.string.participant_talking
            participant.isInputMuted -> UiIcon.MicOff to R.string.participant_input_muted
            participant.isOutputMuted -> UiIcon.VolumeOff to R.string.participant_output_muted
            else -> UiIcon.User to null
        }
    val status = statusLabel?.let { stringResource(it) }
    val avatar = participant.avatar
    if (avatar == null || previews == null) {
        IconTile(icon, Modifier.semantics { if (status != null) contentDescription = status }, accented = participant.isTalking)
        return
    }

    var bitmap by remember(avatar, previews) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(avatar, previews, visible) {
        if (visible && bitmap == null) bitmap = previews.loadAvatar(avatar)
    }
    val previewLabel = stringResource(R.string.server_image_avatar)
    Box(
        Modifier.size(40.dp).clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button) { previews.open(ServerImageTarget.Participant(participant.id), avatar) }
            .semantics {
                contentDescription = previewLabel
                if (status != null) stateDescription = status
            },
    ) {
        val thumbnail = bitmap
        if (thumbnail == null) {
            IconTile(icon, accented = participant.isTalking)
        } else {
            Image(
                remember(thumbnail) { thumbnail.asImageBitmap() },
                contentDescription = null,
                modifier = Modifier.fillMaxSize().testTag("participant_avatar_thumbnail"),
                contentScale = ContentScale.Crop,
            )
            if (status != null) {
                val colors = MaterialTheme.colorScheme
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).size(16.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (participant.isTalking) colors.primaryContainer else colors.surfaceVariant,
                    contentColor = if (participant.isTalking) colors.onPrimaryContainer else colors.onSurfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) { AppIcon(icon, null, Modifier.size(11.dp)) }
                }
            }
        }
    }
}

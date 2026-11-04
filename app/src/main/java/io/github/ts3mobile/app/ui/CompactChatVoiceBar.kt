package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.TeamSpeakServiceState

@Composable
internal fun CompactChatVoiceBar(
    state: TeamSpeakServiceState,
    onMode: (MicrophoneMode) -> Unit,
    onPtt: (Boolean) -> Unit,
) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            if (state.microphoneMode == MicrophoneMode.PUSH_TO_TALK) {
                PushToTalkButton(state.isTransmitting, onPtt)
            } else {
                Text(
                    stringResource(
                        if (state.microphoneMode == MicrophoneMode.OFF) {
                            R.string.microphone_state_off
                        } else if (state.isTransmitting && state.microphoneMode == MicrophoneMode.VOLUME_GATE) {
                            R.string.microphone_talking
                        } else if (state.isTransmitting) {
                            R.string.microphone_state_continuous
                        } else if (state.microphoneMode == MicrophoneMode.VOLUME_GATE && state.isCapturing) {
                            R.string.microphone_state_listening
                        } else if (state.microphoneError == io.github.ts3mobile.app.service.UserMessage.MicrophoneResumeInApp) {
                            R.string.microphone_state_deferred
                        } else {
                            R.string.microphone_state_starting
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        IconButton(onClick = {
            onPtt(false)
            onMode(MicrophoneMode.OFF)
        }) {
            AppIcon(UiIcon.MicOff, stringResource(R.string.chat_stop_microphone))
        }
    }
}

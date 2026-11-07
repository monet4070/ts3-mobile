package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.audio.opus.MicrophoneLevel
import io.github.ts3mobile.protocol.ConnectionPhase
import kotlinx.coroutines.flow.Flow

@Composable
internal fun MicrophoneTuningCapture(
    state: TeamSpeakServiceState,
    levels: Flow<MicrophoneLevel?>,
    onPushToTalkChanged: (Boolean) -> Unit,
    onMicrophoneModeChanged: (MicrophoneMode) -> Unit,
) {
    ReleasePushToTalkEffect(onPushToTalkChanged)
    Column {
        MicrophoneLevelIndicator(levels, state.isCapturing, state.isTransmitting)
        if (state.status.phase == ConnectionPhase.CONNECTED && state.microphoneMode == MicrophoneMode.PUSH_TO_TALK) {
            Text(stringResource(R.string.microphone_tuning_send_warning))
            PushToTalkButton(state.isTransmitting, onPushToTalkChanged)
        } else if (state.isCapturing || state.microphoneMode.requiresContinuousCapture) {
            TextButton(onClick = { onMicrophoneModeChanged(MicrophoneMode.OFF) }) {
                Text(stringResource(R.string.chat_stop_microphone))
            }
        }
    }
}

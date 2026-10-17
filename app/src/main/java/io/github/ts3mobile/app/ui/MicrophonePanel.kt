package io.github.ts3mobile.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.MicrophoneMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MicrophoneControl(
    mode: MicrophoneMode,
    isTransmitting: Boolean,
    captureDeferred: Boolean,
    onMicrophoneModeChanged: (MicrophoneMode) -> Unit,
    onPushToTalkChanged: (Boolean) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MicrophoneModeSelector(mode, onMicrophoneModeChanged)

            when (mode) {
                MicrophoneMode.PUSH_TO_TALK ->
                    PushToTalkButton(
                        isTransmitting = isTransmitting,
                        onPushToTalkChanged = onPushToTalkChanged,
                    )

                MicrophoneMode.OFF,
                MicrophoneMode.CONTINUOUS,
                ->
                    Row(
                        modifier = Modifier.height(58.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AppIcon(
                            icon =
                                if (mode == MicrophoneMode.OFF) {
                                    UiIcon.MicOff
                                } else {
                                    UiIcon.Mic
                                },
                            contentDescription = null,
                            tint =
                                if (isTransmitting) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                        Text(
                            text =
                                stringResource(
                                    if (mode == MicrophoneMode.OFF) {
                                        R.string.microphone_state_off
                                    } else if (isTransmitting) {
                                        R.string.microphone_state_continuous
                                    } else if (captureDeferred) {
                                        R.string.microphone_state_deferred
                                    } else {
                                        R.string.microphone_state_starting
                                    },
                                ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
            }
        }
    }
}

@Composable
private fun PushToTalkButton(
    isTransmitting: Boolean,
    onPushToTalkChanged: (Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val currentPushToTalkChanged by rememberUpdatedState(onPushToTalkChanged)
    val active = pressed || isTransmitting
    val talkDescription =
        stringResource(if (active) R.string.microphone_talking else R.string.microphone_push_to_talk)

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = talkDescription
                    onClick {
                        onPushToTalkChanged(!isTransmitting)
                        true
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        pressed = true
                        currentPushToTalkChanged(true)
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            pressed = false
                            currentPushToTalkChanged(false)
                        }
                    }
                },
        shape = MaterialTheme.shapes.small,
        color =
            if (active) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        contentColor =
            if (active) {
                MaterialTheme.colorScheme.onError
            } else {
                MaterialTheme.colorScheme.onPrimary
            },
        shadowElevation = 0.dp,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            AppIcon(
                icon = UiIcon.Mic,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(talkDescription, style = MaterialTheme.typography.labelLarge)
        }
    }
}

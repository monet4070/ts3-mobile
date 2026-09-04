package io.github.ts3mobile.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.service.MicrophoneMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MicrophoneControl(
    mode: MicrophoneMode,
    isTransmitting: Boolean,
    onMicrophoneModeChanged: (MicrophoneMode) -> Unit,
    onPushToTalkChanged: (Boolean) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                MicrophoneMode.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = mode == option,
                        onClick = { onMicrophoneModeChanged(option) },
                        shape =
                            SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = MicrophoneMode.entries.size,
                            ),
                    ) {
                        Text(
                            when (option) {
                                MicrophoneMode.OFF -> "关闭"
                                MicrophoneMode.PUSH_TO_TALK -> "按住"
                                MicrophoneMode.CONTINUOUS -> "常开"
                            },
                        )
                    }
                }
            }

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
                        Icon(
                            imageVector =
                                if (mode == MicrophoneMode.OFF) {
                                    Icons.Outlined.MicOff
                                } else {
                                    Icons.Filled.Mic
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
                                if (mode == MicrophoneMode.OFF) {
                                    "麦克风已关闭"
                                } else if (isTransmitting) {
                                    "麦克风常开中"
                                } else {
                                    "正在启动麦克风"
                                },
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

    Surface(
        modifier =
            Modifier
                .size(58.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = if (active) "正在说话" else "按住说话"
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
        shape = CircleShape,
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
        shadowElevation = 2.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

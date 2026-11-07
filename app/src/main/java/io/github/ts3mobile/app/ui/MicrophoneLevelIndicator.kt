package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ts3mobile.app.R
import io.github.ts3mobile.audio.opus.MicrophoneLevel
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

/** Only this subtree collects the scalar flow. No per-frame accessibility announcement. */
@Composable
internal fun MicrophoneLevelIndicator(
    levels: Flow<MicrophoneLevel?>,
    isCapturing: Boolean,
    isTransmitting: Boolean,
) {
    val latest by levels.collectAsStateWithLifecycle(initialValue = null)
    val level = latest.takeIf { isCapturing }
    val stateText =
        stringResource(
            when {
                !isCapturing -> R.string.microphone_level_off
                level == null -> R.string.microphone_level_waiting
                level.transmitting && isTransmitting -> R.string.microphone_level_sending
                level.gateEnabled && !level.gateOpen -> R.string.microphone_level_gate_closed
                else -> R.string.microphone_level_capturing
            },
        )
    Column(Modifier.fillMaxWidth().testTag("microphone_level").clearAndSetSemantics { contentDescription = stateText }) {
        Text(stateText, style = MaterialTheme.typography.labelLarge)
        Text(
            level?.let { stringResource(R.string.microphone_level_value, it.rmsDb.roundToInt()) }
                ?: stringResource(R.string.microphone_level_no_sample),
            style = MaterialTheme.typography.bodySmall,
        )
        LinearProgressIndicator(
            progress = { ((level?.rmsDb?.toFloat() ?: -96f) + 96f) / 96f },
            modifier = Modifier.fillMaxWidth(),
        )
        if (level?.gateEnabled == true) {
            Text(
                stringResource(if (level.gateOpen) R.string.microphone_level_gate_open else R.string.microphone_level_gate_closed),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

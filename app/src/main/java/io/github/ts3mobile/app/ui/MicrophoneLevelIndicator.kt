package io.github.ts3mobile.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ts3mobile.app.R
import io.github.ts3mobile.audio.opus.MicrophoneLevel
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

/** Only this subtree collects the scalar flow. No per-frame accessibility announcement. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MicrophoneLevelIndicator(
    levels: Flow<MicrophoneLevel?>,
    isCapturing: Boolean,
    isTransmitting: Boolean,
    gateThresholdDb: Int? = null,
    onThresholdChange: (Int) -> Unit = {},
    onThresholdCommit: () -> Unit = {},
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
    Column(Modifier.fillMaxWidth().testTag("microphone_level"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.clearAndSetSemantics { contentDescription = stateText }) {
            Text(stateText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                level?.let { stringResource(R.string.microphone_level_value, it.rmsDb.roundToInt()) }
                    ?: stringResource(R.string.microphone_level_no_sample),
                style = MaterialTheme.typography.titleMedium,
            )
            if (level?.gateEnabled == true && level.gateOpen) {
                Text(stringResource(R.string.microphone_level_gate_open), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (gateThresholdDb == null) {
            LinearProgressIndicator(
                progress = { ((level?.rmsDb?.toFloat() ?: -96f) + 96f) / 96f },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            val trackColor = MaterialTheme.colorScheme.outlineVariant
            val levelColor = if (level?.gateOpen == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            val thresholdLabel = stringResource(R.string.microphone_gate_threshold)
            Slider(
                value = gateThresholdDb.toFloat(),
                onValueChange = { onThresholdChange(it.roundToInt().coerceIn(-60, -15)) },
                onValueChangeFinished = onThresholdCommit,
                valueRange = -60f..-15f,
                modifier = Modifier.fillMaxWidth().testTag("microphone_gate_slider").semantics { contentDescription = thresholdLabel },
                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary),
                track = {
                    // The custom track occupies the Slider's own track bounds, so the live
                    // fill and thumb use exactly the same -60..-15 dBFS scale.
                    val fraction = (((level?.rmsDb ?: -96.0) + 60.0) / 45.0).toFloat().coerceIn(0f, 1f)
                    Canvas(Modifier.fillMaxWidth().height(12.dp).testTag("microphone_gate_track")) {
                        val radius = CornerRadius(size.height / 2)
                        drawRoundRect(trackColor, cornerRadius = radius)
                        if (fraction > 0f) {
                            val start = if (layoutDirection == LayoutDirection.Rtl) size.width * (1f - fraction) else 0f
                            drawRoundRect(
                                levelColor,
                                topLeft = Offset(start, 0f),
                                size = Size(size.width * fraction, size.height),
                                cornerRadius = radius,
                            )
                        }
                    }
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("−60", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("−15 dBFS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stringResource(R.string.microphone_gate_value, gateThresholdDb),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

package io.github.ts3mobile.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.settings.MicrophonePreferenceStatus
import kotlin.math.roundToInt

@Composable
internal fun MicrophoneInputVolumeSlider(
    volumePercent: Int,
    onVolumeChanged: (Int) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.microphone_input_volume)
    Slider(
        value = volumePercent.toFloat(),
        onValueChange = { onVolumeChanged(it.roundToInt().coerceIn(0, 200)) },
        onValueChangeFinished = onCommit,
        valueRange = 0f..200f,
        modifier = modifier.semantics { contentDescription = label },
    )
}

@Composable
internal fun MicrophoneVolumeShortcut(
    volumePercent: Int,
    preferenceStatus: MicrophonePreferenceStatus,
    onVolumeChanged: (Int) -> Unit,
    onCommit: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val spacing = with(LocalDensity.current) { 6.dp.roundToPx() }
    val placement = remember(spacing) { AboveVolumeControl(spacing) }
    val currentCommit by rememberUpdatedState(onCommit)
    val readFailed = preferenceStatus == MicrophonePreferenceStatus.READ_FAILED
    val failureLabel =
        when (preferenceStatus) {
            MicrophonePreferenceStatus.READ_FAILED -> R.string.microphone_quick_volume_read_failed
            MicrophonePreferenceStatus.WRITE_FAILED -> R.string.microphone_quick_volume_unsaved
            else -> null
        }
    Box {
        IconButton(
            onClick = {
                onOpen()
                expanded = true
            },
            enabled = preferenceStatus != MicrophonePreferenceStatus.LOADING,
        ) {
            AppIcon(
                UiIcon.Mic,
                stringResource(R.string.microphone_quick_volume),
                tint =
                    when {
                        failureLabel != null -> MaterialTheme.colorScheme.error
                        expanded -> MaterialTheme.colorScheme.primary
                        preferenceStatus == MicrophonePreferenceStatus.LOADING -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        if (expanded) {
            Popup(placement, onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
                DisposableEffect(Unit) { onDispose { currentCommit() } }
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation = 6.dp,
                ) {
                    Column(
                        Modifier.widthIn(min = 64.dp, max = 176.dp).padding(horizontal = 8.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (preferenceStatus == MicrophonePreferenceStatus.LOADING) {
                            Text(stringResource(R.string.microphone_preferences_loading))
                        } else if (!readFailed) {
                            Text(
                                stringResource(R.string.microphone_quick_volume_percent, volumePercent),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                MicrophoneInputVolumeSlider(
                                    volumePercent,
                                    onVolumeChanged,
                                    onCommit,
                                    Modifier.size(width = 48.dp, height = 144.dp).verticalVolumeSlider(),
                                )
                            }
                        }
                        if (failureLabel != null) {
                            Text(stringResource(failureLabel), color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry_control)) }
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.verticalVolumeSlider() =
    layout { measurable, constraints ->
        val slider =
            measurable.measure(
                Constraints(
                    minWidth = constraints.minHeight,
                    maxWidth = constraints.maxHeight,
                    minHeight = constraints.minWidth,
                    maxHeight = constraints.maxWidth,
                ),
            )
        layout(slider.height, slider.width) {
            slider.placeWithLayer((slider.height - slider.width) / 2, (slider.width - slider.height) / 2) { rotationZ = -90f }
        }
    }

private class AboveVolumeControl(private val spacing: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset =
        IntOffset(
            (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.top - spacing - popupContentSize.height).coerceIn(
                0,
                (windowSize.height - popupContentSize.height).coerceAtLeast(0),
            ),
        )
}

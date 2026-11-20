package io.github.ts3mobile.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.MicrophoneTrialAccess
import io.github.ts3mobile.app.service.MicrophoneTrialPhase
import io.github.ts3mobile.app.service.TeamSpeakService
import io.github.ts3mobile.app.service.microphoneTrialAccess
import io.github.ts3mobile.app.settings.MicrophonePreferenceStatus
import kotlin.math.roundToInt

@Composable
internal fun MicrophoneTrialDialog(
    binder: TeamSpeakService.SessionBinder,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val permission = Manifest.permission.RECORD_AUDIO
    var startWhenResumed by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionRequest =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionDenied = !granted
            startWhenResumed = granted
        }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val state by binder.microphoneTrialState.collectAsStateWithLifecycle()
    val session by binder.state.collectAsStateWithLifecycle()
    val preferences by binder.microphonePreferenceState.collectAsStateWithLifecycle()
    val waitingForTuning = preferences.status == MicrophonePreferenceStatus.LOADING
    val access = session.microphoneTrialAccess
    val mustTurnOffVoice = access == MicrophoneTrialAccess.TURN_OFF_VOICE
    val running = state.phase == MicrophoneTrialPhase.RUNNING
    LaunchedEffect(lifecycle, startWhenResumed, binder) {
        if (lifecycle == Lifecycle.State.RESUMED && startWhenResumed) {
            startWhenResumed = false
            binder.startMicrophoneTrial()
        }
    }
    DisposableEffect(binder) { onDispose { binder.stopMicrophoneTrial() } }

    InputDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.microphone_trial_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.microphone_trial_description))
                when {
                    waitingForTuning -> Text(stringResource(R.string.microphone_preferences_loading))
                    access == MicrophoneTrialAccess.UNAVAILABLE -> Text(stringResource(R.string.settings_trial_wait))
                    mustTurnOffVoice -> Text(stringResource(R.string.microphone_trial_turn_off_hint))
                    permissionDenied -> Text(stringResource(R.string.microphone_trial_permission))
                    running -> {
                        Text(stringResource(R.string.microphone_trial_input, stringResource(inputDeviceLabel(state.deviceType))))
                        val sample = state.level
                        if (sample == null) {
                            Text(stringResource(R.string.microphone_level_waiting))
                        } else {
                            val level = sample.rmsDb.roundToInt()
                            LinearProgressIndicator(
                                progress = { ((level + 60f) / 60f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(stringResource(R.string.microphone_level_value, level))
                            Text(stringResource(R.string.microphone_gate_value, sample.thresholdDb))
                            Text(
                                stringResource(
                                    if (sample.gateOpen) R.string.microphone_level_gate_open else R.string.microphone_trial_gate_closed,
                                ),
                            )
                        }
                    }
                    state.phase == MicrophoneTrialPhase.TIMED_OUT -> Text(stringResource(R.string.microphone_trial_timeout))
                    state.phase == MicrophoneTrialPhase.UNAVAILABLE -> Text(stringResource(R.string.microphone_trial_unavailable))
                    state.phase == MicrophoneTrialPhase.FAILED -> Text(stringResource(R.string.microphone_trial_failed))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (running) {
                        binder.stopMicrophoneTrial()
                    } else if (mustTurnOffVoice) {
                        binder.setMicrophoneMode(MicrophoneMode.OFF)
                    } else {
                        permissionDenied = false
                        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                            startWhenResumed = true
                        } else {
                            permissionRequest.launch(permission)
                        }
                    }
                },
                enabled = running || (!waitingForTuning && access != MicrophoneTrialAccess.UNAVAILABLE),
            ) {
                AppIcon(if (running || mustTurnOffVoice) UiIcon.MicOff else UiIcon.Mic, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(
                        when {
                            running -> R.string.microphone_trial_stop
                            mustTurnOffVoice -> R.string.microphone_trial_turn_off_voice
                            else -> R.string.microphone_trial_start
                        },
                    ),
                )
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

private fun inputDeviceLabel(type: Int?): Int =
    when (type) {
        null, AudioDeviceInfo.TYPE_UNKNOWN -> R.string.microphone_trial_input_unknown
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> R.string.microphone_trial_input_builtin
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> R.string.microphone_trial_input_bluetooth
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> R.string.microphone_trial_input_headset
        else -> R.string.microphone_trial_input_other
    }

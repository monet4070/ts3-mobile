package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.chat.ChatState
import io.github.ts3mobile.protocol.MAX_TEXT_INPUT_BYTES

@Composable
internal fun ChatComposer(
    state: ChatState,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onFocus: () -> Unit,
) {
    val tooLong = state.draftOverflow || state.draftBytes > MAX_TEXT_INPUT_BYTES
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val warning = tooLong || !state.connected || state.switching && !state.active.isPrivate || state.sending || state.target == null
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f).testTag("chat_draft").onFocusChanged { if (it.isFocused) onFocus() },
                placeholder = { Text(stringResource(R.string.chat_draft)) },
                shape = MaterialTheme.shapes.large,
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                maxLines = if (keyboardOpen) 2 else 3,
                isError = tooLong,
                supportingText =
                    if (warning) {
                        {
                            Text(
                                stringResource(
                                    when {
                                        tooLong -> R.string.chat_too_long
                                        !state.connected -> R.string.chat_read_only
                                        state.target == null && state.active.isPrivate -> R.string.chat_private_unavailable
                                        state.switching && !state.active.isPrivate -> R.string.chat_switching
                                        state.sending -> R.string.chat_sending
                                        state.target == null -> R.string.chat_no_channel
                                        else -> R.string.chat_limit
                                    },
                                    MAX_TEXT_INPUT_BYTES,
                                ),
                            )
                        }
                    } else {
                        null
                    },
            )
            FilledIconButton(onClick = onSend, enabled = state.canSend, modifier = Modifier.padding(start = 8.dp).testTag("chat_send")) {
                AppIcon(UiIcon.ArrowRight, stringResource(R.string.chat_send))
            }
        }
    }
}

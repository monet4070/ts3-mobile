package io.github.ts3mobile.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.github.ts3mobile.app.R

@Composable
internal fun ChannelPasswordDialog(
    channelName: String,
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit,
) {
    var channelPassword by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {
            onDismiss()
            channelPassword = ""
            passwordVisible = false
        },
        title = { Text(stringResource(R.string.channel_join_title, channelName)) },
        text = {
            OutlinedTextField(
                shape = MaterialTheme.shapes.small,
                colors = connectionFieldColors(),
                value = channelPassword,
                onValueChange = { channelPassword = it },
                singleLine = true,
                label = { Text(stringResource(R.string.field_channel_password)) },
                leadingIcon = { AppIcon(UiIcon.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        AppIcon(
                            icon =
                                if (passwordVisible) {
                                    UiIcon.EyeOff
                                } else {
                                    UiIcon.Eye
                                },
                            contentDescription =
                                stringResource(
                                    if (passwordVisible) {
                                        R.string.action_hide_password
                                    } else {
                                        R.string.action_show_password
                                    },
                                ),
                        )
                    }
                },
                visualTransformation =
                    if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onJoin(channelPassword)
                    onDismiss()
                    channelPassword = ""
                    passwordVisible = false
                },
            ) {
                Text(stringResource(R.string.action_join))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    channelPassword = ""
                    passwordVisible = false
                },
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

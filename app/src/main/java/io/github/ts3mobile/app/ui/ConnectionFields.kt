package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import io.github.ts3mobile.app.ConnectionFormState
import io.github.ts3mobile.app.R

@Composable
internal fun ConnectionFields(
    form: ConnectionFormState,
    enabled: Boolean,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onNicknameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val invalidHost = form.submitted && form.host.isBlank()
    val invalidPort = form.submitted && (form.port.toIntOrNull() !in 1..65535)
    val invalidNickname = form.submitted && form.nickname.trim().length !in 3..30
    OutlinedTextField(
        shape = MaterialTheme.shapes.small,
        colors = connectionFieldColors(),
        value = form.host,
        onValueChange = onHostChanged,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(R.string.field_server_address), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        placeholder = { Text(stringResource(R.string.field_server_address_hint)) },
        leadingIcon = { AppIcon(UiIcon.Server, contentDescription = null) },
        isError = invalidHost,
        supportingText =
            if (invalidHost) {
                { Text(stringResource(R.string.error_server_address_required)) }
            } else {
                null
            },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        shape = MaterialTheme.shapes.small,
        colors = connectionFieldColors(),
        value = form.port,
        onValueChange = onPortChanged,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(R.string.field_port), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        isError = invalidPort,
        supportingText =
            if (invalidPort) {
                { Text(stringResource(R.string.field_port_range)) }
            } else {
                null
            },
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
            ),
    )

    OutlinedTextField(
        shape = MaterialTheme.shapes.small,
        colors = connectionFieldColors(),
        value = form.nickname,
        onValueChange = onNicknameChanged,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(R.string.field_nickname), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { AppIcon(UiIcon.User, contentDescription = null) },
        isError = invalidNickname,
        supportingText =
            if (invalidNickname) {
                { Text(stringResource(R.string.error_nickname_length)) }
            } else {
                null
            },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )

    OutlinedTextField(
        shape = MaterialTheme.shapes.small,
        colors = connectionFieldColors(),
        value = form.password,
        onValueChange = onPasswordChanged,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(R.string.field_server_password), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    )
}

package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.ConnectionFormState
import io.github.ts3mobile.app.R
import io.github.ts3mobile.protocol.ConnectionPhase

@Composable
internal fun ConnectionForm(
    form: ConnectionFormState,
    phase: ConnectionPhase,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onNicknameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val isConnecting =
        phase == ConnectionPhase.CONNECTING ||
            phase == ConnectionPhase.RECONNECTING ||
            phase == ConnectionPhase.DISCONNECTING
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val invalidHost = form.submitted && form.host.isBlank()
    val invalidPort = form.submitted && (form.port.toIntOrNull() !in 1..65535)
    val invalidNickname = form.submitted && form.nickname.trim().length !in 3..30

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.connect_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            OutlinedTextField(
                value = form.host,
                onValueChange = onHostChanged,
                modifier = Modifier.weight(1f),
                enabled = !isConnecting,
                singleLine = true,
                label = { Text(stringResource(R.string.field_server_address)) },
                placeholder = { Text(stringResource(R.string.field_server_address_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
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
                value = form.port,
                onValueChange = onPortChanged,
                modifier = Modifier.width(108.dp),
                enabled = !isConnecting,
                singleLine = true,
                label = { Text(stringResource(R.string.field_port)) },
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
        }

        OutlinedTextField(
            value = form.nickname,
            onValueChange = onNicknameChanged,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isConnecting,
            singleLine = true,
            label = { Text(stringResource(R.string.field_nickname)) },
            leadingIcon = { Icon(Icons.Outlined.AlternateEmail, contentDescription = null) },
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
            value = form.password,
            onValueChange = onPasswordChanged,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isConnecting,
            singleLine = true,
            label = { Text(stringResource(R.string.field_server_password)) },
            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector =
                            if (passwordVisible) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
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

        Spacer(Modifier.height(4.dp))

        if (isConnecting) {
            OutlinedButton(
                onClick = onDisconnect,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                enabled = phase != ConnectionPhase.DISCONNECTING,
            ) {
                if (phase != ConnectionPhase.DISCONNECTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        when (phase) {
                            ConnectionPhase.RECONNECTING -> R.string.action_cancel_reconnect
                            ConnectionPhase.DISCONNECTING -> R.string.action_disconnecting
                            else -> R.string.action_cancel_connect
                        },
                    ),
                )
            }
        } else {
            Button(
                onClick = onConnect,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp),
            ) {
                Icon(Icons.Default.Link, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (phase == ConnectionPhase.ERROR) R.string.action_reconnect else R.string.action_connect))
            }
        }
    }
}

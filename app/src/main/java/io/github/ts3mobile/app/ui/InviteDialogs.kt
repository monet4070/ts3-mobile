package io.github.ts3mobile.app.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.invite.IgnoredInviteField
import io.github.ts3mobile.app.invite.InvitePreview
import io.github.ts3mobile.app.invite.ServerInvite
import io.github.ts3mobile.app.invite.configuration
import io.github.ts3mobile.app.service.allowsOfflineActions
import io.github.ts3mobile.protocol.ConnectionPhase

@Composable
internal fun ShareInviteDialog(
    onShareServer: () -> Unit,
    onShareChannel: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    InputDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.invite_share_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsActionGroup(
                    listOfNotNull(
                        SettingsAction(R.string.invite_share_server, UiIcon.Server) {
                            onDismiss()
                            onShareServer()
                        },
                        onShareChannel?.let { share ->
                            SettingsAction(R.string.invite_share_channel, UiIcon.Channel) {
                                onDismiss()
                                share()
                            }
                        },
                    ),
                )
            }
        },
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun PasteInviteAction(
    enabled: Boolean,
    onInvite: (String) -> Unit,
) {
    var showPaste by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { showPaste = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small,
    ) {
        AppIcon(UiIcon.Copy, null, Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.invite_paste))
    }
    if (enabled && showPaste) {
        PasteInviteDialog({ showPaste = false }) {
            showPaste = false
            onInvite(it)
        }
    }
    LaunchedEffect(enabled) { if (!enabled) showPaste = false }
}

@Composable
internal fun PasteInviteDialog(
    onDismiss: () -> Unit,
    onPreview: (String) -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var clipboardEmpty by remember { mutableStateOf(false) }
    InputDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.invite_paste)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    text,
                    { if (it.length <= ServerInvite.MAX_LENGTH) text = it },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.invite_link)) },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    val clip = clipboard?.primaryClip
                    val clipboardText =
                        clip?.takeIf {
                            it.itemCount > 0 && (
                                it.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                                    it.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
                            )
                        }?.getItemAt(0)?.text
                    clipboardEmpty = clipboardText.isNullOrBlank()
                    if (!clipboardText.isNullOrBlank()) onPreview(clipboardText.take(ServerInvite.MAX_LENGTH + 1).toString())
                }) { Text(stringResource(R.string.invite_clipboard)) }
                if (clipboardEmpty) Text(stringResource(R.string.invite_clipboard_empty))
            }
        },
        confirmButton = {
            Button(onClick = { onPreview(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.invite_preview)) }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun InviteDialog(
    preview: InvitePreview,
    defaultNickname: String,
    phase: ConnectionPhase?,
    ready: Boolean,
    onDismiss: () -> Unit,
    onDisconnect: () -> Unit,
    onConfirm: (InvitePreview.Target, String, String, String) -> Unit,
) {
    if (preview is InvitePreview.Target) {
        key(preview) {
            val invite = preview.invite
            var nicknameEdit by remember { mutableStateOf<String?>(null) }
            var password by remember { mutableStateOf("") }
            var channelPassword by remember { mutableStateOf("") }
            val nickname = nicknameEdit ?: defaultNickname
            val offline = phase?.allowsOfflineActions == true
            val valid = invite.configuration(nickname, password, channelPassword).validationError() == null
            InputDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.invite_preview)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.invite_target, invite.endpoint))
                        invite.channelId?.let { Text(stringResource(R.string.invite_channel, it)) }
                        if (invite.ignored.isNotEmpty()) {
                            val fields = invite.ignored.map { stringResource(it.label) }.joinToString(", ")
                            Text(stringResource(R.string.invite_ignored, fields))
                        }
                        if (phase != null && !offline) Text(stringResource(R.string.invite_existing_session))
                        OutlinedTextField(
                            nickname,
                            { nicknameEdit = it },
                            Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.field_nickname)) },
                            singleLine = true,
                            isError = !valid,
                            supportingText =
                                if (valid) {
                                    null
                                } else {
                                    { Text(stringResource(R.string.invite_nickname_length)) }
                                },
                        )
                        InvitePasswordField(password, { password = it }, R.string.field_server_password)
                        if (invite.channelId != null) {
                            InvitePasswordField(channelPassword, { channelPassword = it }, R.string.field_channel_password)
                        }
                    }
                },
                confirmButton = {
                    if (phase != null && !offline && phase != ConnectionPhase.DISCONNECTING) {
                        Button(onClick = onDisconnect) { Text(stringResource(R.string.action_disconnect)) }
                    } else {
                        Button(
                            onClick = { onConfirm(preview, nickname, password, channelPassword) },
                            enabled = ready && offline && valid,
                        ) { Text(stringResource(R.string.action_connect)) }
                    }
                },
                dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
    } else {
        InputDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.invite_preview)) },
            text = { Text(stringResource(R.string.invite_invalid)) },
            confirmButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun InvitePasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
) {
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth(),
        label = { Text(stringResource(label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

private val IgnoredInviteField.label: Int
    get() =
        when (this) {
            IgnoredInviteField.NICKNAME -> R.string.field_nickname
            IgnoredInviteField.PASSWORD -> R.string.invite_ignored_server_password
            IgnoredInviteField.CHANNEL_NAME -> R.string.invite_ignored_channel_name
            IgnoredInviteField.CHANNEL_PASSWORD -> R.string.field_channel_password
            IgnoredInviteField.TOKEN -> R.string.invite_ignored_token
            IgnoredInviteField.BOOKMARK -> R.string.invite_ignored_bookmark
            IgnoredInviteField.OTHER -> R.string.invite_ignored_other
        }

internal fun Context.shareInvite(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, getString(R.string.invite_share_server)))
}

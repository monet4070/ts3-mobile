package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.ts3mobile.app.R

@Composable
internal fun ParticipantChatAction(
    nickname: String,
    onOpen: () -> Unit,
    onPoke: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            AppIcon(UiIcon.ChevronDown, stringResource(R.string.chat_user_actions, nickname))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.chat_open_private)) }, onClick = {
                expanded = false
                onOpen()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.poke_action)) }, onClick = {
                expanded = false
                onPoke()
            })
        }
    }
}

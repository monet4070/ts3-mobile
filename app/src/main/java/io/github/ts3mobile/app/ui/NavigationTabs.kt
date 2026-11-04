package io.github.ts3mobile.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R

internal enum class SessionTab { CHANNELS, PARTICIPANTS, CHAT }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NavigationTabs(
    selected: SessionTab,
    onSelect: (SessionTab) -> Unit,
    unread: Int = 0,
) {
    val tabs =
        listOf(
            R.string.tab_channels to UiIcon.Channel,
            R.string.tab_participants to UiIcon.Users,
            R.string.tab_chat to UiIcon.Chat,
        )
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        val scrollable = LocalDensity.current.fontScale > 1.3f || maxWidth < 328.dp
        Row(
            Modifier.fillMaxWidth()
                .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEachIndexed { index, (label, icon) ->
                val tab = SessionTab.entries[index]
                val unreadDescription =
                    if (tab == SessionTab.CHAT && unread > 0) {
                        pluralStringResource(R.plurals.chat_unread_messages, unread, unread)
                    } else {
                        null
                    }
                val bringIntoView = remember { BringIntoViewRequester() }
                LaunchedEffect(selected, scrollable) {
                    if (selected == tab) bringIntoView.bringIntoView()
                }
                Surface(
                    modifier =
                        (if (scrollable) Modifier.widthIn(min = 112.dp) else Modifier.weight(1f))
                            .bringIntoViewRequester(bringIntoView),
                    shape = MaterialTheme.shapes.small,
                    color = if (selected == tab) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
                    contentColor =
                        if (selected == tab) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                ) {
                    Row(
                        Modifier.selectable(selected == tab, role = Role.Tab, onClick = { onSelect(tab) })
                            .semantics { if (unreadDescription != null) stateDescription = unreadDescription }
                            .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    ) {
                        AppIcon(icon, null, Modifier.size(18.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                        if (tab == SessionTab.CHAT && unread > 0) {
                            Badge { Text(if (unread > 99) "99+" else unread.toString()) }
                        }
                    }
                }
            }
        }
    }
}

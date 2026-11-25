package io.github.ts3mobile.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R

@Composable
internal fun SettingsPage(
    title: Int,
    onBack: () -> Unit,
    snackbar: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    SettingsScaffold(title, onBack, snackbar) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScaffold(
    title: Int,
    onBack: () -> Unit,
    snackbar: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val largeTitle = LocalDensity.current.fontScale > 1.35f
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(title),
                        Modifier.semantics { heading() },
                        style = if (largeTitle) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        maxLines = if (largeTitle) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        AppIcon(
                            UiIcon.ArrowRight,
                            stringResource(R.string.settings_back),
                            Modifier.size(22.dp).rotate(if (LocalLayoutDirection.current == LayoutDirection.Ltr) 180f else 0f),
                        )
                    }
                },
                expandedHeight = if (largeTitle) 92.dp else TopAppBarDefaults.TopAppBarExpandedHeight,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        content = content,
    )
}

internal data class SettingsAction(
    val title: Int,
    val icon: UiIcon,
    val unavailableHint: Int? = null,
    val detail: Int? = null,
    val guideTarget: GuideAnchor? = null,
    val onClick: (() -> Unit)?,
)

@Composable
internal fun SettingsActionGroup(
    actions: List<SettingsAction>,
    title: Int? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) SectionLabel(stringResource(title), Modifier.padding(start = 4.dp).semantics { heading() })
        Panel(Modifier.fillMaxWidth()) {
            actions.forEachIndexed { index, action ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(action)
            }
        }
    }
}

@Composable
private fun SettingsActionRow(action: SettingsAction) {
    val focus = LocalGuideFocus.current
    val enabled = action.onClick != null && (focus == null || action.guideTarget == focus)
    val titleColor = MaterialTheme.colorScheme.onSurface.let { if (enabled) it else it.copy(alpha = 0.5f) }
    val detail = if (enabled) action.detail else action.unavailableHint
    val target = action.guideTarget?.let { Modifier.guideAnchor(it) } ?: Modifier
    Row(
        target.fillMaxWidth().heightIn(min = 60.dp)
            .clickable(enabled = enabled, role = Role.Button) { action.onClick?.invoke() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(action.icon, accented = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(action.title), style = MaterialTheme.typography.titleSmall, color = titleColor)
            if (detail != null) {
                Text(
                    stringResource(detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (enabled) {
            AppIcon(
                UiIcon.ChevronRight,
                null,
                Modifier.size(16.dp).rotate(if (LocalLayoutDirection.current == LayoutDirection.Ltr) 0f else 180f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AppIcon(UiIcon.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

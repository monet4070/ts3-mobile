package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R

@Composable
internal fun GuideDestinationCard(
    step: GuideStep,
    onDismiss: () -> Unit,
    hint: Int? = null,
    status: Int? = null,
) {
    GuideTip(step, hint) {
        if (status != null) GuideStatus(status)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.guide_dismiss_tip)) }
        }
    }
}

@Composable
private fun GuideStatus(status: Int) {
    Text(stringResource(status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun GuideTip(
    step: GuideStep,
    hint: Int? = null,
    actions: @Composable () -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GuideHeading(step.icon, stringResource(step.title))
        if (hint == null) {
            GuideInstructions(step)
        } else {
            Text(stringResource(hint), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        actions()
    }
}

@Composable
private fun GuideInstructions(step: GuideStep) {
    Text(stringResource(step.description), style = MaterialTheme.typography.bodyMedium)
    if (step.title == R.string.guide_identity_title && booleanResource(R.bool.desktop_identity_import_enabled)) {
        Text(
            stringResource(R.string.guide_identity_desktop),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GuideHeading(
    icon: UiIcon,
    title: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconTile(icon)
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        }
    }
}

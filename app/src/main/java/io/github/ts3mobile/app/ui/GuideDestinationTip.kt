package io.github.ts3mobile.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.floatingPttSettingsIntent
import io.github.ts3mobile.app.identity.IdentityManagementActivity

private const val guideRouteExtra = "io.github.ts3mobile.guide.DESTINATION"

internal fun Intent.withGuideRoute(route: GuideRoute): Intent = putExtra(guideRouteExtra, route.name)

internal fun Intent.guideRoute(vararg allowed: GuideRoute): GuideRoute? = allowed.firstOrNull { it.name == getStringExtra(guideRouteExtra) }

internal fun Context.guidedIdentityIntent(): Intent =
    Intent(this, IdentityManagementActivity::class.java).withGuideRoute(GuideRoute.IDENTITY)

internal fun Context.guidedFloatingPttIntent(route: GuideRoute?): Intent =
    floatingPttSettingsIntent().apply {
        if (route == GuideRoute.FLOATING_SETUP || route == GuideRoute.FLOATING_USE) withGuideRoute(route)
    }

@Composable
internal fun GuideSettingsEntry(
    route: GuideRoute?,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val entry = route?.settingsEntry
    if (route == null || entry == null) {
        content()
        return
    }
    val anchors = remember { GuideAnchors() }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalGuideAnchors provides anchors, LocalGuideFocus provides entry) { content() }
        GuideTouchLayer(anchors[entry], onDismiss) {
            GuideDestinationCard(route.step, onDismiss = onDismiss, hint = R.string.guide_tap_settings)
        }
    }
}

@Composable
internal fun GuideDestinationTip(
    route: GuideRoute?,
    content: @Composable () -> Unit,
) {
    var tipDismissed by rememberSaveable(route) { mutableStateOf(false) }
    if (route == null) {
        content()
        return
    }
    val anchors = remember { GuideAnchors() }
    CompositionLocalProvider(
        LocalGuideAnchors provides anchors,
        LocalGuideFocus provides route.anchor.takeUnless { tipDismissed },
    ) { content() }
    if (!tipDismissed) {
        val dismiss = { tipDismissed = true }
        GuideDialog(anchors[route.anchor] ?: route.fallback?.let { anchors[it] }, onBack = dismiss) {
            GuideDestinationCard(route.step, onDismiss = dismiss)
        }
    }
}

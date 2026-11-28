package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.ts3mobile.app.R

@Composable
internal fun MainFeatureHelp(
    available: Boolean,
    connected: Boolean,
    anchors: GuideAnchors,
    filesAvailable: Boolean,
    requestedTitle: Int?,
    onTopicOpened: () -> Unit,
    content: @Composable (navigation: GuideNavigation?) -> Unit,
) {
    var activeTitle by rememberSaveable { mutableStateOf<Int?>(null) }
    val close: () -> Unit = { activeTitle = null }
    LaunchedEffect(requestedTitle, available) {
        if (available && requestedTitle != null) {
            activeTitle = requestedTitle
            onTopicOpened()
        }
    }

    val topic = guideTopics.firstOrNull { it.title == activeTitle }
    var onTargetTab by rememberSaveable(activeTitle) { mutableStateOf(false) }
    val status =
        when {
            topic?.needsConnection == true && !connected -> R.string.guide_connection_required
            topic?.route == GuideRoute.FILES && !filesAvailable -> R.string.guide_visit_unavailable
            else -> null
        }
    val navigation =
        topic?.route?.takeIf { available && status == null && !onTargetTab }?.let { route ->
            GuideNavigation(route, onTabSelected = { onTargetTab = it == route.tab }, onDestinationOpened = close)
        }

    Box(Modifier.fillMaxSize()) {
        content(navigation)
        if (available && topic != null) {
            GuideTopicTip(topic, anchors, connected, navigation, status, close)
        }
    }
}

@Composable
private fun GuideTopicTip(
    topic: GuideStep,
    anchors: GuideAnchors,
    connected: Boolean,
    navigation: GuideNavigation?,
    status: Int?,
    onClose: () -> Unit,
) {
    val directBookmark =
        if (navigation?.route == GuideRoute.BOOKMARKS && !connected) {
            anchors[GuideAnchor.BOOKMARKS]?.takeIf { it.width > 0 && it.height > 0 }
        } else {
            null
        }
    val target =
        when {
            navigation != null -> navigation.route.tab?.guideEntry ?: GuideAnchor.MENU
            status != null -> null
            else -> topic.anchor ?: topic.route?.anchor
        }
    val anchor = directBookmark ?: target?.let { anchors[it] } ?: topic.route?.fallback?.let { anchors[it] }
    if (navigation != null) {
        val hint = if (directBookmark != null || navigation.route.tab != null) R.string.guide_tap_highlight else R.string.guide_tap_more
        GuideTouchLayer(anchor, onClose) { GuideDestinationCard(topic, onDismiss = onClose, hint = hint) }
    } else {
        GuideDialog(anchor, onBack = onClose) {
            GuideDestinationCard(topic, onDismiss = onClose, status = status)
        }
    }
}

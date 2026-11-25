package io.github.ts3mobile.app.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

internal enum class GuideAnchor {
    SERVER,
    INVITE,
    BOOKMARKS,
    MENU,
    OUTPUT,
    DISCONNECT,
    BOOKMARK_ADD,
    IDENTITY_OPERATIONS,
    TEST_MIC,
    PLAYBACK_PROFILE,
    PTT_AUTOSHOW,
    PTT_SHOW,
    USERS_TAB,
    MEMBER_ACTIONS,
    CHAT_CONVERSATIONS,
    CHAT_MESSAGES,
    FILE_ENTRY,
    FILE_LIST,
    HISTORY_TOGGLE,
    NOTIFICATION_SETTINGS,
    DIAGNOSTIC_MARK,
    CHANNELS_TAB,
    CHAT_TAB,
    SETTINGS_IDENTITY,
    SETTINGS_MICROPHONE,
    SETTINGS_PLAYBACK,
    SETTINGS_FLOATING,
    SETTINGS_HISTORY,
    SETTINGS_DIAGNOSTICS,
}

internal val LocalGuideAnchors = staticCompositionLocalOf<GuideAnchors?> { null }
internal val LocalGuideFocus = staticCompositionLocalOf<GuideAnchor?> { null }

internal class GuideAnchors {
    private val positions = mutableStateMapOf<GuideAnchor, Rect>()

    operator fun get(anchor: GuideAnchor): Rect? = positions[anchor]

    fun position(
        anchor: GuideAnchor,
        bounds: Rect,
    ) {
        positions[anchor] = bounds
    }

    fun remove(anchor: GuideAnchor) {
        positions.remove(anchor)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.guideAnchor(anchor: GuideAnchor): Modifier {
    val positions = LocalGuideAnchors.current ?: return this
    DisposableEffect(positions, anchor) { onDispose { positions.remove(anchor) } }
    val focusModifier =
        if (anchor == LocalGuideFocus.current) {
            val requester = remember { BringIntoViewRequester() }
            LaunchedEffect(requester) { requester.bringIntoView() }
            Modifier.bringIntoViewRequester(requester)
        } else {
            Modifier
        }
    return then(focusModifier).onGloballyPositioned { coordinates ->
        val screenOrigin = coordinates.localToScreen(Offset.Zero)
        if (screenOrigin.isSpecified) {
            positions.position(anchor, coordinates.boundsInWindow().translate(screenOrigin - coordinates.positionInWindow()))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GuideDialog(
    anchor: Rect?,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onBack,
        properties =
            DialogProperties(
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        val view = LocalView.current
        val window = ((view as? DialogWindowProvider) ?: (view.parent as? DialogWindowProvider))?.window
        LaunchedEffect(window) { window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND) }
        BackHandler(onBack = onBack)
        val safeInsets = WindowInsets.safeDrawing.union(dialogVisibleBottomInsets())
        var dialogOrigin by remember { mutableStateOf(Offset.Unspecified) }
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { dialogOrigin = it.localToScreen(Offset.Zero) }) {
            val panelMaxHeight = maxHeight * 0.70f
            val viewport = Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            val visibleAnchor =
                anchor?.takeIf { dialogOrigin.isSpecified && it.width > 0 && it.height > 0 }
                    ?.translate(-dialogOrigin)?.takeIf { it.overlaps(viewport) }
            GuideScrim(visibleAnchor)
            val placement =
                when {
                    visibleAnchor == null -> Alignment.Center
                    visibleAnchor.center.y > viewport.center.y -> Alignment.TopCenter
                    else -> Alignment.BottomCenter
                }
            Box(
                Modifier.fillMaxSize().windowInsetsPadding(safeInsets).padding(16.dp),
                contentAlignment = placement,
            ) {
                Panel(Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = panelMaxHeight)) { content() }
            }
        }
    }
}

@Composable
internal fun GuideScrim(anchor: Rect?) {
    val outline = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        val cutout =
            anchor?.let { target ->
                val expanded = target.inflate(5.dp.toPx()).intersect(Rect(0f, 0f, size.width, size.height))
                if (expanded.width > 0 && expanded.height > 0) RoundRect(expanded, CornerRadius(10.dp.toPx())) else null
            }
        val mask =
            Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                cutout?.let(::addRoundRect)
            }
        drawPath(mask, Color.Black.copy(alpha = 0.67f))
        cutout?.let { highlight ->
            drawPath(Path().apply { addRoundRect(highlight) }, outline, style = Stroke(2.dp.toPx()))
        }
    }
}

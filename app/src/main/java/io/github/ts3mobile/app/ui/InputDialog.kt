package io.github.ts3mobile.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Insets constrain the card once, outside its surface; only the body shrinks for the IME. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InputDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit = {},
) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val safeInsets = WindowInsets.safeDrawing.union(dialogVisibleBottomInsets())
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.matchParentSize().background(Color.Transparent).pointerInput(onDismissRequest) {
                    detectTapGestures { onDismissRequest() }
                },
            )
            Box(
                Modifier.fillMaxSize().windowInsetsPadding(safeInsets).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(Modifier.padding(20.dp)) {
                        ProvideTextStyle(MaterialTheme.typography.titleLarge, title)
                        Box(Modifier.weight(1f, fill = false).padding(top = 20.dp, bottom = 16.dp)) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium, text)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        FlowRow(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            dismissButton()
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}

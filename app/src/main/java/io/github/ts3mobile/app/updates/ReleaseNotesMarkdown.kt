package io.github.ts3mobile.app.updates

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.ext.tasklist.TaskListPlugin
import org.commonmark.node.Image
import org.commonmark.node.Link

@Composable
internal fun ReleaseNotesMarkdown(
    notes: String,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val foreground = colors.onSurface.toArgb()
    val accent = colors.primary.toArgb()
    val background = colors.surfaceContainerHighest.toArgb()
    val renderer =
        remember(context, foreground, accent, background) {
            releaseNotesRenderer(context, foreground, accent, background)
        }
    val markdown = remember(renderer, notes) { renderer.toMarkdown(notes) }
    val typography = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current
    val textSizePx = with(density) { typography.fontSize.toPx() }
    val lineHeightPx = with(density) { typography.lineHeight.toPx() }
    AndroidView(
        modifier = modifier,
        factory = {
            TextView(it).apply {
                setPadding(0, 0, 0, 0)
                includeFontPadding = false
            }
        },
        update = {
            it.setTextColor(foreground)
            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
            it.setLineSpacing((lineHeightPx - textSizePx).coerceAtLeast(0f), 1f)
            it.maxLines = if (preview) 6 else Int.MAX_VALUE
            it.ellipsize = if (preview) TextUtils.TruncateAt.END else null
            renderer.setParsedMarkdown(it, markdown)
        },
    )
}

/** Native text only: no WebView, HTML/image loader, or navigation supplied by release content. */
internal fun releaseNotesRenderer(
    context: Context,
    foreground: Int,
    accent: Int,
    background: Int,
): Markwon =
    Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(TableTheme.buildWithDefaults(context).tableHeaderRowBackgroundColor(background).build()))
        .usePlugin(TaskListPlugin.create(accent, foreground, background))
        .usePlugin(
            object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.codeTextColor(foreground).codeBackgroundColor(background).blockQuoteColor(accent)
                        .headingTextSizeMultipliers(floatArrayOf(1.5f, 1.3f, 1.15f, 1.1f, 1f, 1f))
                }

                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    // Render labels/alt text; the explicit release-details button owns browser navigation.
                    builder.setFactory(Link::class.java) { _, _ -> null }
                    builder.setFactory(Image::class.java) { _, _ -> null }
                }
            },
        )
        .build()

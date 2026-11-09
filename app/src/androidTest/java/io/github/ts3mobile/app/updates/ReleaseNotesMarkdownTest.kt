package io.github.ts3mobile.app.updates

import android.graphics.Color
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.StrikethroughSpan
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.descendants
import io.github.ts3mobile.app.ui.theme.Ts3MobileTheme
import io.noties.markwon.core.spans.BlockQuoteSpan
import io.noties.markwon.core.spans.BulletListItemSpan
import io.noties.markwon.core.spans.CodeBlockSpan
import io.noties.markwon.core.spans.CodeSpan
import io.noties.markwon.core.spans.HeadingSpan
import io.noties.markwon.core.spans.OrderedListItemSpan
import io.noties.markwon.core.spans.StrongEmphasisSpan
import io.noties.markwon.ext.tables.TableRowSpan
import io.noties.markwon.ext.tasklist.TaskListSpan
import io.noties.markwon.image.AsyncDrawableSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReleaseNotesMarkdownTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun rendersHeadingsListsEmphasisQuotesAndCodeInsteadOfRawMarkers() {
        val rendered =
            renderer().toMarkdown(
                "# Release\n\n## Fixes\n\n- **Audio** and `JSON`\n\n1. Install\n\n> Keep data\n\n```text\ncode = 15\n```",
            )
        for (span in listOf(
            HeadingSpan::class.java,
            BulletListItemSpan::class.java,
            StrongEmphasisSpan::class.java,
            CodeSpan::class.java,
            OrderedListItemSpan::class.java,
            BlockQuoteSpan::class.java,
            CodeBlockSpan::class.java,
        )) assertTrue(span.simpleName, rendered.getSpans(0, rendered.length, span).isNotEmpty())
        val visibleText = rendered.toString().replace('\u00a0', ' ').replace(Regex("\\s+"), " ")
        assertTrue(visibleText.contains("Audio and JSON"))
        for (marker in listOf("# Release", "**Audio**", "`JSON`", "```")) assertFalse(rendered.toString().contains(marker))
    }

    @Test fun rendersGithubTablesTasksAndStrikethrough() {
        val rendered =
            renderer().toMarkdown(
                "| Channel | State |\n| --- | --- |\n| Stable | Ready |\n\n- [x] Tested\n- [ ] Pending\n\n~~Old~~ New",
            )
        assertTrue(rendered.getSpans(0, rendered.length, TableRowSpan::class.java).isNotEmpty())
        assertEquals(2, rendered.getSpans(0, rendered.length, TaskListSpan::class.java).size)
        assertEquals(1, rendered.getSpans(0, rendered.length, StrikethroughSpan::class.java).size)
    }

    @Test fun linksAndImagesRemainReadableWithoutClickableActionsOrImageLoading() {
        val rendered =
            renderer().toMarkdown(
                "[Details](https://example.com) ![Image description](https://example.com/image.png) " +
                    "[Unsafe](intent://example) <https://example.com>",
            )
        assertTrue(rendered.toString().contains("Details"))
        assertTrue(rendered.toString().contains("Image description"))
        assertTrue(rendered.toString().contains("Unsafe"))
        assertEquals(0, rendered.getSpans(0, rendered.length, ClickableSpan::class.java).size)
        assertEquals(0, rendered.getSpans(0, rendered.length, AsyncDrawableSpan::class.java).size)
    }

    @Test fun nativeTextUsesComposeFontScaleThemeAndSixLinePreview() {
        val preview = mutableStateOf(true)
        var expectedColor = 0
        var expectedSize = 0f
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                Ts3MobileTheme(true) {
                    expectedColor = MaterialTheme.colorScheme.onSurface.toArgb()
                    expectedSize = with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.fontSize.toPx() }
                    Surface {
                        Column(Modifier.width(280.dp)) {
                            ReleaseNotesMarkdown("# Notes\n\n" + "**Long** release notes.\n\n".repeat(30), preview = preview.value)
                        }
                    }
                }
            }
        }
        rule.runOnIdle {
            val view = rule.activity.window.decorView.let { it as android.view.ViewGroup }.descendants.filterIsInstance<TextView>().single()
            assertEquals(expectedColor, view.currentTextColor)
            assertEquals(expectedSize, view.textSize, 0.1f)
            assertEquals(6, view.maxLines)
            assertTrue(view.text is Spanned)
            preview.value = false
        }
        rule.runOnIdle {
            val view = (rule.activity.window.decorView as android.view.ViewGroup).descendants.filterIsInstance<TextView>().single()
            assertEquals(Int.MAX_VALUE, view.maxLines)
            assertTrue(view.lineCount > 6)
        }
    }

    private fun renderer() = releaseNotesRenderer(rule.activity, Color.BLACK, Color.BLUE, Color.LTGRAY)
}

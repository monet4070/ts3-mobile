package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.chat.ChatRichTextParser
import io.github.ts3mobile.app.chat.ChatTextStyle

@Composable
internal fun ChatRichBody(
    body: String,
    truncated: Boolean,
    attachments: ChatAttachmentActions? = null,
) {
    val latestActions by rememberUpdatedState(attachments)
    val rich =
        remember(body, truncated, attachments?.uid, attachments?.channels) {
            if (truncated) null else ChatRichTextParser.parse(body, attachments?.let { it::accepts }).takeIf { it.spans.isNotEmpty() }
        }
    if (rich == null) {
        Text(body, style = MaterialTheme.typography.bodyLarge)
        return
    }
    val primary = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    var pendingUrl by remember(body) { mutableStateOf<String?>(null) }
    var openFailed by remember(body) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val annotated =
        remember(rich, primary, codeBackground) {
            buildAnnotatedString {
                append(rich.text)
                for (span in rich.spans) {
                    if (span.style == ChatTextStyle.LINK) {
                        val style = TextLinkStyles(SpanStyle(color = primary, textDecoration = TextDecoration.Underline))
                        val file = span.attachment
                        if (file != null) {
                            addLink(
                                LinkAnnotation.Clickable("channel-file", style) { latestActions?.open(file) },
                                span.start,
                                span.end,
                            )
                        } else {
                            val url = checkNotNull(span.url)
                            addLink(LinkAnnotation.Url(url, style) { pendingUrl = url }, span.start, span.end)
                        }
                    } else {
                        val style =
                            when (span.style) {
                                ChatTextStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                                ChatTextStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                                else -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                            }
                        addStyle(style, span.start, span.end)
                    }
                }
            }
        }
    Text(annotated, style = MaterialTheme.typography.bodyLarge)
    pendingUrl?.let { url ->
        InputDialog(
            onDismissRequest = { pendingUrl = null },
            title = { FeatureTitle(UiIcon.ExternalLink, stringResource(R.string.chat_open_link_title)) },
            text = {
                Panel(Modifier.fillMaxWidth()) {
                    Text(
                        url,
                        Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                        style =
                            MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                textDirection = TextDirection.Ltr,
                            ),
                    )
                }
            },
            confirmButton = {
                FeatureAction(
                    UiIcon.ExternalLink,
                    stringResource(R.string.chat_open_link),
                    onClick = {
                        pendingUrl = null
                        try {
                            uriHandler.openUri(url)
                        } catch (_: IllegalArgumentException) {
                            openFailed = true
                        }
                    },
                    style = FeatureActionStyle.PRIMARY,
                )
            },
            dismissButton = { FeatureAction(UiIcon.Close, stringResource(R.string.action_cancel), { pendingUrl = null }) },
        )
    }
    if (openFailed) {
        InputDialog(
            onDismissRequest = { openFailed = false },
            title = { FeatureTitle(UiIcon.ExternalLink, stringResource(R.string.chat_open_link_title)) },
            text = { FeatureNotice(UiIcon.Info, stringResource(R.string.chat_open_link_failed), error = true) },
            confirmButton = { FeatureAction(UiIcon.Close, stringResource(R.string.action_close), { openFailed = false }) },
        )
    }
}

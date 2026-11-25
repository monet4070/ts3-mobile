package io.github.ts3mobile.app.chat

import io.github.ts3mobile.protocol.ChannelAttachment
import java.net.URI
import java.net.URISyntaxException

internal data class ChatRichText(val text: String, val spans: List<ChatTextSpan> = emptyList())

internal enum class ChatTextStyle { BOLD, ITALIC, CODE, LINK }

internal data class ChatTextSpan(
    val start: Int,
    val end: Int,
    val style: ChatTextStyle,
    val url: String? = null,
    val attachment: ChannelAttachment? = null,
)

internal object ChatRichTextParser {
    private const val MAX_LENGTH = 4_096
    private const val MAX_SPANS = 64
    private const val MAX_DEPTH = 8
    private val tags = Regex("\\[(/?)(b|i|code|url)(?:=([^\\[\\]\\r\\n]*))?\\]", RegexOption.IGNORE_CASE)

    private data class OpenTag(val style: ChatTextStyle, val start: Int, val url: String?, val spanCount: Int)

    fun parse(
        body: String,
        matchesAttachment: ((ChannelAttachment) -> Boolean)? = null,
    ): ChatRichText {
        if (body.length <= MAX_LENGTH && matchesAttachment != null && body.startsWith("ts3file://", ignoreCase = true)) {
            ChannelAttachment.parse(body)?.takeIf(matchesAttachment)?.let { file ->
                return ChatRichText(file.fileName, listOf(ChatTextSpan(0, file.fileName.length, ChatTextStyle.LINK, attachment = file)))
            }
        }
        if (body.length > MAX_LENGTH || '[' !in body) return ChatRichText(body)
        return parseTags(body, matchesAttachment) ?: ChatRichText(body)
    }

    private fun parseTags(
        body: String,
        matchesAttachment: ((ChannelAttachment) -> Boolean)?,
    ): ChatRichText? {
        val output = StringBuilder(body.length)
        val spans = mutableListOf<ChatTextSpan>()
        val open = ArrayDeque<OpenTag>()
        var cursor = 0
        for (tag in tags.findAll(body)) {
            val style = styleOf(tag.groupValues[2])
            val closing = tag.groupValues[1].isNotEmpty()
            val argument = tag.groups[3]?.value
            if (open.lastOrNull()?.style == ChatTextStyle.CODE && !(closing && style == ChatTextStyle.CODE && argument == null)) continue
            output.append(body, cursor, tag.range.first)
            cursor = tag.range.last + 1
            if (closing) {
                val current = open.removeLastOrNull() ?: return null
                if (current.style != style || argument != null || current.start == output.length || spans.size == MAX_SPANS) return null
                val target = if (style == ChatTextStyle.LINK) current.url ?: output.substring(current.start) else null
                val url = target?.let(::safeHttpUrl)
                val attachment =
                    if (target != null && url == null && matchesAttachment != null) {
                        ChannelAttachment.parse(target)?.takeIf(matchesAttachment)
                    } else {
                        null
                    }
                if (style == ChatTextStyle.LINK && url == null && attachment == null) return null
                if (attachment != null && output.indexOf("\uFFFD", current.start) >= 0) {
                    spans.subList(current.spanCount, spans.size).clear()
                    output.replace(current.start, output.length, attachment.fileName)
                }
                spans.add(ChatTextSpan(current.start, output.length, style, url, attachment))
            } else {
                if (open.size == MAX_DEPTH || (argument != null && style != ChatTextStyle.LINK)) return null
                if (style == ChatTextStyle.LINK && open.any { it.style == ChatTextStyle.LINK }) return null
                open.addLast(OpenTag(style, output.length, argument, spans.size))
            }
        }
        if (open.isNotEmpty() || spans.isEmpty()) return null
        output.append(body, cursor, body.length)
        return ChatRichText(output.toString(), spans)
    }

    private fun styleOf(name: String): ChatTextStyle =
        when (name.lowercase()) {
            "b" -> ChatTextStyle.BOLD
            "i" -> ChatTextStyle.ITALIC
            "code" -> ChatTextStyle.CODE
            else -> ChatTextStyle.LINK
        }

    private fun safeHttpUrl(target: String): String? {
        if (target.any { it.isWhitespace() || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) return null
        val uri =
            try {
                URI(target)
            } catch (_: URISyntaxException) {
                return null
            }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host == null || uri.rawUserInfo != null || (uri.port != -1 && uri.port !in 1..65535)) return null
        return scheme + ":" + uri.toASCIIString().substringAfter(':')
    }
}

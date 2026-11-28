package io.github.ts3mobile.app.chat

import io.github.ts3mobile.protocol.VirtualServerUid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRichTextTest {
    @Test fun fileReferenceWithoutServerIdentityStaysPlainWithAttachmentsEnabled() {
        val reference = "ts3file://example.invalid?port=9987&channel=7&path=%2F&filename=sample.txt&isDir=0"
        listOf(reference, "[URL=$reference]sample.txt[/URL]", "[URL]$reference[/URL]").forEach { body ->
            assertEquals(ChatRichText(body), ChatRichTextParser.parse(body) { error("Unverifiable reference must not reach matching") })
        }
    }

    @Test fun attachmentsStayPlainUnlessTheLiveServerAndChannelMatch() {
        val uid = "AAECAwQFBgcICQoLDA0ODxAREhM="
        val target = "ts3file://example.invalid?port=9987&serverUID=$uid&channel=7&path=/&filename=real.txt&isDir=0"
        val current = VirtualServerUid.parse(uid)
        val body = "[b]File:[/b] [URL=$target]untrusted label[/URL]"
        assertEquals(ChatRichText(body), ChatRichTextParser.parse(body))
        assertEquals(ChatRichText(body), ChatRichTextParser.parse(body) { it.channelId == 8 })
        val rendered = ChatRichTextParser.parse(body) { it.channelId == 7 && it.serverUid == current }
        assertEquals("File: untrusted label", rendered.text)
        assertEquals("real.txt", rendered.spans.single { it.style == ChatTextStyle.LINK }.attachment?.fileName)
        assertEquals("real.txt", ChatRichTextParser.parse(target) { it.serverUid == current }.text)
        val code = "[code][URL=$target]not a link[/URL][/code]"
        assertTrue(ChatRichTextParser.parse(code) { true }.spans.none { it.attachment != null })
    }

    @Test fun verifiedAttachmentUsesExactFilenameWhenDesktopLabelIsCorrupted() {
        val uid = "AAECAwQFBgcICQoLDA0ODxAREhM="
        val target =
            "ts3file://example.invalid?port=9987&serverUID=$uid&channel=7&path=%2Ffixtures&filename=QA%20%E4%B8%AD%20A%2B100%25.txt&isDir=0"
        val damagedLabel = "QA 中 A+100\uFFFDxt"
        val original = "[b]File: [URL=$target][i]$damagedLabel[/i][/URL][/b] [i]after[/i]"
        val filename = "QA 中 A+100%.txt"
        assertEquals(ChatRichText(original), ChatRichTextParser.parse(original) { it.channelId != 7 })
        val rendered = ChatRichTextParser.parse(original) { it.serverUid == VirtualServerUid.parse(uid) && it.channelId == 7 }
        assertEquals("File: $filename after", rendered.text)
        assertEquals(
            listOf(ChatTextStyle.LINK to filename, ChatTextStyle.BOLD to "File: $filename", ChatTextStyle.ITALIC to "after"),
            rendered.spans.map { it.style to rendered.text.substring(it.start, it.end) },
        )
        assertEquals(filename, rendered.spans.single { it.style == ChatTextStyle.LINK }.attachment?.fileName)
        assertEquals(damagedLabel, ChatRichTextParser.parse("[URL=https://example.invalid]$damagedLabel[/URL]").text)
    }

    @Test fun buildsUtf16RangesForNestedStylesAndPreservesCodeLiterally() {
        val rich = ChatRichTextParser.parse("你好🙂 [B]粗[i]斜[/i][/b] [code][b]literal[/b][/CODE]")
        assertEquals("你好🙂 粗斜 [b]literal[/b]", rich.text)
        assertEquals(
            listOf(ChatTextStyle.ITALIC to "斜", ChatTextStyle.BOLD to "粗斜", ChatTextStyle.CODE to "[b]literal[/b]"),
            rich.spans.map { it.style to rich.text.substring(it.start, it.end) },
        )
    }

    @Test fun supportsBothExplicitLinkFormsAndUsesTheTargetNotTheLabel() {
        val rich = ChatRichTextParser.parse("[url=HTTPS://example.invalid/路径][i]文档[/i][/url] [url]http://example.invalid[/url]")
        assertEquals("文档 http://example.invalid", rich.text)
        assertEquals(
            listOf("https://example.invalid/%E8%B7%AF%E5%BE%84", "http://example.invalid"),
            rich.spans.filter { it.style == ChatTextStyle.LINK }.map { it.url },
        )
    }

    @Test fun unsupportedTagsAndInvalidTargetsDoNotBecomeClickable() {
        val inputs =
            listOf(
                "[img]https://example.invalid/a.png[/img]",
                "[url]javascript:alert(1)[/url]",
                "[url=intent://example.invalid]App[/url]",
                "[url]https://other.invalid@example.invalid[/url]",
                "[url]https:///bad[/url]",
                "[url]https://example.invalid:65536[/url]",
                "[url]https://example.invalid/a\u202Eb[/url]",
                "[url]https://example.invalid/a b[/url]",
                "[code][url]https://example.invalid[/url][/code]",
            )
        inputs.forEach { source -> assertTrue(ChatRichTextParser.parse(source).spans.none { it.url != null }) }
    }

    @Test fun unbalancedMarkupAndExceededBudgetsRemainUnchanged() {
        listOf(
            "[b][i]交叉[/b][/i]",
            "[b]missing closer",
            "[url=https://example.invalid]unfinished",
            "[b]".repeat(9) + "deep" + "[/b]".repeat(9),
            "[i]span[/i]".repeat(65),
            "[b]" + "x".repeat(4_096) + "[/b]",
        ).forEach { source -> assertEquals(ChatRichText(source), ChatRichTextParser.parse(source)) }
    }
}

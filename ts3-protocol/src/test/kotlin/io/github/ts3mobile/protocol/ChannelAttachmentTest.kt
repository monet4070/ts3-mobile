package io.github.ts3mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelAttachmentTest {
    @Test fun parsesDocumentedCopyUrlAndDecodesComponentsExactlyOnce() {
        val reference = "$PREFIX&path=%2Fdocs%20and%20notes%2F&filename=%E4%B8%AD%20A%2B%2520.txt&$SUFFIX"
        val parsed = checkNotNull(ChannelAttachment.parse(reference))
        assertEquals(VirtualServerUid.parse(UID), parsed.serverUid)
        assertEquals(7, parsed.channelId)
        assertEquals("/docs and notes", parsed.directory)
        assertEquals("中 A+%20.txt", parsed.fileName)
        assertEquals("/docs and notes/中 A+%20.txt", parsed.remotePath)

        val root = "$PREFIX&path=/&filename=a+b%26c.txt&$SUFFIX"
        assertEquals("/a+b&c.txt", ChannelAttachment.parse(root)?.remotePath)
        assertEquals("/a+b&c.txt", ChannelAttachment.parse(root.replace("channel=7", "cid=7"))?.remotePath)
        assertEquals("/a+b&c.txt", ChannelAttachment.parse(root.replace("example.invalid", "[::1]"))?.remotePath)
        assertEquals(parsed.serverUid, ChannelAttachment.parse(reference.replace("$UID&", "${UID.removeSuffix("=")}%3D&"))?.serverUid)
        assertNotEquals(VirtualServerUid.parse(UID), VirtualServerUid.parse("AAAAAAAAAAAAAAAAAAAAAAAAAAA="))
    }

    @Test fun rejectsAmbiguousOrUntrustedReferencesWithoutNormalizingUnsafePaths() {
        val reference = "$PREFIX&path=/&filename=fixture.txt&$SUFFIX"
        listOf(
            reference.replace("ts3file://", "https://"),
            reference.replace("example.invalid", "someone@example.invalid"),
            reference.replace("example.invalid", "example.invalid:8080"),
            reference.replace("example.invalid?", "example.invalid/path?"),
            reference.replace("channel=7", "channel=0"),
            reference.replace("channel=7", "channel=7&cid=8"),
            reference.replace("channel=7", "channel=Lobby"),
            reference.replace("isDir=0", "isDir=1"),
            reference.replace("filename=fixture.txt", "filename=%2E%2E"),
            reference.replace("filename=fixture.txt", "filename=sub%2Fchild"),
            reference.replace("filename=fixture.txt", "filename=bad%C0%AF.txt"),
            reference.replace("filename=fixture.txt", "filename=bad%0A.txt"),
            reference.replace("filename=fixture.txt", "filename=bad%E2%80%AE.txt"),
            reference.replace("path=/", "path=/../../"),
            reference.replace("path=/", "path=//"),
            reference.replace("port=9987", "port=65536"),
            reference.replace("serverUID=$UID&", ""),
            reference.replace("$UID&", "not-a-uid&"),
            "$reference&filename=other.txt",
            "$reference#fragment",
            "$reference&extra=${"a".repeat(4096)}",
        ).forEachIndexed { index, rejected -> assertNull("invalid case $index", ChannelAttachment.parse(rejected)) }
    }

    private companion object {
        const val UID = "AAECAwQFBgcICQoLDA0ODxAREhM="
        const val PREFIX = "ts3file://example.invalid?port=9987&serverUID=$UID&channel=7"
        const val SUFFIX = "isDir=0&size=32&fileDateTime=1716445847"
    }
}

package io.github.ts3mobile.app.bookmarks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BookmarkCodecTest {
    private val entry = ServerBookmark("fixture-1", name = "我的服务器", host = "voice.example.com", nickname = "Tester", defaultChannelId = 7)

    @Test fun independentVersionedRoundtripPreservesOrderAndOptionalFields() {
        val list =
            listOf(
                entry,
                entry.copy(id = "fixture-2", rememberPasswords = true, password = "fixture-secret", channelPassword = "channel-fixture"),
            )
        assertEquals(list, BookmarkCodec.decode(BookmarkCodec.encode(list)))
        assertEquals(emptyList<ServerBookmark>(), BookmarkCodec.decode(BookmarkCodec.encode(emptyList())))
        val unicodePassword = entry.copy(rememberPasswords = true, password = "😀".repeat(256))
        assertEquals(unicodePassword, BookmarkCodec.decode(BookmarkCodec.encode(listOf(unicodePassword))).single())
    }

    @Test fun optOutRemovesBothPasswordsFromEncodedContent() {
        val raw = entry.copy(password = "do-not-store", channelPassword = "do-not-store-channel")
        val encoded = BookmarkCodec.encode(listOf(raw))
        assertFalse(String(encoded).contains("do-not-store"))
        assertEquals(listOf(entry), BookmarkCodec.decode(encoded))
        val first = BookmarkCodec.save(emptyList(), raw.copy(rememberPasswords = true)).single()
        val cleared = BookmarkCodec.save(listOf(first), first.copy(rememberPasswords = false)).single()
        assertEquals("", BookmarkCodec.decode(BookmarkCodec.encode(listOf(cleared))).single().password)
        assertEquals("", cleared.channelPassword)
    }

    @Test fun staleEditAndDeleteCannotOverwriteNewerSavedEntryOrResurrectDeletedOne() {
        val first = BookmarkCodec.save(emptyList(), entry).single()
        val updated = BookmarkCodec.save(listOf(first), first.copy(name = "New name")).single()
        assertThrows(IllegalArgumentException::class.java) { BookmarkCodec.save(listOf(updated), first) }
        assertThrows(IllegalArgumentException::class.java) { BookmarkCodec.remove(listOf(updated), first) }
        assertThrows(IllegalArgumentException::class.java) { BookmarkCodec.save(BookmarkCodec.remove(listOf(updated), updated), updated) }
    }

    @Test fun rejectsCorruptionUnknownVersionTrailingBytesDuplicatesAndOverCapacity() {
        val bytes = BookmarkCodec.encode(listOf(entry))
        assertThrows(IllegalArgumentException::class.java) { BookmarkCodec.decode(bytes + byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { BookmarkCodec.decode(byteArrayOf(0, 0, 0, 2)) }
        assertThrows(Exception::class.java) { BookmarkCodec.decode(bytes.copyOf(10)) }
        assertThrows(IllegalArgumentException::class.java) { BookmarkCodec.encode(listOf(entry, entry)) }
        val full = (1..50).map { entry.copy(id = "item-$it", revision = 1) }
        assertThrows(IllegalArgumentException::class.java) { BookmarkCodec.save(full, entry) }
        assertEquals(50, BookmarkCodec.save(full, full.first().copy(name = "Updated")).size)
    }

    @Test fun validationAndRedactionCoverEndpointsPasswordsAndDefaultChannel() {
        assertFalse(entry.copy(password = "secret").toString().contains("secret"))
        assertFalse(entry.toString().contains(entry.host))
        assertThrows(IllegalArgumentException::class.java) { entry.copy(port = 0).validate() }
        assertThrows(IllegalArgumentException::class.java) { entry.copy(defaultChannelId = -1).validate() }
        assertThrows(IllegalArgumentException::class.java) { entry.copy(name = "").validate() }
        assertEquals(7, entry.toConfig().defaultChannelId)
    }
}

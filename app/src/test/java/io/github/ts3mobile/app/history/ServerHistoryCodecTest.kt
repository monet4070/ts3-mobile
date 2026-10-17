package io.github.ts3mobile.app.history

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerHistoryCodecTest {
    @Test
    fun roundTripsDomainsAndIpv6Hosts() {
        val entries =
            listOf(
                ServerHistoryEntry("voice.example.com", 9987),
                ServerHistoryEntry("2001:db8::1", 9987),
                ServerHistoryEntry("fe80::1%wlan0", 10011),
            )

        assertEquals(entries, ServerHistoryCodec.decode(ServerHistoryCodec.encode(entries)))
    }

    @Test
    fun decodeOfNullOrEmptyIsEmpty() {
        assertEquals(emptyList<ServerHistoryEntry>(), ServerHistoryCodec.decode(null))
        assertEquals(emptyList<ServerHistoryEntry>(), ServerHistoryCodec.decode(""))
    }

    @Test
    fun decodeSkipsCorruptLines() {
        val payload =
            listOf(
                "voice.example.com\t9987",
                "missing-port",
                "\t9987",
                "voice2.example.com\tnot-a-number",
                "voice3.example.com\t70000",
                "voice4.example.com\t9987\textra",
                "",
            ).joinToString("\n")

        assertEquals(
            listOf(ServerHistoryEntry("voice.example.com", 9987)),
            ServerHistoryCodec.decode(payload),
        )
    }

    @Test
    fun decodeToleratesWindowsLineEndings() {
        val payload = "voice.example.com\t9987\r\nvoice2.example.com\t10011\r\n"

        assertEquals(
            listOf(
                ServerHistoryEntry("voice.example.com", 9987),
                ServerHistoryEntry("voice2.example.com", 10011),
            ),
            ServerHistoryCodec.decode(payload),
        )
    }

    @Test
    fun decodeDeduplicatesAndCapsTheList() {
        val payload =
            (0 until ServerHistoryPolicy.MAX_ENTRIES + 5)
                .joinToString("\n") { "host$it.example.com\t9987" } +
                "\nvoice.example.com\t9987\nVOICE.example.com\t9987"

        assertEquals(ServerHistoryPolicy.MAX_ENTRIES, ServerHistoryCodec.decode(payload).size)
    }

    @Test
    fun encodeSkipsInvalidEntries() {
        val encoded =
            ServerHistoryCodec.encode(
                listOf(
                    ServerHistoryEntry("", 9987),
                    ServerHistoryEntry("voice.example.com", 9987),
                ),
            )

        assertEquals(listOf(ServerHistoryEntry("voice.example.com", 9987)), ServerHistoryCodec.decode(encoded))
    }
}

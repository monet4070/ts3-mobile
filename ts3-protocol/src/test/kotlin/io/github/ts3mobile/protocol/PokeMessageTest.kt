package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.event.ClientPokeEvent
import com.github.manevolent.ts3j.event.TS3Event
import com.github.manevolent.ts3j.protocol.ProtocolRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PokeMessageTest {
    @Test fun mapsPlainReasonAndIdentityWithoutGuessingOrReescaping() {
        val event =
            mapOf(
                "invokerid" to "8",
                "invokeruid" to "exact-uid",
                "invokername" to "Fixture",
                "msg" to " 中文😀 | \\ ",
            ).toPokeEvent()
        assertEquals(8, event.invokerId)
        assertEquals("exact-uid", event.invokerUid)
        assertEquals(" 中文😀 | \\ ", event.body)
        assertEquals(TextEventIssue.NONE, event.issue)
        assertFalse(event.toString().contains("Fixture"))
        assertFalse(event.toString().contains("exact-uid"))
        assertFalse(event.toString().contains("中文"))
    }

    @Test fun emptyAndOmittedReasonsAreValidWhileInvalidIdentityRemainsExplicit() {
        assertEquals(TextEventIssue.NONE, mapOf("msg" to "").toPokeEvent().issue)
        val absent = mapOf("invokerid" to "0", "invokeruid" to "u".repeat(257)).toPokeEvent()
        assertEquals(TextEventIssue.NONE, absent.issue)
        assertNull(absent.invokerId)
        assertNull(absent.invokerUid)
    }

    @Test fun realNotifyCommandWithoutReasonReachesThePokeMapper() {
        val command =
            SingleCommand.parse(
                ProtocolRole.SERVER,
                "notifyclientpoke invokerid=8 invokeruid=fixture-uid invokername=Fixture",
            )
        val event = TS3Event.createEvent(command) as ClientPokeEvent
        val mapped = event.map.toPokeEvent()
        assertEquals(8, mapped.invokerId)
        assertEquals("fixture-uid", mapped.invokerUid)
        assertEquals("", mapped.body)
        assertEquals(TextEventIssue.NONE, mapped.issue)
    }

    @Test fun enormousReasonIsBoundedOnACodePointBoundary() {
        val event = mapOf("msg" to "中😀".repeat(10_000), "invokername" to "名".repeat(1_000)).toPokeEvent()
        assertEquals(TextEventIssue.TRUNCATED, event.issue)
        assertTrue(event.body.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_EVENT_BYTES)
        assertTrue(event.invokerName!!.toByteArray(Charsets.UTF_8).size <= 256)
        assertFalse(event.body.last().isHighSurrogate())
    }
}

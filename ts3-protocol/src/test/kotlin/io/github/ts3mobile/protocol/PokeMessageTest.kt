package io.github.ts3mobile.protocol

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

    @Test fun emptyReasonIsValidButMissingBodyAndInvalidIdentityRemainExplicit() {
        assertEquals(TextEventIssue.NONE, mapOf("msg" to "").toPokeEvent().issue)
        val absent = mapOf("invokerid" to "0", "invokeruid" to "u".repeat(257)).toPokeEvent()
        assertEquals(TextEventIssue.MISSING_BODY, absent.issue)
        assertNull(absent.invokerId)
        assertNull(absent.invokerUid)
    }

    @Test fun enormousReasonIsBoundedOnACodePointBoundary() {
        val event = mapOf("msg" to "中😀".repeat(10_000), "invokername" to "名".repeat(1_000)).toPokeEvent()
        assertEquals(TextEventIssue.TRUNCATED, event.issue)
        assertTrue(event.body.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_EVENT_BYTES)
        assertTrue(event.invokerName!!.toByteArray(Charsets.UTF_8).size <= 256)
        assertFalse(event.body.last().isHighSurrogate())
    }
}

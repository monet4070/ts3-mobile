package io.github.ts3mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ts3jTextMessageMapperTest {
    @Test fun absentAndInvalidFieldsRemainExplicit() {
        val event = mapOf("targetmode" to "2", "target" to "bad").toTextEvent()
        assertEquals(TextEventIssue.MISSING_BODY, event.issue)
        assertNull(event.targetId)
        assertNull(event.invokerId)
        assertEquals(TextTargetMode.UNKNOWN, mapOf("msg" to "hello", "targetmode" to "7").toTextEvent().mode)
    }

    @Test fun mapsModesIdentityAndUnescapedBodyWithoutGuessingChannel() {
        val event =
            mapOf(
                "targetmode" to "2",
                "msg" to " 中文 | \\ ",
                "invokerid" to "7",
                "invokeruid" to "fixture-uid",
                "invokername" to "Fixture",
            ).toTextEvent()
        assertEquals(" 中文 | \\ ", event.body)
        assertEquals(7, event.invokerId)
        assertEquals("fixture-uid", event.invokerUid)
        assertNull(event.targetId)
        assertEquals(TextTargetMode.PRIVATE, mapOf("targetmode" to "1").toTextEvent().mode)
        assertEquals(TextTargetMode.SERVER, mapOf("targetmode" to "3").toTextEvent().mode)
    }

    @Test fun enormousUnicodeEventIsBoundedAndRedacted() {
        val event = mapOf("targetmode" to "2", "msg" to "😀".repeat(20_000), "invokername" to "private-name").toTextEvent()
        assertEquals(TextEventIssue.TRUNCATED, event.issue)
        assertTrue(event.body.toByteArray().size <= MAX_TEXT_EVENT_BYTES)
        assertFalse(event.body.endsWith("\uD83D"))
        assertFalse(event.toString().contains("private-name"))
        assertFalse(event.toString().contains("😀"))
    }
}

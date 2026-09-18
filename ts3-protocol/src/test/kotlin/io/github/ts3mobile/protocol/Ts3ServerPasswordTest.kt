package io.github.ts3mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ts3ServerPasswordTest {
    @Test
    fun hashesNormalPasswordOnceUsingTeamSpeakRepresentation() {
        assertEquals(
            "W6ph5Mm5Pz8GgiULbPgzG37mj9g=",
            "password".toTs3jServerPassword(),
        )
    }

    @Test
    fun doesNotTurnEmptyOrBlankPasswordIntoAProtectedConnection() {
        assertNull("".toTs3jServerPassword())
        assertNull("   ".toTs3jServerPassword())
    }

    @Test
    fun hashesNonAsciiPasswordAsUtf8() {
        assertEquals(
            "9Rfd8dMqES/xrVXGbRsSyzjn6Pc=",
            "pässwörd".toTs3jServerPassword(),
        )
    }
}

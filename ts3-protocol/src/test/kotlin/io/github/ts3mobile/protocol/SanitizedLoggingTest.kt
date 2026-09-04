package io.github.ts3mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SanitizedLoggingTest {
    @Test
    fun exposesFailureTypesWithoutExceptionMessages() {
        val error =
            IllegalStateException(
                "voice.example.com:9987",
                IllegalArgumentException("password=secret"),
            )

        val summary = error.sanitizedFailureTypes()

        assertEquals("IllegalStateException <- IllegalArgumentException", summary)
        assertFalse(summary.contains("voice.example.com"))
        assertFalse(summary.contains("secret"))
    }
}

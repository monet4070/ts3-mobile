package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.CommandException
import com.github.manevolent.ts3j.command.CommandProcessException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

class ConnectionFailureTest {
    @Test
    fun terminalOuterCommandFailureTakesPrecedenceOverANestedCloneRefusal() {
        val terminal =
            CommandException("invalid password", 520).apply {
                initCause(CommandProcessException("too many clones already connected"))
            }
        assertFalse(terminal.isRetryableConnectionFailure())
        assertEquals(ConnectionFailureCategory.UNCLASSIFIED, terminal.connectionFailureCategory())
    }

    @Test
    fun networkFailuresAreRetryableThroughWrappedCauses() {
        assertTrue(IOException("offline").isRetryableConnectionFailure())
        assertTrue(SocketException("reset").isRetryableConnectionFailure())
        assertTrue(UnknownHostException("host").isRetryableConnectionFailure())
        assertTrue(IllegalStateException(TimeoutException("slow")).isRetryableConnectionFailure())
        assertTrue(RuntimeException(InterruptedException("handshake interrupted")).isRetryableConnectionFailure())
    }

    @Test
    fun protocolAndConfigurationFailuresAreTerminal() {
        assertFalse(CommandException("invalid password", 520).isRetryableConnectionFailure())
        assertFalse(CommandProcessException("nickname is already in use").isRetryableConnectionFailure())
        assertFalse(IllegalArgumentException("bad port").isRetryableConnectionFailure())
    }

    @Test
    fun cloneRejectionRequiresManualRetryEvenAfterAConnectionDrop() {
        assertFalse(
            CommandProcessException("too many clones already connected")
                .isRetryableConnectionFailure(),
        )
    }

    @Test
    fun theObservedCloneRefusalIsClassifiedThroughWrappedAndNestedCauses() {
        assertEquals(
            ConnectionFailureCategory.CLONES_LIMIT,
            CommandProcessException("too many clones already connected").connectionFailureCategory(),
        )
        assertEquals(
            ConnectionFailureCategory.CLONES_LIMIT,
            IllegalStateException("connect failed", CommandProcessException("TOO MANY CLONES ALREADY CONNECTED"))
                .connectionFailureCategory(),
        )
        assertEquals(
            ConnectionFailureCategory.CLONES_LIMIT,
            RuntimeException(
                IOException("transport", CommandProcessException("Too Many Clones Already Connected")),
            ).connectionFailureCategory(),
        )
    }

    @Test
    fun onlyTheObservedRefusalTypeIsClassifiedAsACloneLimit() {
        // The same text from another type is not the same fact, and no structured
        // error code is invented for it.
        assertEquals(
            ConnectionFailureCategory.UNCLASSIFIED,
            CommandException("too many clones already connected", 520).connectionFailureCategory(),
        )
        assertEquals(
            ConnectionFailureCategory.UNCLASSIFIED,
            IOException("too many clones already connected").connectionFailureCategory(),
        )
        assertEquals(
            ConnectionFailureCategory.UNCLASSIFIED,
            CommandProcessException("nickname is already in use").connectionFailureCategory(),
        )
        assertEquals(
            ConnectionFailureCategory.UNCLASSIFIED,
            TimeoutException("handshake timed out").connectionFailureCategory(),
        )
    }

    @Test
    fun aTerminalCommandErrorIsNeverClassifiedAsACloneRefusal() {
        val terminal = CommandException("too many clones already connected", 520)

        assertFalse(terminal.isRetryableConnectionFailure())
        assertEquals(ConnectionFailureCategory.UNCLASSIFIED, terminal.connectionFailureCategory())
    }

    @Test
    fun aCyclicCauseChainIsClassifiedWithoutLooping() {
        val first = CommandProcessException("connect failed")
        val second = IllegalStateException("wrapped", first)
        first.initCause(second)

        assertEquals(ConnectionFailureCategory.UNCLASSIFIED, first.connectionFailureCategory())
        assertFalse(first.isRetryableConnectionFailure())
    }

    @Test
    fun aStatusWithoutACategoryStaysSourceCompatible() {
        assertEquals(ConnectionFailureCategory.UNCLASSIFIED, ConnectionStatus().category)
        assertEquals(
            ConnectionFailureCategory.CLONES_LIMIT,
            ConnectionStatus(category = ConnectionFailureCategory.CLONES_LIMIT).category,
        )
    }
}

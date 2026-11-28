package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.protocol.Packet
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.client.ClientConnectionState
import com.github.manevolent.ts3j.protocol.packet.PacketBody2Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

class Ts3jHandshakeRejectionTest {
    @Test
    fun serverRefusalSurvivesSocketTeardownBeforeTheExceptionCallback() {
        assertHandshakeRejected(
            "error id=1234 msg=fixture\\srefusal",
            "fixture refusal",
            ConnectionFailureCategory.UNCLASSIFIED,
            errorId = 1234,
        )
    }

    @Test
    fun interruptedHandshakeKeepsTheExistingIdentityLimitGuidance() {
        assertHandshakeRejected(
            "error id=521 msg=fixture\\sidentity\\srefusal",
            "fixture identity refusal",
            ConnectionFailureCategory.CLONES_LIMIT,
            errorId = 521,
            deliverCallback = true,
        )
    }

    @Test
    fun aDifferentServerCodeDoesNotAuthorizeCloneLimitRecovery() {
        assertHandshakeRejected(
            "error id=520 msg=too\\smany\\sclones\\salready\\sconnected",
            "too many clones already connected",
            ConnectionFailureCategory.UNCLASSIFIED,
            errorId = 520,
        )
    }

    @Test
    fun missingErrorCodeDoesNotTurnARefusalIntoARetryableFailure() {
        assertHandshakeRejected(
            "error msg=fixture\\srefusal",
            "fixture refusal",
            ConnectionFailureCategory.UNCLASSIFIED,
            errorId = null,
        )
    }

    private fun assertHandshakeRejected(
        reply: String,
        message: String,
        category: ConnectionFailureCategory,
        errorId: Int?,
        deliverCallback: Boolean = false,
    ) {
        val worker = Executors.newSingleThreadExecutor()
        val loopback = fixtureIpv4Loopback
        val socket = WaitingHandshakeSocket()
        val listener = RecordingListener()
        try {
            DatagramSocket(0, loopback).use { peer ->
                Ts3jSessionClient(Ts3jClientSocketFactory { LocalTeamspeakClientSocketAdapter(socket) }).use { client ->
                    val result =
                        worker.submit<Throwable> {
                            val config = ServerConfig(host = loopback.hostAddress, port = peer.localPort, nickname = "Fixture")
                            assertThrows(Exception::class.java) { client.connect(config, Ts3IdentityCodec.generate(), listener) }
                        }
                    assertTrue(socket.waitingForConnect.await(5, SECONDS))
                    socket.setState(ClientConnectionState.RETRIEVING_DATA)
                    val packet = Packet(ProtocolRole.SERVER).apply { body = PacketBody2Command(ProtocolRole.SERVER, reply) }
                    val packetFailure = assertThrows(IOException::class.java) { socket.handler.handlePacket(packet) }
                    if (deliverCallback) socket.exceptionHandler.accept(packetFailure)

                    val error = result.get(5, SECONDS)
                    assertEquals(message, error.message)
                    assertFalse(error.isRetryableConnectionFailure())
                    assertEquals(category, error.connectionFailureCategory())
                    assertEquals(errorId, (error as Ts3jHandshakeRejectedException).serverErrorId)
                    assertEquals(
                        listOf(ConnectionStatus(ConnectionPhase.ERROR, message, retryable = false, category = category)),
                        listener.statuses.filter { it.phase != ConnectionPhase.CONNECTING },
                    )
                }
            }
        } finally {
            worker.shutdownNow()
            socket.close()
        }
    }

    private class WaitingHandshakeSocket : StateAwareTeamspeakClientSocket() {
        val waitingForConnect = CountDownLatch(1)

        override fun waitForState(
            state: ClientConnectionState,
            wait: Long,
        ) {
            if (state == ClientConnectionState.CONNECTED) waitingForConnect.countDown()
            super.waitForState(state, wait)
        }
    }

    private class RecordingListener : Ts3SessionListener {
        val statuses = mutableListOf<ConnectionStatus>()

        override fun onStatusChanged(status: ConnectionStatus) {
            statuses += status
        }

        override fun onSnapshotChanged(snapshot: SessionSnapshot) = Unit

        override fun onVoiceFrame(frame: VoiceFrame) = Unit
    }
}

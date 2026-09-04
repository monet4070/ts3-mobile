package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.audio.Microphone
import com.github.manevolent.ts3j.event.ClientJoinEvent
import com.github.manevolent.ts3j.event.DisconnectedEvent
import com.github.manevolent.ts3j.event.TS3Listener
import com.github.manevolent.ts3j.identity.LocalIdentity
import com.github.manevolent.ts3j.protocol.PacketKind
import com.github.manevolent.ts3j.protocol.packet.PacketBody0Voice
import com.github.manevolent.ts3j.protocol.packet.PacketBody1VoiceWhisper
import com.github.manevolent.ts3j.protocol.packet.statistics.PacketStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference

class Ts3jSessionClientTest {
    @Test
    fun aStaleJoinCannotMutateTheReplacementSessionSnapshot() {
        val joinEntered = CountDownLatch(1)
        val releaseJoin = CountDownLatch(1)
        val firstSocket = FakeSocket(joinEntered = joinEntered, releaseJoin = releaseJoin)
        val secondSocket = FakeSocket()
        val sockets = ConcurrentLinkedQueue(listOf(firstSocket, secondSocket))
        val firstListener = RecordingListener()
        val secondListener = RecordingListener()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { sockets.remove() })
        val identity = Ts3IdentityCodec.generate()

        client.connect(testConfig(), identity, firstListener)
        firstSocket.fireClientJoin(clientId = 7, channelId = 10, nickname = "Alice")

        val joinFailure = AtomicReference<Throwable?>()
        val joinThread =
            Thread {
                try {
                    client.joinChannel(20)
                } catch (error: Throwable) {
                    joinFailure.set(error)
                }
            }
        joinThread.start()
        assertTrue(joinEntered.await(1, SECONDS))

        client.connect(testConfig(), identity, secondListener)
        secondSocket.fireClientJoin(clientId = 7, channelId = 30, nickname = "Bob")
        releaseJoin.countDown()
        joinThread.join(1_000)

        assertFalse(joinThread.isAlive)
        assertNull(joinFailure.get())
        assertEquals(20, firstSocket.joinedChannel)
        assertTrue(secondListener.snapshots.isNotEmpty())
        assertEquals(30, secondListener.snapshots.last().participants.single().channelId)
        assertTrue(secondListener.snapshots.last().channels.isEmpty())
    }

    @Test
    fun aStaleDisconnectCallbackCannotClearTheReplacementSession() {
        val firstSocket = FakeSocket()
        val secondSocket = FakeSocket()
        val sockets = ConcurrentLinkedQueue(listOf(firstSocket, secondSocket))
        val firstListener = RecordingListener()
        val secondListener = RecordingListener()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { sockets.remove() })
        val identity = Ts3IdentityCodec.generate()

        client.connect(testConfig(), identity, firstListener)
        client.connect(testConfig(), identity, secondListener)
        firstSocket.fireDisconnected(reasonId = 4)

        assertTrue(secondSocket.isConnected)
        assertTrue(secondListener.statuses.none { it.phase == ConnectionPhase.DISCONNECTED })
        assertTrue(secondListener.snapshots.last().participants.isEmpty())
    }

    @Test
    fun failureDuringConnectedDeliveryIsReportedAfterConnected() {
        val socket = FakeSocket()
        val listener =
            RecordingListener { status ->
                if (status.phase == ConnectionPhase.CONNECTED) {
                    socket.emitException(IllegalStateException("background failure"))
                }
            }
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })

        client.connect(testConfig(), Ts3IdentityCodec.generate(), listener)

        assertEquals(
            listOf(ConnectionPhase.CONNECTING, ConnectionPhase.CONNECTED, ConnectionPhase.ERROR),
            listener.statuses.map(ConnectionStatus::phase),
        )
    }

    @Test
    fun failureBeforeConnectedDeliveryDoesNotPublishConnected() {
        val failure = IllegalStateException("handshake failed")
        val socket = FakeSocket(failureOnConnect = failure)
        val listener = RecordingListener()
        val client = Ts3jSessionClient(Ts3jClientSocketFactory { socket })

        var thrown: Throwable? = null
        try {
            client.connect(testConfig(), Ts3IdentityCodec.generate(), listener)
        } catch (error: Throwable) {
            thrown = error
        }

        assertNotNull(thrown)
        assertEquals(failure, thrown)
        assertTrue(listener.statuses.none { it.phase == ConnectionPhase.CONNECTED })
        assertEquals(1, listener.statuses.count { it.phase == ConnectionPhase.ERROR })
    }

    private fun testConfig() =
        ServerConfig(
            host = "localhost",
            nickname = "Test User",
        )

    private class RecordingListener(
        private val onStatus: ((ConnectionStatus) -> Unit)? = null,
    ) : Ts3SessionListener {
        val statuses = mutableListOf<ConnectionStatus>()
        val snapshots = mutableListOf<SessionSnapshot>()

        override fun onStatusChanged(status: ConnectionStatus) {
            statuses += status
            onStatus?.invoke(status)
        }

        override fun onSnapshotChanged(snapshot: SessionSnapshot) {
            snapshots += snapshot
        }

        override fun onVoiceFrame(frame: VoiceFrame) = Unit
    }

    private class FakeSocket(
        private val joinEntered: CountDownLatch? = null,
        private val releaseJoin: CountDownLatch? = null,
        private val failureOnConnect: Throwable? = null,
    ) : Ts3jClientSocket {
        private var exceptionHandler: ((Throwable) -> Unit)? = null
        private var connected = false
        private var eventListener: TS3Listener? = null
        override val clientId: Int = 7
        var joinedChannel: Int? = null
            private set

        override fun setIdentity(identity: LocalIdentity) = Unit

        override fun setNickname(nickname: String) = Unit

        override fun setHWID(hwid: String) = Unit

        override fun setMicrophone(microphone: Microphone?) = Unit

        override fun setExceptionHandler(handler: (Throwable) -> Unit) {
            exceptionHandler = handler
        }

        override fun setVoiceHandler(handler: (PacketBody0Voice) -> Unit) = Unit

        override fun setWhisperHandler(handler: (PacketBody1VoiceWhisper) -> Unit) = Unit

        override fun addListener(listener: TS3Listener) {
            eventListener = listener
        }

        override fun connect(
            address: InetSocketAddress,
            password: String?,
            timeout: Long,
        ) {
            connected = true
            failureOnConnect?.let { exceptionHandler?.invoke(it) }
        }

        override fun subscribeAll() = Unit

        override val isConnected: Boolean
            get() = connected

        override fun joinChannel(
            channelId: Int,
            password: String,
        ) {
            joinEntered?.countDown()
            releaseJoin?.await(2, SECONDS)
            joinedChannel = channelId
        }

        override fun disconnect(reason: String) {
            connected = false
        }

        override fun close() {
            connected = false
        }

        override fun getStatistics(packetKind: PacketKind): PacketStatistics = PacketStatistics()

        fun emitException(error: Throwable) {
            exceptionHandler?.invoke(error)
        }

        fun fireClientJoin(
            clientId: Int,
            channelId: Int,
            nickname: String,
        ) {
            eventListener?.onClientJoin(
                ClientJoinEvent(
                    mapOf(
                        "clid" to clientId.toString(),
                        "ctid" to channelId.toString(),
                        "client_type" to "0",
                        "client_nickname" to nickname,
                        "client_unique_identifier" to "test-user",
                        "client_input_muted" to "0",
                        "client_output_muted" to "0",
                        "client_flag_talking" to "0",
                    ),
                ),
            )
        }

        fun fireDisconnected(reasonId: Int) {
            eventListener?.onDisconnected(
                DisconnectedEvent(mapOf("reasonid" to reasonId.toString())),
            )
        }
    }
}

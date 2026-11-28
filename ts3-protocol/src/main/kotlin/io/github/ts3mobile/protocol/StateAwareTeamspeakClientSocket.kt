package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.command.parameter.CommandSingleParameter
import com.github.manevolent.ts3j.protocol.NetworkPacket
import com.github.manevolent.ts3j.protocol.Packet
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.client.ClientConnectionState
import com.github.manevolent.ts3j.protocol.packet.PacketBody0Voice
import com.github.manevolent.ts3j.protocol.packet.PacketBody2Command
import com.github.manevolent.ts3j.protocol.packet.PacketBodyType
import com.github.manevolent.ts3j.protocol.packet.handler.PacketHandler
import com.github.manevolent.ts3j.protocol.socket.client.AbstractTeamspeakClientSocket
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default delegate that mirrors the socket's own connection state behind a
 * one-way latch.
 *
 * ts3j exposes its connection state as a plain field and its idle watchdog can
 * flip that field without delivering a listener callback or an exception, so a
 * caller reading it from another thread has no visibility guarantee. The latch is
 * written after the library call returns, which publishes the confirmed
 * disconnect to every later reader. A socket instance is created per connection
 * attempt, so the latch never has to be cleared.
 *
 * The library's own isConnected is deliberately not overridden so internal ts3j
 * flows keep their original behavior.
 */
internal open class StateAwareTeamspeakClientSocket :
    LocalTeamspeakClientSocket(),
    BoundedDisconnectTarget {
    @Volatile
    private var attemptStarted = false

    @Volatile
    private var closed = false

    @Volatile
    private var disconnectNotificationPending = false

    @Volatile
    var handshakeRejection: Ts3jHandshakeRejectedException? = null
        private set

    /** Counted down once this socket observes its own leave notification. */
    private val selfLeaveNotification = CountDownLatch(1)
    private val operations = SocketOperations()
    private val resourcesClosed = AtomicBoolean(false)

    @Volatile
    private var exitSender: Thread? = null

    @Volatile
    private var requestSent: (() -> Unit)? = null

    @Volatile var voiceTiming: Ts3jVoiceTiming? = null
    val channelFileReplies = Ts3jChannelFileReplies()

    @Volatile var serverIcon: ServerImage.Icon? = null
        private set

    @Volatile var serverUid: VirtualServerUid? = null
        private set

    @Volatile var closeChannelFiles: (() -> Unit)? = null

    fun <T> withOperation(block: () -> T): T = operations.run(block)

    fun recordHandshakeRejection(error: Ts3jHandshakeRejectedException) {
        handshakeRejection = error
    }

    override fun getHandlerClass(state: ClientConnectionState): Class<out PacketHandler> =
        if (state == ClientConnectionState.RETRIEVING_DATA) Ts3jHandshakeReplyHandler::class.java else super.getHandlerClass(state)

    override fun writePacket(packet: Packet) {
        val voice = packet.body as? PacketBody0Voice
        if (voice != null && voice.codecData?.isNotEmpty() != true) voiceTiming?.clearOffered()
        try {
            operations.run { super.writePacket(packet) }
        } finally {
            if (voice != null) voiceTiming?.clearOffered()
        }
    }

    final override fun writeNetworkPacket(packet: NetworkPacket) {
        writeDatagram(packet)
        if (packet.header.type == PacketBodyType.VOICE) voiceTiming?.successfulSend(packet)
        if (Thread.currentThread() === exitSender && packet.header.type == PacketBodyType.COMMAND) {
            requestSent?.also { requestSent = null }?.invoke()
        }
    }

    /** Tests substitute only datagram I/O; encoding, command locking and ACK waits remain real. */
    protected open fun writeDatagram(packet: NetworkPacket) = super.writeNetworkPacket(packet)

    override fun close() {
        if (!resourcesClosed.compareAndSet(false, true)) return
        voiceTiming?.close()
        closed = true
        closeChannelFiles?.invoke()
        operations.stop()
        try {
            super.close()
        } finally {
            setState(ClientConnectionState.DISCONNECTED)
        }
    }

    /** Volatile-backed transport state for callers outside ts3j. */
    val transportConnected: Boolean
        get() = !closed && super<LocalTeamspeakClientSocket>.isConnected

    /** Latched once a server-issued disconnect for this client is accepted. */
    val isDisconnectNotificationPending: Boolean
        get() = disconnectNotificationPending

    override fun setState(state: ClientConnectionState) {
        if (state == ClientConnectionState.CONNECTING) attemptStarted = true
        if (state == ClientConnectionState.DISCONNECTED && attemptStarted) operations.stop()
        try {
            super.setState(state)
        } finally {
            // The library sets DISCONNECTED from its own constructor, before an
            // attempt exists; only a disconnect after CONNECTING is a lost session.
            if (state == ClientConnectionState.DISCONNECTED && attemptStarted) {
                closed = true
                closeChannelFiles?.invoke()
            }
        }
    }

    override fun process(
        client: AbstractTeamspeakClientSocket,
        command: SingleCommand,
    ) {
        if (client === this && command.role == ProtocolRole.SERVER && command.name in ICON_NOTIFICATIONS) {
            command.parameters.firstOrNull { it.name == "virtualserver_icon_id" }?.let { serverIcon = ServerImage.icon(it.value) }
            if (command.name == "initserver") {
                serverUid =
                    command.parameters.firstOrNull { it.name == "virtualserver_unique_identifier" }
                        ?.value?.let(VirtualServerUid::parse)
            }
        }
        if (client === this && command.role == ProtocolRole.SERVER && command.name in FILE_NOTIFICATIONS &&
            channelFileReplies.accept(command.name, command.parameters.associate { it.name to it.value })
        ) {
            return
        }
        if (client === this && command.role == ProtocolRole.SERVER && command.isSelfDisconnectNotification()) {
            disconnectNotificationPending = true
            selfLeaveNotification.countDown()
        }
        super.process(client, command)
    }

    override val isSessionOpen: Boolean
        get() = transportConnected

    /**
     * Writes one clientdisconnect with the library's own command path, so the
     * command is encoded, encrypted, sequenced and acknowledged exactly like
     * every other reliable command. The library's `disconnect(reason)` is not
     * used: it waits for connection states for up to 40 seconds in total.
     */
    override fun sendClientDisconnect(
        reason: String,
        onRequestSent: () -> Unit,
    ) {
        exitSender = Thread.currentThread()
        requestSent = onRequestSent
        try {
            writePacket(
                PacketBody2Command(
                    ProtocolRole.CLIENT,
                    SingleCommand(
                        CLIENT_DISCONNECT_COMMAND,
                        ProtocolRole.CLIENT,
                        CommandSingleParameter(REASON_ID_PARAMETER, SELF_DISCONNECT_REASON_ID),
                        CommandSingleParameter(REASON_MESSAGE_PARAMETER, reason),
                    ),
                ),
            )
        } finally {
            exitSender = null
            requestSent = null
        }
    }

    /**
     * Waits for this socket's own leave notification. A local close, a watchdog
     * transition or another client's leave never satisfies this wait.
     */
    override fun awaitSelfLeaveNotification(timeoutMs: Long): Boolean =
        selfLeaveNotification.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)

    override fun closeTransport() {
        // Also applies to delegates whose close() substitutes only datagram I/O.
        operations.stop()
        close()
    }

    override fun markSessionDisconnected() {
        setState(ClientConnectionState.DISCONNECTED)
    }

    override fun awaitOperationsStopped(timeoutMs: Long): Boolean = operations.awaitStopped(timeoutMs)

    private fun SingleCommand.isSelfDisconnectNotification(): Boolean {
        if (name != SELF_DISCONNECT_COMMAND) return false
        if (!has(CLIENT_ID_PARAMETER)) return false
        return get(CLIENT_ID_PARAMETER)?.value?.toIntOrNull() == clientId
    }

    private companion object {
        val FILE_NOTIFICATIONS = setOf("notifyfilelist", "notifyfilelistfinished", "notifystartdownload", "notifystatusfiletransfer")
        val ICON_NOTIFICATIONS = setOf("initserver", "notifyserveredited")
        const val SELF_DISCONNECT_COMMAND = "notifyclientleftview"
        const val CLIENT_ID_PARAMETER = "clid"
        const val CLIENT_DISCONNECT_COMMAND = "clientdisconnect"
        const val REASON_ID_PARAMETER = "reasonid"
        const val REASON_MESSAGE_PARAMETER = "reasonmsg"
        const val SELF_DISCONNECT_REASON_ID = "8"
    }
}

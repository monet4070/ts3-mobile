package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.CommandException
import com.github.manevolent.ts3j.audio.Microphone
import com.github.manevolent.ts3j.event.ChannelCreateEvent
import com.github.manevolent.ts3j.event.ChannelDeletedEvent
import com.github.manevolent.ts3j.event.ChannelEditedEvent
import com.github.manevolent.ts3j.event.ChannelListEvent
import com.github.manevolent.ts3j.event.ChannelMovedEvent
import com.github.manevolent.ts3j.event.ClientJoinEvent
import com.github.manevolent.ts3j.event.ClientLeaveEvent
import com.github.manevolent.ts3j.event.ClientMovedEvent
import com.github.manevolent.ts3j.event.ClientUpdatedEvent
import com.github.manevolent.ts3j.event.DisconnectedEvent
import com.github.manevolent.ts3j.event.TS3Listener
import com.github.manevolent.ts3j.enums.CodecType
import com.github.manevolent.ts3j.protocol.packet.PacketBody0Voice
import com.github.manevolent.ts3j.protocol.packet.PacketBody1VoiceWhisper
import com.github.manevolent.ts3j.protocol.PacketKind
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class Ts3jSessionClient : Ts3SessionClient {
    private val generation = AtomicLong(0L)
    private val snapshotStore = SessionSnapshotStore()

    @Volatile
    private var socket: LocalTeamspeakClientSocket? = null

    @Volatile
    private var listener: Ts3SessionListener? = null

    @Volatile
    private var voiceSource: EncodedVoiceSource? = null

    override fun connect(
        config: ServerConfig,
        identityMaterial: String,
        listener: Ts3SessionListener,
    ) {
        val normalized = config.normalized()
        val validationError = normalized.validationError()
        require(validationError == null) { validationError ?: "Invalid server configuration" }

        disconnectQuietly()
        val token = generation.incrementAndGet()
        this.listener = listener
        snapshotStore.clear()
        emitStatus(token, ConnectionStatus(ConnectionPhase.CONNECTING))

        val identity = Ts3IdentityCodec.decode(identityMaterial)
        val client = LocalTeamspeakClientSocket()
        val asynchronousFailure = AtomicReference<Throwable?>(null)
        socket = client

        client.setIdentity(identity)
        client.setNickname(normalized.nickname)
        client.setHWID(identity.uid.toBase64())
        client.setMicrophone(voiceSource?.toMicrophone())
        client.setExceptionHandler { error ->
            if (token == generation.get()) {
                asynchronousFailure.compareAndSet(null, error)
                logFailure("background protocol failure", error)
                emitStatus(
                    token,
                    ConnectionStatus(
                        ConnectionPhase.ERROR,
                        error.conciseMessage(),
                        retryable = error.isRetryableConnectionFailure(),
                    ),
                )
            }
        }
        client.setVoiceHandler { packet ->
            try {
                forwardVoicePacket(packet, token)
            } catch (error: RuntimeException) {
                logFailure("voice callback rejected", error)
            }
        }
        client.setWhisperHandler { packet ->
            try {
                forwardWhisperPacket(packet, token)
            } catch (error: RuntimeException) {
                logFailure("whisper callback rejected", error)
            }
        }
        client.addListener(createListener(client, token))

        try {
            val address = InetSocketAddress(
                InetAddress.getByName(normalized.host),
                normalized.port,
            )
            logDiagnostic("connecting to ${address.address.hostAddress}:${address.port}")
            client.connect(address, normalized.password.toTs3jServerPassword(), CONNECT_TIMEOUT_MS)
            if (token != generation.get()) {
                runCatching { client.close() }
                if (socket === client) socket = null
                return
            }
            try {
                client.subscribeAll()
            } catch (error: CommandException) {
                logDiagnostic("event subscriptions unavailable: ${error.conciseMessage()}")
            }
            if (token != generation.get()) {
                runCatching { client.close() }
                if (socket === client) socket = null
                return
            }
            publishSnapshot(token)
            emitStatus(token, ConnectionStatus(ConnectionPhase.CONNECTED))
        } catch (error: Throwable) {
            val reportedError = asynchronousFailure.get() ?: error.withNetworkDiagnostics(client)
            logFailure("connection failed", reportedError)
            if (token == generation.get()) {
                emitStatus(
                    token,
                    ConnectionStatus(
                        ConnectionPhase.ERROR,
                        reportedError.conciseMessage(),
                        retryable = reportedError.isRetryableConnectionFailure(),
                    ),
                )
            }
            runCatching { client.close() }
            if (socket === client) socket = null
            throw reportedError
        }
    }

    override fun disconnect(reason: String) {
        val current = socket ?: run {
            listener?.onStatusChanged(ConnectionStatus())
            return
        }
        generation.incrementAndGet()
        listener?.onStatusChanged(ConnectionStatus(ConnectionPhase.DISCONNECTING))
        try {
            runCatching { current.disconnect(reason) }
        } finally {
            runCatching { current.close() }
            if (socket === current) socket = null
            listener?.onSnapshotChanged(SessionSnapshot.Empty)
            listener?.onStatusChanged(ConnectionStatus())
        }
    }

    override fun setVoiceSource(source: EncodedVoiceSource?) {
        voiceSource = source
        socket?.setMicrophone(source?.toMicrophone())
    }

    override fun joinChannel(channelId: Int, password: String) {
        require(channelId > 0) { "Invalid channel ID" }
        val current = socket?.takeIf { it.isConnected }
            ?: error("Not connected to a TeamSpeak server")
        current.joinChannel(channelId, password)
        snapshotStore.updateParticipant(current.clientId) { participant ->
            participant.copy(channelId = channelId)
        }
        publishSnapshot(generation.get())
    }

    override fun close() {
        generation.incrementAndGet()
        val current = socket
        socket = null
        runCatching { current?.close() }
        snapshotStore.clear()
        voiceSource = null
        listener = null
    }

    private fun createListener(
        client: LocalTeamspeakClientSocket,
        token: Long,
    ): TS3Listener = object : TS3Listener {
        override fun onDisconnected(event: DisconnectedEvent) {
            if (token != generation.get()) return
            socket = null
            runCatching { client.close() }
            snapshotStore.clear()
            listener?.onSnapshotChanged(SessionSnapshot.Empty)
            emitStatus(
                token,
                ConnectionStatus(
                    ConnectionPhase.DISCONNECTED,
                    "Server closed the connection (${event.reasonId})",
                    retryable = event.reasonId !in TERMINAL_DISCONNECT_REASONS,
                ),
            )
        }

        override fun onChannelList(event: ChannelListEvent) {
            snapshotStore.putChannel(event.map.toChannel(event.channelId))
            publishSnapshotWhenConnected(client, token)
        }

        override fun onClientJoin(event: ClientJoinEvent) {
            if (event.clientType == REGULAR_CLIENT_TYPE) {
                snapshotStore.putParticipant(event.toParticipant())
                publishSnapshotWhenConnected(client, token)
            }
        }

        override fun onClientLeave(event: ClientLeaveEvent) {
            snapshotStore.removeParticipant(event.clientId)
            publishSnapshotWhenConnected(client, token)
        }

        override fun onClientMoved(event: ClientMovedEvent) {
            snapshotStore.updateParticipant(event.clientId) { it.copy(channelId = event.targetChannelId) }
            publishSnapshotWhenConnected(client, token)
        }

        override fun onClientChanged(event: ClientUpdatedEvent) {
            snapshotStore.updateParticipant(event.clientId) { it.withUpdates(event.map) }
            publishSnapshotWhenConnected(client, token)
        }

        override fun onChannelCreate(event: ChannelCreateEvent) {
            snapshotStore.putChannel(event.map.toChannel(event.channelId))
            publishSnapshotWhenConnected(client, token)
        }

        override fun onChannelDeleted(event: ChannelDeletedEvent) {
            snapshotStore.removeChannel(event.channelId)
            publishSnapshotWhenConnected(client, token)
        }

        override fun onChannelEdit(event: ChannelEditedEvent) {
            snapshotStore.updateChannel(event.channelId) { it.withUpdates(event.map) }
            publishSnapshotWhenConnected(client, token)
        }

        override fun onChannelMoved(event: ChannelMovedEvent) {
            snapshotStore.updateChannel(event.channelId) {
                it.copy(parentId = event.channelParentId, orderAfterId = event.channelOrder)
            }
            publishSnapshotWhenConnected(client, token)
        }
    }

    private fun publishSnapshotWhenConnected(client: LocalTeamspeakClientSocket, token: Long) {
        if (client.isConnected) publishSnapshot(token)
    }

    private fun publishSnapshot(token: Long) {
        if (token != generation.get()) return
        val ownClientId = socket?.takeIf { it.isConnected }?.clientId
        listener?.onSnapshotChanged(snapshotStore.snapshot().copy(ownClientId = ownClientId))
    }

    private fun emitStatus(token: Long, status: ConnectionStatus) {
        if (token == generation.get()) listener?.onStatusChanged(status)
    }

    private fun forwardVoicePacket(packet: PacketBody0Voice, token: Long) {
        forwardVoiceFrame(
            clientId = packet.clientId,
            packetId = packet.packetId,
            codecType = packet.codecType,
            codecData = packet.codecData,
            isWhisper = false,
            token = token,
        )
    }

    private fun forwardWhisperPacket(packet: PacketBody1VoiceWhisper, token: Long) {
        forwardVoiceFrame(
            clientId = packet.clientId,
            packetId = packet.packetId,
            codecType = packet.codecType,
            codecData = packet.codecData,
            isWhisper = true,
            token = token,
        )
    }

    private fun forwardVoiceFrame(
        clientId: Int,
        packetId: Int,
        codecType: CodecType,
        codecData: ByteArray,
        isWhisper: Boolean,
        token: Long,
    ) {
        if (token != generation.get()) return
        val codec = when (codecType) {
            CodecType.OPUS_VOICE -> VoiceCodec.OPUS_VOICE
            CodecType.OPUS_MUSIC -> VoiceCodec.OPUS_MUSIC
            else -> return
        }
        listener?.onVoiceFrame(
            VoiceFrame(
                clientId = clientId,
                packetId = packetId,
                codec = codec,
                encodedData = codecData.copyOf(),
                isWhisper = isWhisper,
            ),
        )
    }

    private fun disconnectQuietly() {
        val current = socket ?: return
        runCatching { current.disconnect("Replacing connection") }
        runCatching { current.close() }
        if (socket === current) socket = null
    }

    private fun EncodedVoiceSource.toMicrophone(): Microphone = object : Microphone {
        override fun isReady(): Boolean = runCatching { this@toMicrophone.isReady() }
            .getOrDefault(false)

        override fun getCodec(): CodecType = CodecType.OPUS_VOICE

        override fun provide(): ByteArray = runCatching { this@toMicrophone.pollEncodedFrame() }
            .getOrNull()
            ?: EMPTY_AUDIO_FRAME
    }

    private fun Map<String, String>.toChannel(id: Int) = Ts3Channel(
        id = id,
        parentId = intValue("pid") ?: intValue("cpid") ?: 0,
        orderAfterId = intValue("channel_order") ?: 0,
        name = get("channel_name").orEmpty(),
        clientCount = 0,
        hasPassword = booleanValue("channel_flag_password") ?: false,
        isDefault = booleanValue("channel_flag_default") ?: false,
    )

    private fun ClientJoinEvent.toParticipant() = Ts3Participant(
        id = clientId,
        channelId = clientTargetId,
        nickname = clientNickname,
        isTalking = isClientTalking,
        isInputMuted = isClientInputMuted,
        isOutputMuted = isClientOutputMuted,
        uniqueIdentifier = uniqueClientIdentifier,
    )

    private fun Ts3Channel.withUpdates(values: Map<String, String>) = copy(
        parentId = values.intValue("pid") ?: values.intValue("cpid") ?: parentId,
        orderAfterId = values.intValue("channel_order") ?: orderAfterId,
        name = values["channel_name"] ?: name,
        hasPassword = values.booleanValue("channel_flag_password") ?: hasPassword,
        isDefault = values.booleanValue("channel_flag_default") ?: isDefault,
    )

    private fun Ts3Participant.withUpdates(values: Map<String, String>) = copy(
        nickname = values["client_nickname"] ?: nickname,
        uniqueIdentifier = values["client_unique_identifier"] ?: uniqueIdentifier,
        isTalking = values.booleanValue("client_flag_talking")
            ?: values.booleanValue("status")
            ?: isTalking,
        isInputMuted = values.booleanValue("client_input_muted") ?: isInputMuted,
        isOutputMuted = values.booleanValue("client_output_muted") ?: isOutputMuted,
    )

    private fun Map<String, String>.intValue(key: String): Int? = get(key)?.toIntOrNull()

    private fun Map<String, String>.booleanValue(key: String): Boolean? = get(key)?.let { it == "1" }

    private fun Throwable.conciseMessage(): String {
        var cursor: Throwable? = this
        while (cursor != null) {
            cursor.message?.takeIf(String::isNotBlank)?.let { return it.take(180) }
            cursor = cursor.cause
        }
        return this::class.java.simpleName
    }

    private fun Throwable.withNetworkDiagnostics(client: LocalTeamspeakClientSocket): Throwable {
        if (this !is TimeoutException) return this

        val sentPackets = PacketKind.entries.sumOf { client.getStatistics(it).sentPackets }
        val receivedPackets = PacketKind.entries.sumOf { client.getStatistics(it).receivedPackets }
        val detail = if (receivedPackets == 0) {
            "the server sent no TeamSpeak response"
        } else {
            "sent $sentPackets and received $receivedPackets UDP packets before the handshake stalled"
        }
        return IOException("TeamSpeak handshake timed out: $detail", this)
    }

    private fun logDiagnostic(message: String) {
        System.err.println("TS3_DIAG: $message")
    }

    private fun logFailure(stage: String, error: Throwable) {
        System.err.println("TS3_DIAG: $stage: ${error.conciseMessage()}")
        error.printStackTrace(System.err)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val REGULAR_CLIENT_TYPE = 0
        val TERMINAL_DISCONNECT_REASONS = setOf(4, 5)
        val EMPTY_AUDIO_FRAME = ByteArray(0)
    }
}

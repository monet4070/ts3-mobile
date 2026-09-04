package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.audio.Microphone
import com.github.manevolent.ts3j.command.CommandException
import com.github.manevolent.ts3j.enums.CodecType
import com.github.manevolent.ts3j.event.DisconnectedEvent
import com.github.manevolent.ts3j.event.TS3Listener
import com.github.manevolent.ts3j.protocol.PacketKind
import com.github.manevolent.ts3j.protocol.packet.PacketBody0Voice
import com.github.manevolent.ts3j.protocol.packet.PacketBody1VoiceWhisper
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeoutException

class Ts3jSessionClient : Ts3SessionClient {
    private val socketFactory: Ts3jClientSocketFactory
    private val generation = SessionGenerationGate()
    private val snapshotStore = SessionSnapshotStore()

    constructor() : this(Ts3jClientSocketFactory { LocalTeamspeakClientSocketAdapter() })

    internal constructor(socketFactory: Ts3jClientSocketFactory) {
        this.socketFactory = socketFactory
    }

    @Volatile
    private var socket: Ts3jClientSocket? = null

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

        val (token, previousSocket) =
            generation.begin { nextToken ->
                val previous = socket
                socket = null
                this.listener = listener
                snapshotStore.clear()
                nextToken to previous
            }
        disconnectQuietly(previousSocket)
        emitStatus(token, ConnectionStatus(ConnectionPhase.CONNECTING))

        val identity = Ts3IdentityCodec.decode(identityMaterial)
        val client = socketFactory.create()
        val connectionAttempt = ConnectionAttemptGate()
        client.setIdentity(identity)
        client.setNickname(normalized.nickname)
        client.setHWID(identity.uid.toBase64())
        client.setMicrophone(voiceSource?.toMicrophone())
        client.setExceptionHandler { error ->
            val delivery =
                generation.withCurrent(token) {
                    connectionAttempt.recordFailure(error)
                } ?: return@setExceptionHandler
            logFailure("background protocol failure", error)
            if (delivery == ConnectionAttemptGate.FailureDelivery.EMIT_NOW) {
                emitConnectionFailureStatus(token, error)
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
        client.addListener(createEventAdapter(client, token))
        if (generation.withCurrent(token) {
                socket = client
                true
            } != true
        ) {
            runCatching { client.close() }
            return
        }

        try {
            val address =
                InetSocketAddress(
                    InetAddress.getByName(normalized.host),
                    normalized.port,
                )
            logDiagnostic("starting TeamSpeak UDP connection")
            client.connect(address, normalized.password.takeIf(String::isNotBlank), CONNECT_TIMEOUT_MS)
            if (!generation.isCurrent(token)) {
                runCatching { client.close() }
                return
            }
            try {
                client.subscribeAll()
            } catch (error: CommandException) {
                logDiagnostic("event subscriptions unavailable: ${error.conciseMessage()}")
            }
            if (!generation.isCurrent(token)) {
                runCatching { client.close() }
                return
            }
            val connectedEmissionStarted =
                generation.withCurrent(token) {
                    connectionAttempt.beginConnectedEmission()
                } ?: return
            if (!connectedEmissionStarted) {
                throw checkNotNull(connectionAttempt.failureOrNull())
            }
            try {
                publishSnapshot(token)
                emitStatus(token, ConnectionStatus(ConnectionPhase.CONNECTED))
            } finally {
                connectionAttempt.completeConnectedEmission()?.let { deferredFailure ->
                    emitConnectionFailureStatus(token, deferredFailure)
                }
            }
        } catch (error: Throwable) {
            val reportedError = connectionAttempt.failureOrNull() ?: error.withNetworkDiagnostics(client)
            logFailure("connection failed", reportedError)
            reportConnectionFailure(token, connectionAttempt, reportedError)
            runCatching { client.close() }
            generation.withCurrent(token) {
                if (socket === client) socket = null
            }
            throw reportedError
        }
    }

    override fun disconnect(reason: String) {
        val (current, targetListener) =
            generation.invalidate {
                val previous = socket
                socket = null
                snapshotStore.clear()
                previous to listener
            }
        if (current == null) {
            targetListener?.onStatusChanged(ConnectionStatus())
            return
        }
        targetListener?.onStatusChanged(ConnectionStatus(ConnectionPhase.DISCONNECTING))
        try {
            runCatching { current.disconnect(reason) }
        } finally {
            runCatching { current.close() }
            targetListener?.onSnapshotChanged(SessionSnapshot.Empty)
            targetListener?.onStatusChanged(ConnectionStatus())
        }
    }

    override fun setVoiceSource(source: EncodedVoiceSource?) {
        voiceSource = source
        socket?.setMicrophone(source?.toMicrophone())
    }

    override fun joinChannel(
        channelId: Int,
        password: String,
    ) {
        require(channelId > 0) { "Invalid channel ID" }
        val token = generation.currentToken()
        val current =
            generation.withCurrent(token) {
                socket?.takeIf { it.isConnected }
            }
                ?: error("Not connected to a TeamSpeak server")
        current.joinChannel(channelId, password)
        val snapshotUpdated =
            generation.withCurrent(token) {
                if (socket !== current) return@withCurrent false
                snapshotStore.updateParticipant(current.clientId) { participant ->
                    participant.copy(channelId = channelId)
                }
                true
            } == true
        if (snapshotUpdated) publishSnapshot(token)
    }

    override fun close() {
        val current =
            generation.invalidate {
                val previous = socket
                socket = null
                snapshotStore.clear()
                voiceSource = null
                listener = null
                previous
            }
        runCatching { current?.close() }
    }

    private fun createEventAdapter(
        client: Ts3jClientSocket,
        token: Long,
    ): TS3Listener =
        Ts3jEventAdapter(
            token = token,
            generation = generation,
            snapshotStore = snapshotStore,
            client = client,
            publishSnapshot = { publishSnapshot(token) },
            handleDisconnected = { event -> handleServerDisconnect(client, token, event) },
        )

    private fun handleServerDisconnect(
        client: Ts3jClientSocket,
        token: Long,
        event: DisconnectedEvent,
    ) {
        val targetListener =
            generation.withCurrent(token) {
                socket = null
                snapshotStore.clear()
                listener
            } ?: return
        runCatching { client.close() }
        targetListener.onSnapshotChanged(SessionSnapshot.Empty)
        targetListener.onStatusChanged(
            ConnectionStatus(
                ConnectionPhase.DISCONNECTED,
                "Server closed the connection (${event.reasonId})",
                retryable = event.reasonId !in TERMINAL_DISCONNECT_REASONS,
            ),
        )
    }

    private fun publishSnapshot(token: Long) {
        val pending =
            generation.withCurrent(token) {
                val ownClientId = socket?.takeIf { it.isConnected }?.clientId
                listener to snapshotStore.snapshot().copy(ownClientId = ownClientId)
            } ?: return
        pending.first?.onSnapshotChanged(pending.second)
    }

    private fun emitStatus(
        token: Long,
        status: ConnectionStatus,
    ) {
        generation.withCurrent(token) { listener }?.onStatusChanged(status)
    }

    private fun forwardVoicePacket(
        packet: PacketBody0Voice,
        token: Long,
    ) {
        forwardVoiceFrame(
            clientId = packet.clientId,
            packetId = packet.packetId,
            codecType = packet.codecType,
            codecData = packet.codecData,
            isWhisper = false,
            token = token,
        )
    }

    private fun forwardWhisperPacket(
        packet: PacketBody1VoiceWhisper,
        token: Long,
    ) {
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
        if (!generation.isCurrent(token)) return
        val codec =
            when (codecType) {
                CodecType.OPUS_VOICE -> VoiceCodec.OPUS_VOICE
                CodecType.OPUS_MUSIC -> VoiceCodec.OPUS_MUSIC
                else -> return
            }
        val targetListener = generation.withCurrent(token) { listener } ?: return
        targetListener.onVoiceFrame(
            VoiceFrame(
                clientId = clientId,
                packetId = packetId,
                codec = codec,
                encodedData = codecData.copyOf(),
                isWhisper = isWhisper,
            ),
        )
    }

    private fun disconnectQuietly(current: Ts3jClientSocket?) {
        current ?: return
        runCatching { current.disconnect("Replacing connection") }
        runCatching { current.close() }
    }

    private fun reportConnectionFailure(
        token: Long,
        attempt: ConnectionAttemptGate,
        error: Throwable,
    ) {
        val delivery =
            generation.withCurrent(token) {
                attempt.recordFailure(error)
            }
        if (delivery == ConnectionAttemptGate.FailureDelivery.EMIT_NOW) {
            emitConnectionFailureStatus(token, error)
        }
    }

    private fun emitConnectionFailureStatus(
        token: Long,
        error: Throwable,
    ) {
        emitStatus(
            token,
            ConnectionStatus(
                ConnectionPhase.ERROR,
                error.conciseMessage(),
                retryable = error.isRetryableConnectionFailure(),
            ),
        )
    }

    private fun EncodedVoiceSource.toMicrophone(): Microphone =
        object : Microphone {
            override fun isReady(): Boolean =
                runCatching { this@toMicrophone.isReady() }
                    .getOrDefault(false)

            override fun getCodec(): CodecType = CodecType.OPUS_VOICE

            override fun provide(): ByteArray =
                runCatching { this@toMicrophone.pollEncodedFrame() }
                    .getOrNull()
                    ?: EMPTY_AUDIO_FRAME
        }

    private fun Throwable.conciseMessage(): String {
        var cursor: Throwable? = this
        while (cursor != null) {
            cursor.message?.takeIf(String::isNotBlank)?.let { return it.take(180) }
            cursor = cursor.cause
        }
        return this::class.java.simpleName
    }

    private fun Throwable.withNetworkDiagnostics(client: Ts3jClientSocket): Throwable {
        if (this !is TimeoutException) return this

        val sentPackets = PacketKind.entries.sumOf { client.getStatistics(it).sentPackets }
        val receivedPackets = PacketKind.entries.sumOf { client.getStatistics(it).receivedPackets }
        val detail =
            if (receivedPackets == 0) {
                "the server sent no TeamSpeak response"
            } else {
                "sent $sentPackets and received $receivedPackets UDP packets before the handshake stalled"
            }
        return IOException("TeamSpeak handshake timed out: $detail", this)
    }

    private fun logDiagnostic(message: String) {
        System.err.println("TS3_DIAG: $message")
    }

    private fun logFailure(
        stage: String,
        error: Throwable,
    ) {
        System.err.println(
            "TS3_DIAG: $stage; types=${error.sanitizedFailureTypes()}; " +
                "retryable=${error.isRetryableConnectionFailure()}",
        )
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val REGULAR_CLIENT_TYPE = 0
        val TERMINAL_DISCONNECT_REASONS = setOf(4, 5)
        val EMPTY_AUDIO_FRAME = ByteArray(0)
    }
}

package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.audio.Microphone
import com.github.manevolent.ts3j.command.CommandException
import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.command.parameter.CommandSingleParameter
import com.github.manevolent.ts3j.event.TS3Listener
import com.github.manevolent.ts3j.identity.LocalIdentity
import com.github.manevolent.ts3j.protocol.PacketKind
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.client.ClientConnectionState
import com.github.manevolent.ts3j.protocol.packet.PacketBody0Voice
import com.github.manevolent.ts3j.protocol.packet.PacketBody1VoiceWhisper
import com.github.manevolent.ts3j.protocol.packet.statistics.PacketStatistics
import com.github.manevolent.ts3j.protocol.socket.client.AbstractTeamspeakClientSocket
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.TimeoutException

internal interface Ts3jClientSocket {
    fun setIdentity(identity: LocalIdentity)

    fun setNickname(nickname: String)

    fun setHWID(hwid: String)

    fun setMicrophone(microphone: Microphone?)

    fun setExceptionHandler(handler: (Throwable) -> Unit)

    fun setVoiceHandler(handler: (PacketBody0Voice) -> Unit)

    fun setWhisperHandler(handler: (PacketBody1VoiceWhisper) -> Unit)

    fun addListener(listener: TS3Listener)

    fun connect(
        address: InetSocketAddress,
        password: String?,
        timeoutMs: Long,
    )

    fun subscribeAll()

    /**
     * Runs one bounded, read-only control round trip.
     *
     * Returns true when the server answered within [timeoutMs], including an
     * error response such as a permission refusal: any response proves the
     * session is alive. Returns false only on timeout or a transport failure.
     * The request never changes server state.
     */
    fun probeLiveness(timeoutMs: Long): Boolean

    val isConnected: Boolean

    /**
     * True while the transport has already accepted a server-issued disconnect for
     * this client but the matching listener notification has not been delivered
     * yet, so a poll of [isConnected] cannot mistake a kick or ban for a silent
     * transport loss. Implementations that cannot observe this report false.
     */
    val disconnectNotificationPending: Boolean
        get() = false

    val clientId: Int

    fun joinChannel(
        channelId: Int,
        password: String,
    )

    fun disconnect(reason: String)

    fun close()

    fun getStatistics(packetKind: PacketKind): PacketStatistics
}

internal fun interface Ts3jClientSocketFactory {
    fun create(): Ts3jClientSocket
}

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
internal class StateAwareTeamspeakClientSocket : LocalTeamspeakClientSocket() {
    @Volatile
    private var attemptStarted = false

    @Volatile
    private var closed = false

    @Volatile
    private var disconnectNotificationPending = false

    /** Volatile-backed transport state for callers outside ts3j. */
    val transportConnected: Boolean
        get() = !closed && super.isConnected

    /** Latched once a server-issued disconnect for this client is accepted. */
    val isDisconnectNotificationPending: Boolean
        get() = disconnectNotificationPending

    override fun setState(state: ClientConnectionState) {
        if (state == ClientConnectionState.CONNECTING) attemptStarted = true
        try {
            super.setState(state)
        } finally {
            // The library sets DISCONNECTED from its own constructor, before an
            // attempt exists; only a disconnect after CONNECTING is a lost session.
            if (state == ClientConnectionState.DISCONNECTED && attemptStarted) closed = true
        }
    }

    override fun process(
        client: AbstractTeamspeakClientSocket,
        command: SingleCommand,
    ) {
        if (command.isSelfDisconnectNotification()) disconnectNotificationPending = true
        super.process(client, command)
    }

    private fun SingleCommand.isSelfDisconnectNotification(): Boolean {
        if (name != SELF_DISCONNECT_COMMAND) return false
        if (!has(CLIENT_ID_PARAMETER)) return false
        return get(CLIENT_ID_PARAMETER)?.value?.toIntOrNull() == clientId
    }

    private companion object {
        const val SELF_DISCONNECT_COMMAND = "notifyclientleftview"
        const val CLIENT_ID_PARAMETER = "clid"
    }
}

internal class LocalTeamspeakClientSocketAdapter(
    private val delegate: LocalTeamspeakClientSocket = StateAwareTeamspeakClientSocket(),
) : Ts3jClientSocket {
    override fun setIdentity(identity: LocalIdentity) {
        delegate.setIdentity(identity)
    }

    override fun setNickname(nickname: String) {
        delegate.setNickname(nickname)
    }

    override fun setHWID(hwid: String) {
        delegate.setHWID(hwid)
    }

    override fun setMicrophone(microphone: Microphone?) {
        delegate.setMicrophone(microphone)
    }

    override fun setExceptionHandler(handler: (Throwable) -> Unit) {
        delegate.setExceptionHandler { error -> handler(error) }
    }

    override fun setVoiceHandler(handler: (PacketBody0Voice) -> Unit) {
        delegate.setVoiceHandler { packet -> handler(packet) }
    }

    override fun setWhisperHandler(handler: (PacketBody1VoiceWhisper) -> Unit) {
        delegate.setWhisperHandler { packet -> handler(packet) }
    }

    override fun addListener(listener: TS3Listener) {
        delegate.addListener(listener)
    }

    override fun connect(
        address: InetSocketAddress,
        password: String?,
        timeoutMs: Long,
    ) {
        delegate.connect(address, password, timeoutMs)
    }

    override fun subscribeAll() {
        delegate.subscribeAll()
    }

    override fun probeLiveness(timeoutMs: Long): Boolean {
        return try {
            // clientinfo is read-only, so a probe cannot rename or otherwise
            // mutate the client even when the server enforces a different
            // nickname than the one requested.
            val command =
                SingleCommand(
                    "clientinfo",
                    ProtocolRole.CLIENT,
                    CommandSingleParameter("clid", clientId.toString()),
                )
            delegate.executeCommand(command).get(timeoutMs)
            true
        } catch (_: CommandException) {
            // The server answered with an error: it is alive, the command was
            // merely refused.
            true
        } catch (_: TimeoutException) {
            false
        } catch (_: IOException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    override val isConnected: Boolean
        get() = (delegate as? StateAwareTeamspeakClientSocket)?.transportConnected ?: delegate.isConnected

    override val disconnectNotificationPending: Boolean
        get() = (delegate as? StateAwareTeamspeakClientSocket)?.isDisconnectNotificationPending ?: false

    override val clientId: Int
        get() = delegate.clientId

    override fun joinChannel(
        channelId: Int,
        password: String,
    ) {
        delegate.joinChannel(channelId, password)
    }

    override fun disconnect(reason: String) {
        delegate.disconnect(reason)
    }

    override fun close() {
        delegate.close()
    }

    override fun getStatistics(packetKind: PacketKind): PacketStatistics = delegate.getStatistics(packetKind)
}

package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.audio.Microphone
import com.github.manevolent.ts3j.command.CommandException
import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.command.parameter.CommandSingleParameter
import com.github.manevolent.ts3j.event.TS3Listener
import com.github.manevolent.ts3j.identity.LocalIdentity
import com.github.manevolent.ts3j.protocol.PacketKind
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.packet.PacketBody0Voice
import com.github.manevolent.ts3j.protocol.packet.PacketBody1VoiceWhisper
import com.github.manevolent.ts3j.protocol.packet.statistics.PacketStatistics
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

internal class LocalTeamspeakClientSocketAdapter(
    private val delegate: LocalTeamspeakClientSocket = LocalTeamspeakClientSocket(),
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
        get() = delegate.isConnected

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

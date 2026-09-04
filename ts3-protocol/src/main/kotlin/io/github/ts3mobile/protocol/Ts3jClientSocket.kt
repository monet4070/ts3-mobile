package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.audio.Microphone
import com.github.manevolent.ts3j.event.TS3Listener
import com.github.manevolent.ts3j.identity.LocalIdentity
import com.github.manevolent.ts3j.protocol.PacketKind
import com.github.manevolent.ts3j.protocol.packet.PacketBody0Voice
import com.github.manevolent.ts3j.protocol.packet.PacketBody1VoiceWhisper
import com.github.manevolent.ts3j.protocol.packet.statistics.PacketStatistics
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket
import java.net.InetSocketAddress

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

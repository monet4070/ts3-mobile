package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.protocol.NetworkPacket
import com.github.manevolent.ts3j.protocol.packet.PacketBody
import java.lang.reflect.Method

internal class Ts3jVoiceTiming(
    private val collector: VoiceTimingCollector,
    private val clock: () -> Long = System::nanoTime,
) {
    private val token = collector.open(receivedStamp != null && sentStamp != null)

    private class Offered(val encodedAt: Long?)

    private val offered = ThreadLocal<Offered>()

    fun received(body: PacketBody) {
        val getter = receivedStamp ?: return
        val now = clock()
        readStamp(getter, body)?.let { collector.received(token, it, now) }
    }

    fun offer(frame: TimedEncodedVoiceFrame?) {
        if (frame == null || frame.bytes.isEmpty()) {
            offered.remove()
            collector.emptyRead(token)
        } else {
            offered.set(Offered(frame.encodedAtNanos))
        }
    }

    fun successfulSend(packet: NetworkPacket) {
        val frame = offered.get() ?: return
        offered.remove()
        readStamp(sentStamp, packet)?.let { collector.sent(token, frame.encodedAt, it) }
    }

    fun clearOffered() = offered.remove()

    fun close() = collector.close(token)

    private companion object {
        val receivedStamp = method(PacketBody::class.java, "getUdpReceivedNanoTime")
        val sentStamp = method(NetworkPacket::class.java, "getSentNanoTime")

        fun method(
            owner: Class<*>,
            name: String,
        ): Method? =
            try {
                owner.getMethod(name)
            } catch (_: NoSuchMethodException) {
                null
            }

        fun readStamp(
            getter: Method?,
            value: Any,
        ): Long? =
            try {
                getter?.invoke(value) as? Long
            } catch (_: ReflectiveOperationException) {
                null
            }
    }
}

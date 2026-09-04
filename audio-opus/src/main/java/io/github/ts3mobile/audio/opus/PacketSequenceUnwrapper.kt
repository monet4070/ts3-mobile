package io.github.ts3mobile.audio.opus

/** Extends TeamSpeak's wrapping 16-bit packet id into a stable signed sequence. */
internal class PacketSequenceUnwrapper {
    private var highestRaw: Int? = null
    private var highestExtended = 0L

    fun unwrap(packetId: Int): Long {
        val raw = packetId and PACKET_ID_MASK
        val previousRaw = highestRaw
        if (previousRaw == null) {
            highestRaw = raw
            highestExtended = raw.toLong()
            return highestExtended
        }

        val delta = signedPacketDistance16(previousRaw, raw)
        val extended = highestExtended + delta
        if (delta > 0) {
            highestRaw = raw
            highestExtended = extended
        }
        return extended
    }

    fun reset() {
        highestRaw = null
        highestExtended = 0L
    }
}

internal fun signedPacketDistance16(
    previous: Int,
    current: Int,
): Int = ((current - previous + HALF_PACKET_ID_RANGE) and PACKET_ID_MASK) - HALF_PACKET_ID_RANGE

private const val PACKET_ID_MASK = 0xffff
private const val HALF_PACKET_ID_RANGE = 0x8000

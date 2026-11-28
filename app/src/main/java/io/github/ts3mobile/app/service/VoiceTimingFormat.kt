package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.VoiceDelayWindow
import io.github.ts3mobile.protocol.VoiceTimingCollector
import io.github.ts3mobile.protocol.VoiceTimingWindow

internal object VoiceTimingFormat {
    fun row(window: VoiceTimingWindow): String =
        with(window) {
            listOf(
                udpTimestampsAvailable,
                udpReadToCallback.buckets.joinToString(","),
                encodedToUdpSent.buckets.joinToString(","),
                emptyUplinkReads,
                captureOverflowFrames,
                sendGapsBelow10Ms,
            ).joinToString(";")
        }

    fun parse(row: String): VoiceTimingWindow {
        val columns = row.split(';', limit = 7)
        require(columns.size == 6)
        val available = columns[0].toBooleanStrict()
        val receive = parseDelay(columns[1])
        val send = parseDelay(columns[2])
        val bursts = count(columns[5])
        require(available || (receive.buckets.all { it == 0L } && send.buckets.all { it == 0L } && bursts == 0L))
        return VoiceTimingWindow(available, receive, send, count(columns[3]), count(columns[4]), bursts)
    }

    fun json(window: VoiceTimingWindow): String =
        with(window) {
            "{\"udpTimestampsAvailable\":$udpTimestampsAvailable,\"emptyUplinkReads\":$emptyUplinkReads," +
                "\"captureOverflowFrames\":$captureOverflowFrames,\"sendGapsBelow10Ms\":$sendGapsBelow10Ms," +
                "\"udpReadToCallback\":${delayJson(udpReadToCallback)},\"encodedToUdpSent\":${delayJson(encodedToUdpSent)}}"
        }

    private fun delayJson(delay: VoiceDelayWindow): String =
        "{\"buckets\":${delay.buckets.joinToString(",", "[", "]")},\"p50UpperUs\":${delay.p50UpperUs},\"p95UpperUs\":${delay.p95UpperUs}}"

    private fun parseDelay(column: String): VoiceDelayWindow {
        val size = VoiceTimingCollector.delayUpperBoundsUs.size + 1
        val buckets = column.split(',', limit = size + 1)
        require(buckets.size == size)
        return VoiceDelayWindow(buckets.map { count(it).also { value -> require(value <= VoiceTimingCollector.MAX_BUCKET_COUNT) } })
    }

    private fun count(value: String): Long = value.toLong().also { require(it >= 0L) }
}

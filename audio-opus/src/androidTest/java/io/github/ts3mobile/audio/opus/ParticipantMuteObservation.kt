package io.github.ts3mobile.audio.opus

/** Test-only, read while worker is parked at a fake-output barrier. No production hook. */
internal object ParticipantMuteObservation {
    data class Snapshot(val budget: Int, val input: Int, val pending: Int, val pcm: Int, val decoder: Any)

    fun snapshot(
        player: OpusAudioPlayer,
        client: Int,
    ): Snapshot {
        val queue = queue(player)
        val streams = streams(player)

        @Suppress("UNCHECKED_CAST")
        val talkers = field(streams, "talkers") as Map<Int, TalkerJitterPipeline>
        val talker = checkNotNull(talkers[client])

        @Suppress("UNCHECKED_CAST")
        val pending = field(talker, "pending") as Map<Long, QueuedFrame>
        val input =
            synchronized(queue) {
                @Suppress("UNCHECKED_CAST")
                val frames = field(queue, "frames") as ArrayDeque<QueuedFrame>
                frames.filter { it.frame.clientId == client }.sumOf { checkNotNull(it.info).samples }
            }
        return Snapshot(
            queue.budget.samples(client),
            input,
            pending.values.sumOf { checkNotNull(it.info).samples },
            (field(talker, "pcm") as ReceivePcmBuffer).available,
            field(talker, "decoder"),
        )
    }

    fun budget(
        player: OpusAudioPlayer,
        client: Int,
    ): Int = queue(player).budget.samples(client)

    fun decoderClosed(decoder: Any): Boolean = field(decoder, "handle") == 0L

    private fun queue(player: OpusAudioPlayer) = field(player, "inputQueue") as VoiceReceiveQueue

    private fun streams(player: OpusAudioPlayer): PlaybackStreams {
        val control = field(player, "control")
        return field(field(control, "session"), "streams") as PlaybackStreams
    }

    private fun field(
        target: Any,
        name: String,
    ): Any =
        target.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            checkNotNull(it.get(target))
        }
}

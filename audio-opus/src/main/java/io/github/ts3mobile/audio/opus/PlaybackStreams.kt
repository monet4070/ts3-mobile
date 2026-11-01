package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.sanitizedFailureTypes

internal class PlaybackStreams(
    private val inputQueue: VoiceReceiveQueue,
    private val discontinuityClients: MutableSet<Int>,
    private val participantGains: Map<Int, Float>,
    private val policy: ReceivePolicy = ReceivePolicy(),
    private val decoderFactory: () -> OpusPacketDecoder = { NativeOpusDecoder() },
) : AutoCloseable {
    private val talkers = mutableMapOf<Int, TalkerJitterPipeline>()
    private val mixer = ParticipantGainMixer()
    private var closed = false
    var oldestMixedArrival: Long? = null
        private set

    fun ingest(queued: QueuedFrame) {
        check(!closed) { "Playback streams are closed" }
        val clientId = queued.frame.clientId
        if (clientId !in talkers && talkers.size >= ReceivePolicy.MAX_TRACKED_CLIENTS) {
            val oldest = talkers.minBy { it.value.lastArrivalNanos }.key
            talkers.remove(oldest)?.close()
            mixer.forget(oldest)
            inputQueue.removeClient(oldest)
            inputQueue.budget.stats.budgetResets.incrementAndGet()
        }
        if (discontinuityClients.remove(clientId)) {
            talkers.remove(clientId)?.close()
            mixer.forget(clientId)
        }
        talkers.getOrPut(clientId) {
            TalkerJitterPipeline(decoderFactory = decoderFactory, policy = policy, budget = inputQueue.budget)
        }.offer(queued)
    }

    fun drainAvailable(
        limit: Int,
        now: Long,
    ): Int {
        var count = 0
        while (count < limit) {
            ingest(inputQueue.poll(now) ?: break)
            count++
        }
        return count
    }

    fun prepare(now: Long) {
        val discontinuities = discontinuityClients.iterator()
        while (discontinuities.hasNext()) {
            val clientId = discontinuities.next()
            talkers.remove(clientId)?.close()
            mixer.forget(clientId)
            discontinuities.remove()
        }
        val iterator = talkers.iterator()
        while (iterator.hasNext()) {
            val (clientId, talker) = iterator.next()
            if (now - talker.lastArrivalNanos > ReceivePolicy.TALKER_IDLE_NANOS) {
                talker.close()
                mixer.forget(clientId)
                iterator.remove()
            }
        }
    }

    fun readyToStart(now: Long): Boolean = talkers.values.any { it.readyToStart(now) }

    fun mix(now: Long): ShortArray? {
        oldestMixedArrival = null
        val inputs = ArrayList<ParticipantGainMixer.Input>(talkers.size)
        val iterator = talkers.iterator()
        while (iterator.hasNext()) {
            val (clientId, talker) = iterator.next()
            try {
                talker.fillPcm(now)
                val samples = talker.readPcm(TalkerJitterPipeline.MIX_TICK_SAMPLES)
                if (samples.isNotEmpty()) {
                    talker.oldestReadArrival?.let { arrival -> oldestMixedArrival = minOf(oldestMixedArrival ?: arrival, arrival) }
                    inputs += ParticipantGainMixer.Input(clientId, samples, participantGains[clientId] ?: 1f)
                }
            } catch (error: RuntimeException) {
                System.err.println("TS3_AUDIO: dropping invalid Opus stream: ${error.sanitizedFailureTypes()}")
                talker.close()
                mixer.forget(clientId)
                iterator.remove()
            }
        }
        return mixer.mix(inputs, TalkerJitterPipeline.MIX_TICK_SAMPLES).takeIf { it.isNotEmpty() }
    }

    fun finishTalkspurts() {
        talkers.values.filter { it.isFinishedTalkspurt() }.forEach(TalkerJitterPipeline::finishTalkspurt)
    }

    fun advanceSilence(samples: Int) = mixer.advanceSilence(samples)

    fun reset() {
        talkers.values.forEach(TalkerJitterPipeline::close)
        talkers.clear()
        mixer.reset()
        discontinuityClients.clear()
    }

    override fun close() {
        if (closed) return
        reset()
        closed = true
    }
}

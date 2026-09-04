package io.github.ts3mobile.audio.opus

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import io.github.ts3mobile.protocol.VoiceFrame
import io.github.ts3mobile.protocol.sanitizedFailureTypes
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class OpusAudioPlayer(context: Context) : AutoCloseable {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val inputQueue = ArrayBlockingQueue<QueuedFrame>(INPUT_QUEUE_CAPACITY)
    private val discontinuityClients = ConcurrentHashMap.newKeySet<Int>()
    private val participantGains = ConcurrentHashMap<Int, Float>()
    private val resetRequested = AtomicBoolean(false)
    private val userMuted = AtomicBoolean(false)
    private val focusAllowsPlayback = AtomicBoolean(false)
    private val receivedFrameCount = AtomicLong(0L)
    private val decodedPacketCount = AtomicLong(0L)
    private val loggedFirstFrame = AtomicBoolean(false)
    private val loggedFirstPcm = AtomicBoolean(false)
    private val lifecycleLock = Any()

    @Volatile
    private var control: PlaybackControl? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var preferredDevice: AudioDeviceInfo? = null

    @Volatile
    private var worker: Thread? = null

    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            when (AudioFocusPolicy.signalFor(change)) {
                AudioFocusSignal.GAIN -> {
                    focusAllowsPlayback.set(true)
                    applyVolume()
                }

                AudioFocusSignal.INTERRUPT -> {
                    focusAllowsPlayback.set(false)
                    resetRequested.set(true)
                    applyVolume()
                }

                AudioFocusSignal.IGNORE -> Unit
            }
        }

    private val focusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(voicePlaybackAudioAttributes())
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(focusListener)
            .build()

    fun start() {
        synchronized(lifecycleLock) {
            if (control?.running?.get() == true) return

            val track = createAudioTrack()
            check(track.state == AudioTrack.STATE_INITIALIZED) { "Unable to initialize audio output" }

            inputQueue.clear()
            discontinuityClients.clear()
            resetRequested.set(false)
            focusAllowsPlayback.set(false)
            receivedFrameCount.set(0L)
            decodedPacketCount.set(0L)
            loggedFirstFrame.set(false)
            loggedFirstPcm.set(false)

            val newControl = PlaybackControl()
            control = newControl
            audioTrack = track
            worker =
                Thread({ playbackLoop(track, newControl) }, "ts3-opus-playback").apply {
                    priority = Thread.MAX_PRIORITY
                    start()
                }
        }
    }

    fun submit(frame: VoiceFrame) {
        val activeControl = control ?: return
        if (!activeControl.running.get() || userMuted.get()) return
        if ((participantGains[frame.clientId] ?: DEFAULT_PARTICIPANT_GAIN) <= 0f) return

        val queued = QueuedFrame(frame, System.nanoTime())
        receivedFrameCount.incrementAndGet()
        if (!inputQueue.offer(queued)) {
            inputQueue.poll()?.let { dropped ->
                discontinuityClients += dropped.frame.clientId
            }
            inputQueue.offer(queued)
        }
    }

    fun setMuted(muted: Boolean) {
        userMuted.set(muted)
        resetRequested.set(true)
        if (muted) inputQueue.clear()
        applyVolume()
    }

    fun replaceParticipantGains(gains: Map<Int, Float>) {
        val normalized =
            gains.mapValues { (_, gain) ->
                gain.coerceIn(MIN_PARTICIPANT_GAIN, MAX_PARTICIPANT_GAIN)
            }.filterValues { it != DEFAULT_PARTICIPANT_GAIN }
        if (participantGains == normalized) return

        val previous = participantGains.toMap()
        participantGains.clear()
        participantGains.putAll(normalized)

        (previous.keys + normalized.keys).forEach { clientId ->
            val oldGain = previous[clientId] ?: DEFAULT_PARTICIPANT_GAIN
            val newGain = normalized[clientId] ?: DEFAULT_PARTICIPANT_GAIN
            if ((oldGain <= 0f) != (newGain <= 0f)) discontinuityClients += clientId
        }
        inputQueue.removeIf { queued ->
            (normalized[queued.frame.clientId] ?: DEFAULT_PARTICIPANT_GAIN) <= 0f
        }
    }

    fun setPreferredDevice(device: AudioDeviceInfo?) {
        preferredDevice = device
        audioTrack?.let { track ->
            runCatching { track.setPreferredDevice(device) }
                .onFailure { error ->
                    System.err.println(
                        "TS3_AUDIO: failed to route playback: ${error.sanitizedFailureTypes()}",
                    )
                }
        }
    }

    fun stop() {
        val activeControl: PlaybackControl
        val activeWorker: Thread?
        val track: AudioTrack?
        synchronized(lifecycleLock) {
            activeControl = control ?: return
            activeControl.running.set(false)
            activeWorker = worker
            track = audioTrack
            inputQueue.clear()
        }

        runCatching { track?.pause() }
        activeWorker?.interrupt()
        if (activeWorker !== Thread.currentThread()) {
            runCatching { activeWorker?.join(STOP_JOIN_TIMEOUT_MS) }
        }
        synchronized(lifecycleLock) {
            if (control === activeControl) {
                control = null
                worker = null
            }
        }
    }

    override fun close() = stop()

    private fun playbackLoop(
        track: AudioTrack,
        playbackControl: PlaybackControl,
    ) {
        val talkers = mutableMapOf<Int, TalkerJitterPipeline>()
        var outputStarted = false
        var nextTickNanos = 0L
        try {
            while (playbackControl.running.get()) {
                if (resetRequested.getAndSet(false)) {
                    resetTalkers(talkers)
                    inputQueue.clear()
                    if (outputStarted) stopOutput(track)
                    outputStarted = false
                }

                if (!outputStarted) {
                    inputQueue.poll(IDLE_POLL_MS, TimeUnit.MILLISECONDS)?.let { first ->
                        drainInput(talkers, first, MAX_INPUT_DRAIN_PER_TICK)
                    }
                    applyDiscontinuities(talkers)
                    val now = System.nanoTime()
                    removeIdleTalkers(talkers, now)
                    if (talkers.values.any { it.readyToStart(now) }) {
                        if (startOutput(track)) {
                            outputStarted = true
                            nextTickNanos = now
                        } else {
                            resetTalkers(talkers)
                            inputQueue.clear()
                        }
                    }
                    continue
                }

                var drained = 0
                while (
                    playbackControl.running.get() &&
                    !resetRequested.get() &&
                    drained < MAX_INPUT_DRAIN_PER_TICK
                ) {
                    val remaining = nextTickNanos - System.nanoTime()
                    if (remaining <= 0L) break
                    val queued = inputQueue.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                    ingestFrame(talkers, queued)
                    drained++
                    drained +=
                        drainAvailableInput(
                            talkers,
                            MAX_INPUT_DRAIN_PER_TICK - drained,
                        )
                }

                if (resetRequested.get()) continue
                drained +=
                    drainAvailableInput(
                        talkers,
                        MAX_INPUT_DRAIN_PER_TICK - drained,
                    )
                applyDiscontinuities(talkers)

                val now = System.nanoTime()
                removeIdleTalkers(talkers, now)
                if (now - nextTickNanos > MAX_TICK_LAG_NANOS) {
                    nextTickNanos = now
                }

                val mixed = mixNextTick(talkers, now)
                if (mixed == null) {
                    talkers.values
                        .filter { it.isFinishedTalkspurt() }
                        .forEach(TalkerJitterPipeline::finishTalkspurt)
                    stopOutput(track)
                    outputStarted = false
                    continue
                }

                writeFully(track, mixed)
                nextTickNanos += MIX_TICK_NANOS
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            if (playbackControl.running.get()) {
                System.err.println(
                    "TS3_AUDIO: playback stopped: ${error.sanitizedFailureTypes()}",
                )
            }
        } finally {
            playbackControl.running.set(false)
            resetTalkers(talkers)
            inputQueue.clear()
            runCatching { stopOutput(track) }
            runCatching { track.release() }
            synchronized(lifecycleLock) {
                if (control === playbackControl) control = null
                if (audioTrack === track) audioTrack = null
                if (worker === Thread.currentThread()) worker = null
            }
        }
    }

    private fun drainInput(
        talkers: MutableMap<Int, TalkerJitterPipeline>,
        first: QueuedFrame,
        limit: Int,
    ) {
        ingestFrame(talkers, first)
        drainAvailableInput(talkers, limit - 1)
    }

    private fun drainAvailableInput(
        talkers: MutableMap<Int, TalkerJitterPipeline>,
        limit: Int,
    ): Int {
        var count = 0
        while (count < limit) {
            val queued = inputQueue.poll() ?: break
            ingestFrame(talkers, queued)
            count++
        }
        return count
    }

    private fun ingestFrame(
        talkers: MutableMap<Int, TalkerJitterPipeline>,
        queued: QueuedFrame,
    ) {
        val clientId = queued.frame.clientId
        if (loggedFirstFrame.compareAndSet(false, true)) {
            System.err.println(
                "TS3_AUDIO: received first Opus frame from client $clientId " +
                    "(${queued.frame.encodedData.size} bytes)",
            )
        }
        if (discontinuityClients.remove(clientId)) {
            talkers.remove(clientId)?.close()
        }
        talkers.getOrPut(clientId) { TalkerJitterPipeline(decodedPacketCount) }.offer(queued)
    }

    private fun applyDiscontinuities(talkers: MutableMap<Int, TalkerJitterPipeline>) {
        val iterator = discontinuityClients.iterator()
        while (iterator.hasNext()) {
            val clientId = iterator.next()
            talkers.remove(clientId)?.close()
            iterator.remove()
        }
    }

    private fun mixNextTick(
        talkers: MutableMap<Int, TalkerJitterPipeline>,
        now: Long,
    ): ShortArray? {
        val inputs = ArrayList<ParticipantGainMixer.Input>(talkers.size)
        val iterator = talkers.iterator()
        while (iterator.hasNext()) {
            val (clientId, talker) = iterator.next()
            try {
                talker.fillPcm(now)
                talker.readPcm(TalkerJitterPipeline.MIX_TICK_SAMPLES).takeIf { it.isNotEmpty() }?.let { samples ->
                    inputs +=
                        ParticipantGainMixer.Input(
                            samples = samples,
                            gain = participantGains[clientId] ?: DEFAULT_PARTICIPANT_GAIN,
                        )
                }
            } catch (error: RuntimeException) {
                System.err.println(
                    "TS3_AUDIO: dropping invalid Opus stream: ${error.sanitizedFailureTypes()}",
                )
                talker.close()
                iterator.remove()
            }
        }

        val output = ParticipantGainMixer.mix(inputs, TalkerJitterPipeline.MIX_TICK_SAMPLES)
        if (output.isEmpty()) return null
        if (loggedFirstPcm.compareAndSet(false, true)) {
            System.err.println(
                "TS3_AUDIO: decoded ${decodedPacketCount.get()} packet(s), " +
                    "nonZero=${output.any { it.toInt() != 0 }}, " +
                    "received=${receivedFrameCount.get()}",
            )
        }
        return output
    }

    private fun removeIdleTalkers(
        talkers: MutableMap<Int, TalkerJitterPipeline>,
        now: Long,
    ) {
        val iterator = talkers.iterator()
        while (iterator.hasNext()) {
            val talker = iterator.next().value
            if (now - talker.lastArrivalNanos > TALKER_IDLE_NANOS) {
                talker.close()
                iterator.remove()
            }
        }
    }

    private fun resetTalkers(talkers: MutableMap<Int, TalkerJitterPipeline>) {
        talkers.values.forEach(TalkerJitterPipeline::close)
        talkers.clear()
        discontinuityClients.clear()
    }

    private fun startOutput(track: AudioTrack): Boolean {
        val focusResult = audioManager.requestAudioFocus(focusRequest)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusAllowsPlayback.set(false)
            return false
        }
        focusAllowsPlayback.set(true)
        applyVolume()
        track.play()
        return true
    }

    private fun stopOutput(track: AudioTrack) {
        if (track.playState != AudioTrack.PLAYSTATE_STOPPED) {
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
        audioManager.abandonAudioFocusRequest(focusRequest)
        focusAllowsPlayback.set(false)
        applyVolume()
    }

    private fun writeFully(
        track: AudioTrack,
        samples: ShortArray,
    ) {
        var offset = 0
        while (offset < samples.size) {
            val written =
                track.write(
                    samples,
                    offset,
                    samples.size - offset,
                    AudioTrack.WRITE_BLOCKING,
                )
            if (written < 0) error("AudioTrack write failed: $written")
            if (written == 0) error("AudioTrack accepted no audio data")
            offset += written
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val minimumBytes =
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        check(minimumBytes > 0) { "Unable to determine audio buffer size: $minimumBytes" }
        return AudioTrack.Builder()
            .setAudioAttributes(voicePlaybackAudioAttributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minimumBytes, TARGET_AUDIO_TRACK_BUFFER_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .also { track ->
                preferredDevice?.let { device ->
                    if (!track.setPreferredDevice(device)) {
                        System.err.println("TS3_AUDIO: playback device preference was rejected")
                    }
                }
            }
    }

    private fun applyVolume() {
        val volume = if (userMuted.get() || !focusAllowsPlayback.get()) 0f else 1f
        runCatching { audioTrack?.setVolume(volume) }
    }

    private class PlaybackControl {
        val running = AtomicBoolean(true)
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val INPUT_QUEUE_CAPACITY = 256
        private const val MAX_INPUT_DRAIN_PER_TICK = 256
        private const val IDLE_POLL_MS = 10L
        private const val STOP_JOIN_TIMEOUT_MS = 1_000L
        private const val TARGET_AUDIO_TRACK_BUFFER_BYTES = TalkerJitterPipeline.MIX_TICK_SAMPLES * 2 * 4
        private const val MIN_PARTICIPANT_GAIN = 0f
        private const val DEFAULT_PARTICIPANT_GAIN = 1f
        private const val MAX_PARTICIPANT_GAIN = 2f
        private val MIX_TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(10)
        private val MAX_TICK_LAG_NANOS = TimeUnit.MILLISECONDS.toNanos(50)
        private val TALKER_IDLE_NANOS = TimeUnit.SECONDS.toNanos(2)

        internal fun signedPacketDistance(
            previous: Int,
            current: Int,
        ): Int = signedPacketDistance16(previous, current)
    }
}

internal fun voicePlaybackAudioAttributes(): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

package io.github.ts3mobile.audio.opus

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Looper
import io.github.ts3mobile.protocol.VoiceFrame
import io.github.ts3mobile.protocol.sanitizedFailureTypes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class OpusAudioPlayer internal constructor(
    context: Context,
    private val reference: PlaybackReferenceSink?,
    private val onRouteChanged: () -> Unit = {},
    private val onStatusChanged: (PlaybackStatus) -> Unit = {},
    private val outputFactory: (() -> PlaybackOutput)? = null,
) : AutoCloseable {
    constructor(context: Context) : this(context, null)
    constructor(context: Context, onRouteChanged: () -> Unit) : this(context, null, onRouteChanged)
    constructor(context: Context, onRouteChanged: () -> Unit, onStatusChanged: (PlaybackStatus) -> Unit) :
        this(context, null, onRouteChanged, onStatusChanged)

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val inputQueue = VoiceReceiveQueue()
    private val discontinuityClients = ConcurrentHashMap.newKeySet<Int>()
    private val participantGains = ConcurrentHashMap<Int, Float>()
    private val userMuted = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val admissionRevision = AtomicLong()

    @Volatile var status = PlaybackStatus.STOPPED
        private set

    @Volatile private var control: PlaybackControl? = null

    @Volatile private var preferredDevice: AudioDeviceInfo? = null
    private var selectedRouteId = SYSTEM_AUDIO_ROUTE_ID

    @Volatile private var worker: Thread? = null

    fun start() {
        synchronized(lifecycleLock) {
            if (control?.running?.get() == true) return
            check(worker?.isAlive != true) { "Previous playback worker has not stopped" }
            check(control?.session?.retired != false) { "Previous playback output has not been released" }
            admissionRevision.incrementAndGet()
            inputQueue.clear()
            discontinuityClients.clear()
            val active = PlaybackControl()
            control = active
            publishStatus(PlaybackStatus.BUFFERING)
            worker =
                Thread({ playbackLoop(active) }, "ts3-opus-playback").apply {
                    priority = Thread.MAX_PRIORITY
                    start()
                }
        }
    }

    fun submit(frame: VoiceFrame) {
        val expectedControl = control ?: return
        val revision = admissionRevision.get()
        if (!expectedControl.running.get() || userMuted.get()) return
        val queued = inputQueue.prepare(QueuedFrame(frame, System.nanoTime())) ?: return
        synchronized(lifecycleLock) {
            val active = control ?: return
            if (active !== expectedControl || admissionRevision.get() != revision) return
            if (!active.running.get() || userMuted.get() || status == PlaybackStatus.FAILED) return
            if ((participantGains[frame.clientId] ?: 1f) <= 0f) return
            inputQueue.offer(queued, System.nanoTime())
        }
    }

    fun setMuted(muted: Boolean) {
        synchronized(lifecycleLock) {
            userMuted.set(muted)
            admissionRevision.incrementAndGet()
            control?.let { active ->
                active.invalidate()
            }
        }
    }

    fun replaceParticipantGains(gains: Map<Int, Float>) {
        val normalized = gains.mapValues { (_, gain) -> gain.coerceIn(0f, 2f) }.filterValues { it != 1f }
        synchronized(lifecycleLock) {
            if (participantGains == normalized) return
            val previous = participantGains.toMap()
            participantGains.clear()
            participantGains.putAll(normalized)
            (previous.keys + normalized.keys).forEach { clientId ->
                val oldGain = previous[clientId] ?: 1f
                val newGain = normalized[clientId] ?: 1f
                if ((oldGain <= 0f) != (newGain <= 0f)) {
                    discontinuityClients += clientId
                    admissionRevision.incrementAndGet()
                    control?.invalidate()
                }
            }
            normalized.filterValues { it <= 0f }.keys.forEach(inputQueue::removeClient)
        }
    }

    fun setPreferredDevice(device: AudioDeviceInfo?) = setPreferredDevice(device, SYSTEM_AUDIO_ROUTE_ID)

    fun setPreferredDevice(
        device: AudioDeviceInfo?,
        routeId: Int,
    ) {
        synchronized(lifecycleLock) {
            if (preferredDevice?.id == device?.id && selectedRouteId == routeId) return
            preferredDevice = device
            selectedRouteId = routeId
            admissionRevision.incrementAndGet()
            control?.let { active ->
                active.invalidate()
            }
        }
    }

    fun stop() {
        val activeWorker =
            synchronized(lifecycleLock) {
                val active = control ?: return
                admissionRevision.incrementAndGet()
                active.running.set(false)
                if (worker?.isAlive != true && !active.session.retired) {
                    worker = Thread({ retire(active) }, "ts3-playback-retire").apply { start() }
                }
                worker
            }
        activeWorker?.interrupt()
        if (activeWorker !== Thread.currentThread() && Looper.myLooper() != Looper.getMainLooper()) {
            try {
                activeWorker?.join(STOP_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (activeWorker?.isAlive == true) {
                publishStatus(PlaybackStatus.FAILED)
                System.err.println("TS3_AUDIO: playback worker stop timed out; restart is blocked")
            }
        }
    }

    override fun close() = stop()

    private fun onOutputRouteChanged(output: AndroidPlaybackOutput) {
        var changed = false
        synchronized(lifecycleLock) {
            val active = control ?: return
            if (active.session.output === output && active.running.get()) {
                admissionRevision.incrementAndGet()
                active.invalidate()
                changed = true
            }
        }
        if (changed) onRouteChanged()
    }

    private fun playbackLoop(active: PlaybackControl) {
        val pacer = PlaybackPacer()
        var failed = false
        var appliedRevision = active.revision.get()
        try {
            System.err.println("TS3_AUDIO: playback codec=${NativeOpusInfo.version}")
            while (active.running.get()) {
                TimeUnit.NANOSECONDS.sleep(pacer.waitNanos(System.nanoTime()))
                val revision = active.revision.get()
                if (revision != appliedRevision) {
                    active.session.invalidate(System.nanoTime(), active.focusInterrupted.getAndSet(false))
                    appliedRevision = revision
                }
                active.writingRevision = revision
                if (active.running.get() && !userMuted.get()) active.session.tick(System.nanoTime())
                pacer.completed(System.nanoTime())
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            if (active.running.get()) {
                failed = true
                publishStatus(PlaybackStatus.FAILED)
                System.err.println("TS3_AUDIO: playback stopped: ${error.sanitizedFailureTypes()}")
            }
        } finally {
            retire(active, failed)
        }
    }

    private fun onFocusChange(
        active: PlaybackControl,
        change: Int,
    ) {
        synchronized(lifecycleLock) {
            if (control !== active || !active.running.get()) return
            if (AudioFocusPolicy.signalFor(change) == AudioFocusSignal.INTERRUPT) {
                admissionRevision.incrementAndGet()
                active.focusInterrupted.set(true)
                active.invalidate()
            }
        }
    }

    private inner class PlaybackControl {
        val running = AtomicBoolean(true)
        val revision = AtomicLong()
        val focusInterrupted = AtomicBoolean(false)
        var writingRevision = 0L

        fun invalidate() {
            revision.incrementAndGet()
            inputQueue.clear()
        }

        val focusRequest =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(voicePlaybackAudioAttributes())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { change -> onFocusChange(this, change) }
                .build()
        val session =
            PlaybackSession(
                inputQueue,
                PlaybackStreams(inputQueue, discontinuityClients, participantGains),
                { outputFactory?.invoke() ?: AndroidPlaybackOutput(preferredDevice, ::onOutputRouteChanged) },
                { audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED },
                { audioManager.abandonAudioFocusRequest(focusRequest) },
                reference,
                { publishStatus(it) },
                { running.get() && !userMuted.get() && revision.get() == writingRevision },
            )
    }

    private fun retire(
        active: PlaybackControl,
        failed: Boolean = false,
    ) {
        active.running.set(false)
        try {
            active.session.close()
        } finally {
            synchronized(lifecycleLock) {
                if (control === active && failed) publishStatus(PlaybackStatus.FAILED)
                if (control === active && active.session.retired) control = null
                if (worker === Thread.currentThread()) worker = null
            }
        }
    }

    private fun publishStatus(updated: PlaybackStatus) {
        if (status == updated) return
        status = updated
        onStatusChanged(updated)
    }

    private companion object {
        const val STOP_JOIN_TIMEOUT_MS = 1_000L
    }
}

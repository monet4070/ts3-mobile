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

class OpusAudioPlayer internal constructor(
    context: Context,
    private val reference: PlaybackReferenceSink?,
    private val onRouteChanged: () -> Unit = {},
    private val onStatusChanged: (PlaybackStatus) -> Unit = {},
    private val outputFactory: (() -> PlaybackOutput)? = null,
    private val onUnsafeRoute: () -> Unit = {},
    private val prepareOutputRoute: () -> Unit = {},
) : AutoCloseable {
    constructor(context: Context) : this(context, null)
    constructor(context: Context, onRouteChanged: () -> Unit) : this(context, null, onRouteChanged)
    constructor(context: Context, onRouteChanged: () -> Unit, onStatusChanged: (PlaybackStatus) -> Unit) :
        this(context, null, onRouteChanged, onStatusChanged)
    constructor(context: Context, onRouteChanged: () -> Unit, onStatusChanged: (PlaybackStatus) -> Unit, onUnsafeRoute: () -> Unit) :
        this(context, null, onRouteChanged, onStatusChanged, onUnsafeRoute = onUnsafeRoute)
    constructor(
        context: Context,
        onRouteChanged: () -> Unit,
        onStatusChanged: (PlaybackStatus) -> Unit,
        onUnsafeRoute: () -> Unit,
        prepareOutputRoute: () -> Unit,
    ) : this(context, null, onRouteChanged, onStatusChanged, onUnsafeRoute = onUnsafeRoute, prepareOutputRoute = prepareOutputRoute)

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val inputQueue = VoiceReceiveQueue()
    private val receiveDiagnostics = PlaybackDiagnostics(inputQueue.budget.stats)
    private val participantGains = ConcurrentHashMap<Int, Float>()
    private val userMuted = AtomicBoolean(false)
    private val routeGuard = BluetoothPlaybackGuard()

    @Volatile private var routeSwitching = false
    private val lifecycleLock = Any()
    private val admission = PlaybackAdmission()

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
            admission.invalidateAll()
            inputQueue.clear()
            receiveDiagnostics.begin()
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

    /** One diagnostics collector owns interval peaks; no payload or device identifier is exposed. */
    fun takeReceiveDiagnostics(): AudioReceiveDiagnostics {
        control?.session?.output?.pollDiagnostics()
        return receiveDiagnostics.take(true)
    }

    fun setDiagnosticsObserver(observer: AudioDiagnosticsObserver?) {
        inputQueue.budget.stats.observer = observer
    }

    fun submit(frame: VoiceFrame) {
        inputQueue.budget.stats.count(AudioReceiveCounter.SUBMITTED)
        val expectedControl = control ?: return
        if (!expectedControl.running.get() || userMuted.get() || routeSwitching) return
        admission.withTicket(frame.clientId) { ticket ->
            val queued = inputQueue.prepare(QueuedFrame(frame, System.nanoTime())) ?: return@withTicket
            synchronized(lifecycleLock) {
                val active = control ?: return@synchronized
                if (active !== expectedControl || !admission.accepts(ticket)) return@synchronized
                if (!active.running.get() || userMuted.get() || routeSwitching || status == PlaybackStatus.FAILED) return@synchronized
                if ((participantGains[frame.clientId] ?: 1f) <= 0f) return@synchronized
                inputQueue.offer(queued, System.nanoTime())
            }
        }
    }

    fun setMuted(muted: Boolean) {
        synchronized(lifecycleLock) {
            userMuted.set(muted)
            if (!muted) routeGuard.acknowledgeSafetyMute()
            admission.invalidateAll()
            control?.let { active ->
                active.session.output?.muteImmediately()
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
            val affected =
                (previous.keys + normalized.keys).filterTo(mutableSetOf()) { clientId ->
                    ((previous[clientId] ?: 1f) <= 0f) != ((normalized[clientId] ?: 1f) <= 0f)
                }
            if (affected.isNotEmpty()) {
                inputQueue.budget.stats.count(AudioReceiveCounter.PARTICIPANT_INVALIDATIONS)
                admission.invalidateParticipants(affected)
                affected.forEach(inputQueue::removeClient)
                control?.invalidation?.invalidateParticipants(affected)
            }
        }
    }

    fun setPreferredDevice(device: AudioDeviceInfo?) = setPreferredDevice(device, SYSTEM_AUDIO_ROUTE_ID)

    fun setPreferredDevice(
        device: AudioDeviceInfo?,
        routeId: Int,
    ) = updateRouting(AudioRoutingState(selectedRouteId = routeId), device)

    fun updateRouting(
        routing: AudioRoutingState,
        device: AudioDeviceInfo?,
    ) {
        synchronized(lifecycleLock) {
            val requireBluetooth =
                routing.selectedRoute.kind == AudioRouteKind.BLUETOOTH ||
                    device?.let { AudioRouteDevices.routeKindForDeviceType(it.type) } == AudioRouteKind.BLUETOOTH
            val guardChanged = routeGuard.select(routing.selectedRouteId, requireBluetooth)
            if (requireBluetooth && routing.switching && !routeSwitching) routeGuard.beginBluetoothAttempt()
            if (preferredDevice?.id == device?.id && selectedRouteId == routing.selectedRouteId &&
                !guardChanged && routeSwitching == routing.switching
            ) {
                return
            }
            preferredDevice = device
            selectedRouteId = routing.selectedRouteId
            routeSwitching = routing.switching
            admission.invalidateAll()
            control?.let { active ->
                active.session.output?.muteImmediately()
                active.invalidate()
            }
        }
    }

    fun stop() {
        val activeWorker =
            synchronized(lifecycleLock) {
                val active = control ?: return
                admission.invalidateAll()
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
                admission.invalidateAll()
                active.invalidate()
                inputQueue.budget.stats.count(AudioReceiveCounter.ROUTE_CALLBACKS)
                changed = true
            }
        }
        if (changed) onRouteChanged()
    }

    private fun onUnsafeOutputRoute(output: AndroidPlaybackOutput) {
        synchronized(lifecycleLock) {
            val active = control ?: return
            if (active.session.output !== output || !active.running.get() || userMuted.get()) return
            userMuted.set(true)
            output.muteImmediately()
            admission.invalidateAll()
            active.invalidate()
        }
        onUnsafeRoute()
    }

    private fun playbackLoop(active: PlaybackControl) {
        val pacer = PlaybackPacer()
        var failed = false
        var previousTick: Long? = null
        try {
            System.err.println("TS3_AUDIO: playback codec=${NativeOpusInfo.version}")
            while (active.running.get()) {
                TimeUnit.NANOSECONDS.sleep(pacer.waitNanos(System.nanoTime()))
                val tick = System.nanoTime()
                previousTick?.let { previous ->
                    val gap = (tick - previous).coerceAtLeast(0)
                    inputQueue.budget.stats.peak(AudioReceivePeak.WORKER_GAP_NS, gap)
                    if (gap > 30_000_000L) inputQueue.budget.stats.count(AudioReceiveCounter.WORKER_GAPS_OVER_30_MS)
                }
                previousTick = tick
                active.invalidation.take()?.let { command ->
                    if (command.global) {
                        active.session.invalidate(System.nanoTime(), command.focusInterrupted)
                    } else {
                        active.session.invalidateParticipants(command.clients, System.nanoTime())
                    }
                    active.writingRevision = command.revision
                }
                if (active.running.get() && !userMuted.get() && !routeSwitching) active.session.tick(System.nanoTime())
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
                inputQueue.budget.stats.count(AudioReceiveCounter.FOCUS_INTERRUPTS)
                inputQueue.budget.stats.observer?.onEvent(AudioDiagnosticEventKind.FOCUS_INTERRUPTED)
                admission.invalidateAll()
                active.invalidate(focusInterrupted = true)
            }
        }
    }

    private inner class PlaybackControl {
        val running = AtomicBoolean(true)
        val invalidation = PlaybackInvalidation()
        var writingRevision = 0L

        fun invalidate(focusInterrupted: Boolean = false) {
            inputQueue.budget.stats.count(AudioReceiveCounter.GLOBAL_INVALIDATIONS)
            invalidation.invalidateAll(focusInterrupted)
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
                PlaybackStreams(inputQueue, mutableSetOf(), participantGains),
                {
                    outputFactory?.invoke()
                        ?: AndroidPlaybackOutput(
                            preferredDevice, ::onOutputRouteChanged, routeGuard, ::onUnsafeOutputRoute, inputQueue.budget.stats,
                        )
                },
                {
                    val granted = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    if (granted) {
                        inputQueue.budget.stats.observer?.onEvent(AudioDiagnosticEventKind.FOCUS_GAINED)
                        prepareOutputRoute()
                    }
                    granted
                },
                { audioManager.abandonAudioFocusRequest(focusRequest) },
                reference,
                { publishStatus(it) },
                { running.get() && !userMuted.get() && !routeSwitching && invalidation.permitsWrite(writingRevision) },
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
            receiveDiagnostics.end()
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

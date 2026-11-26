package io.github.ts3mobile.audio.opus

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.max

internal class AndroidPlaybackOutput(
    preferredDevice: AudioDeviceInfo?,
    onRoutingChanged: (AndroidPlaybackOutput) -> Unit,
    private val routeGuard: BluetoothPlaybackGuard,
    private val onUnsafeRoute: (AndroidPlaybackOutput) -> Unit,
    private val stats: ReceiveStats? = null,
    standardBluetoothExperiment: Boolean = false,
) : PlaybackOutput {
    private val track: AudioTrack
    private val routingListener: AudioRouting.OnRoutingChangedListener
    private val outputLock = Any()
    private val routeController: PlaybackRouteController
    private var reportedUnderruns = 0
    private var performanceModeObserved = false

    @Volatile
    private var routedDeviceId: Int? = null

    override val routedKind: AudioRouteKind? get() = routeController.routedKind

    init {
        val minimumBytes =
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        check(minimumBytes > 0) { "Unable to determine audio buffer size: $minimumBytes" }
        val performanceMode = playbackPerformanceMode(routeGuard.expectsBluetooth(), standardBluetoothExperiment)
        track =
            AudioTrack.Builder()
                .setAudioAttributes(voicePlaybackAudioAttributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(max(minimumBytes, TalkerJitterPipeline.MIX_TICK_SAMPLES * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(performanceMode)
                .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("Unable to initialize audio output")
        }
        routeController =
            PlaybackRouteController(
                routeGuard = routeGuard,
                lock = outputLock,
                clockMs = SystemClock::elapsedRealtime,
                readRoutedDeviceType = { track.routedDevice?.type },
                setTrackVolume = { volume -> track.setVolume(volume) },
                onUnsafeRoute = { onUnsafeRoute(this) },
                stats = stats,
                onAllowedRouteObserved = { observePerformanceMode() },
            )
        try {
            setPreferredDevice(preferredDevice)
            routeController.initializeVolume()
            routingListener =
                AudioRouting.OnRoutingChangedListener { routing ->
                    routeController.onRoutingChanged()
                    val deviceId = routing.routedDevice?.id
                    if (deviceId != null) {
                        val previous = routedDeviceId
                        routedDeviceId = deviceId
                        if (previous != null && previous != deviceId) onRoutingChanged(this)
                    }
                }
            track.addOnRoutingChangedListener(routingListener, Handler(Looper.getMainLooper()))
        } catch (error: RuntimeException) {
            track.release()
            throw error
        }
    }

    override val playbackHead: Long get() = track.playbackHeadPosition.toLong() and 0xffff_ffffL

    override fun sampleClock(): AudioSampleClock? = AndroidAudioClocks.playback(track)

    override fun pollDiagnostics() =
        synchronized(outputLock) {
            pollDiagnosticsLocked()
        }

    private fun pollDiagnosticsLocked() {
        if (!routeController.isClosed && stats != null) {
            reportedUnderruns = stats.measureUnderruns(reportedUnderruns) { track.underrunCount }
        }
    }

    override fun start() = track.play()

    override val routeReady: Boolean get() = routeController.isRouteReady()

    override fun muteImmediately() = routeController.muteImmediately()

    override fun write(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ): Int =
        routeController.writeGuarded {
            track.write(samples, offset, count, AudioTrack.WRITE_NON_BLOCKING)
        }

    private fun observePerformanceMode() {
        if (performanceModeObserved) return
        performanceModeObserved = true
        val diagnostics = stats ?: return
        val mode =
            try {
                track.performanceMode
            } catch (_: IllegalStateException) {
                diagnostics.count(AudioReceiveCounter.DIAGNOSTIC_READ_FAILURES)
                return
            }
        diagnostics.observer?.onEvent(AudioDiagnosticEventKind.OUTPUT_PERFORMANCE_MODE, mode.toLong())
    }

    override fun discard() =
        routeController.discard {
            track.pause()
            track.flush()
            routedDeviceId = null
        }

    fun setPreferredDevice(device: AudioDeviceInfo?) {
        synchronized(outputLock) {
            routeController.invalidateForPreferredDevice()
            if (!track.setPreferredDevice(device)) {
                System.err.println("TS3_AUDIO: playback device preference was rejected")
            }
        }
    }

    override fun close() =
        routeController.close(
            beforeRelease = { pollDiagnosticsLocked() },
            release = {
                try {
                    track.removeOnRoutingChangedListener(routingListener)
                } finally {
                    track.release()
                }
            },
        )

    private companion object {
        const val SAMPLE_RATE = 48_000
    }
}

/**
 * Owns output route validation, bluetooth wait/safety mute enforcement, volume deduplication,
 * and steady-state polling throttling shared cleanly across [PlaybackOutput.routeReady] and writes.
 */
internal class PlaybackRouteController(
    private val routeGuard: BluetoothPlaybackGuard,
    private val routeRevision: Long = routeGuard.revision(),
    private val lock: Any = Any(),
    private val clockMs: () -> Long,
    private val readRoutedDeviceType: () -> Int?,
    private val setTrackVolume: (Float) -> Unit,
    private val onUnsafeRoute: () -> Unit,
    private val stats: ReceiveStats? = null,
    private val onAllowedRouteObserved: (AudioRouteKind) -> Unit = {},
    private val routeThrottle: PlaybackRouteCheckThrottle = PlaybackRouteCheckThrottle(),
) {
    private var silenced = false

    @Volatile
    var isClosed = false
        private set
    private var appliedVolume = Float.NaN
    private var bluetoothWaitStartedAt: Long? = null

    @Volatile
    var routedKind: AudioRouteKind? = null
        private set

    fun initializeVolume() {
        synchronized(lock) { applyVolumeLocked(0f) }
    }

    fun isRouteReady(): Boolean = checkRoute(forceCheck = false)

    fun onRoutingChanged(): Boolean {
        synchronized(lock) { routeThrottle.invalidate() }
        return checkRoute(forceCheck = true)
    }

    fun invalidateForPreferredDevice() {
        synchronized(lock) { routeThrottle.invalidate() }
    }

    fun muteImmediately() {
        synchronized(lock) {
            if (isClosed) return
            silenced = true
            routeThrottle.invalidate()
            applyVolumeLocked(0f)
            stats?.confirmOutputDevice(null)
        }
    }

    fun writeGuarded(writeSamples: () -> Int): Int {
        var unsafeRoute = false
        val written =
            synchronized(lock) {
                val decision = evaluateIfDueLocked(clockMs(), forceCheck = false)
                if (decision == PlaybackRouteDecision.ALLOW) {
                    writeSamples()
                } else {
                    unsafeRoute = decision == PlaybackRouteDecision.MUTE
                    0
                }
            }
        if (unsafeRoute) onUnsafeRoute()
        return written
    }

    fun discard(afterMute: () -> Unit) {
        synchronized(lock) {
            if (isClosed) return
            silenced = true
            routeThrottle.invalidate()
            applyVolumeLocked(0f)
            afterMute()
            stats?.confirmOutputDevice(null)
        }
    }

    fun close(
        beforeRelease: () -> Unit,
        release: () -> Unit,
    ) {
        synchronized(lock) {
            if (isClosed) return
            silenced = true
            routeThrottle.invalidate()
            beforeRelease()
            try {
                applyVolumeLocked(0f)
            } finally {
                release()
                isClosed = true
                routedKind = null
                stats?.observeRoute(null)
                stats?.confirmOutputDevice(null)
            }
        }
    }

    private fun checkRoute(forceCheck: Boolean): Boolean {
        val decision =
            synchronized(lock) {
                evaluateIfDueLocked(clockMs(), forceCheck = forceCheck)
            }
        if (decision == PlaybackRouteDecision.MUTE) onUnsafeRoute()
        return decision == PlaybackRouteDecision.ALLOW
    }

    private fun evaluateIfDueLocked(
        nowMs: Long,
        forceCheck: Boolean,
    ): PlaybackRouteDecision {
        if (isClosed || silenced) return PlaybackRouteDecision.WAIT_FOR_BLUETOOTH
        val requireLiveCheck = routeGuard.requiresLiveRouteCheck()
        if (!forceCheck && !requireLiveCheck && routeGuard.revision() == routeRevision && !routeThrottle.shouldCheck(nowMs)) {
            return PlaybackRouteDecision.ALLOW
        }
        val deviceType = readRoutedDeviceType()
        val kind = deviceType?.let(AudioRouteDevices::routeKindForDeviceType)
        routedKind = kind
        stats?.observeRoute(kind)
        val waitStarted = bluetoothWaitStartedAt
        val timedOut = waitStarted != null && nowMs - waitStarted >= BLUETOOTH_ROUTE_TIMEOUT_MS
        return routeGuard.evaluate(kind, routeRevision, timedOut).also { decision ->
            val allowed = decision == PlaybackRouteDecision.ALLOW
            if (decision == PlaybackRouteDecision.MUTE) silenced = true
            if (routeGuard.requiresLiveRouteCheck()) {
                routeThrottle.invalidate()
            } else {
                routeThrottle.record(allowed, nowMs)
            }
            bluetoothWaitStartedAt = if (decision == PlaybackRouteDecision.WAIT_FOR_BLUETOOTH) waitStarted ?: nowMs else null
            applyVolumeLocked(if (allowed) 1f else 0f)
            stats?.confirmOutputDevice(if (allowed) actualAudioDevice(deviceType) else null)
            if (allowed && kind != null) onAllowedRouteObserved(kind)
        }
    }

    private fun applyVolumeLocked(targetVolume: Float) {
        if (appliedVolume == targetVolume) return
        setTrackVolume(targetVolume)
        appliedVolume = targetVolume
    }

    private companion object {
        const val BLUETOOTH_ROUTE_TIMEOUT_MS = 5_000L
    }
}

/**
 * Limits steady-state synchronous `track.routedDevice` polling on hot playback writes
 * while still verifying immediately on startup, invalidation, or unsafe/pending routes.
 */
internal class PlaybackRouteCheckThrottle(
    private val intervalMs: Long = STEADY_ROUTE_CHECK_INTERVAL_MS,
) {
    private var dirty = true
    private var lastAllowed = false
    private var lastCheckedAtMs = Long.MIN_VALUE

    fun shouldCheck(nowMs: Long): Boolean =
        dirty || !lastAllowed || lastCheckedAtMs == Long.MIN_VALUE || nowMs - lastCheckedAtMs >= intervalMs

    fun record(
        allowed: Boolean,
        nowMs: Long,
    ) {
        lastAllowed = allowed
        lastCheckedAtMs = nowMs
        dirty = !allowed
    }

    fun invalidate() {
        dirty = true
        lastAllowed = false
    }

    companion object {
        const val STEADY_ROUTE_CHECK_INTERVAL_MS = 250L
    }
}

internal fun voicePlaybackAudioAttributes(): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        }
        .build()

internal fun playbackPerformanceMode(
    bluetoothExpected: Boolean,
    standardBluetoothExperiment: Boolean,
): Int = if (bluetoothExpected && standardBluetoothExperiment) AudioTrack.PERFORMANCE_MODE_NONE else AudioTrack.PERFORMANCE_MODE_LOW_LATENCY

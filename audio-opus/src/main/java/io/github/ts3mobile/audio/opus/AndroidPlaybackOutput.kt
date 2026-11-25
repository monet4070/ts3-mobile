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
    private val routeRevision = routeGuard.revision()
    private var silenced = false
    private var closed = false
    private var reportedUnderruns = 0
    private var performanceModeObserved = false
    private var bluetoothWaitStartedAt: Long? = null

    @Volatile
    private var routedDeviceId: Int? = null

    @Volatile
    override var routedKind: AudioRouteKind? = null
        private set

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
        try {
            setPreferredDevice(preferredDevice)
            track.setVolume(0f)
            routingListener =
                AudioRouting.OnRoutingChangedListener { routing ->
                    checkRoute()
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
            if (!closed && stats != null) {
                reportedUnderruns = stats.measureUnderruns(reportedUnderruns) { track.underrunCount }
            }
        }

    override fun start() = track.play()

    override val routeReady: Boolean get() = checkRoute()

    override fun muteImmediately() =
        synchronized(outputLock) {
            if (closed) return
            silenced = true
            track.setVolume(0f)
            stats?.confirmOutputDevice(null)
            Unit
        }

    override fun write(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ): Int {
        if (!checkRoute()) return 0
        return synchronized(outputLock) {
            if (silenced || closed || routeGuard.revision() != routeRevision) {
                0
            } else {
                track.write(samples, offset, count, AudioTrack.WRITE_NON_BLOCKING)
            }
        }
    }

    private fun checkRoute(): Boolean {
        val decision =
            synchronized(outputLock) {
                if (closed || silenced) return false
                val device = track.routedDevice
                val kind = device?.let { AudioRouteDevices.routeKindForDeviceType(it.type) }
                routedKind = kind
                stats?.observeRoute(kind)
                val now = SystemClock.elapsedRealtime()
                val waitStarted = bluetoothWaitStartedAt
                val timedOut = waitStarted != null && now - waitStarted >= BLUETOOTH_ROUTE_TIMEOUT_MS
                routeGuard.evaluate(kind, routeRevision, timedOut).also {
                    bluetoothWaitStartedAt = if (it == PlaybackRouteDecision.WAIT_FOR_BLUETOOTH) waitStarted ?: now else null
                    track.setVolume(if (it == PlaybackRouteDecision.ALLOW) 1f else 0f)
                    stats?.confirmOutputDevice(if (it == PlaybackRouteDecision.ALLOW) actualAudioDevice(device?.type) else null)
                    if (it == PlaybackRouteDecision.ALLOW && kind != null) observePerformanceMode()
                }
            }
        if (decision == PlaybackRouteDecision.MUTE) onUnsafeRoute(this)
        return decision == PlaybackRouteDecision.ALLOW
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
        synchronized(outputLock) {
            if (closed) return
            silenced = true
            track.setVolume(0f)
            track.pause()
            track.flush()
            routedDeviceId = null
            stats?.confirmOutputDevice(null)
            Unit
        }

    fun setPreferredDevice(device: AudioDeviceInfo?) {
        if (!track.setPreferredDevice(device)) {
            System.err.println("TS3_AUDIO: playback device preference was rejected")
        }
    }

    override fun close() =
        synchronized(outputLock) {
            if (closed) return
            silenced = true
            pollDiagnostics()
            try {
                track.setVolume(0f)
                track.removeOnRoutingChangedListener(routingListener)
            } finally {
                track.release()
                closed = true
                routedKind = null
                stats?.observeRoute(null)
                stats?.confirmOutputDevice(null)
            }
        }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val BLUETOOTH_ROUTE_TIMEOUT_MS = 5_000L
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

package io.github.ts3mobile.audio.opus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.AudioRouting
import android.os.Build
import android.os.Process
import io.github.ts3mobile.protocol.TimedEncodedVoiceFrame
import io.github.ts3mobile.protocol.TimedEncodedVoiceSource
import io.github.ts3mobile.protocol.sanitizedFailureTypes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class OpusMicrophoneCapture private constructor(
    context: Context,
    private val onFailure: (Throwable) -> Unit,
    private val onStateChanged: (MicrophoneCaptureState) -> Unit,
    private val encodeAudio: Boolean,
) : MicrophoneCapture, TimedEncodedVoiceSource {
    constructor(
        context: Context,
        onFailure: (Throwable) -> Unit,
        onStateChanged: (MicrophoneCaptureState) -> Unit,
    ) : this(context, onFailure, onStateChanged, encodeAudio = true)

    constructor(context: Context, onFailure: (Throwable) -> Unit = {}) : this(context, onFailure, {})

    private val applicationContext = context.applicationContext
    private val lifecycleLock = Any()
    private var options = MicrophoneProcessingOptions()
    private val captureGeneration = AtomicLong(0)
    private val captureOverflow = AtomicLong(0)
    private val levelMeter = MicrophoneLevelMeter()
    override val generation: Long get() = captureGeneration.get()

    override fun observeLevels(): MicrophoneLevelSubscription = levelMeter.subscribe()

    @Volatile
    private var control: CaptureControl? = null

    @Volatile
    private var resources: CaptureResources? = null

    @Volatile
    private var preferredDevice: AudioDeviceInfo? = null

    @Volatile private var bluetoothCallInput = false

    @Volatile
    private var worker: Thread? = null

    override val isCapturing: Boolean
        get() = control?.running?.get() == true

    val actualInputType: Int? get() = control?.takeIf { it.running.get() }?.routedDeviceType

    val isInputSilenced: Boolean
        get() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
            return synchronized(lifecycleLock) {
                if (control?.running?.get() != true) return@synchronized false
                val active = resources?.takeIf { !it.released } ?: return@synchronized false
                runCatching { active.record.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)
            }
        }

    override fun configure(
        settings: MicrophoneSettings,
        volumeGate: Boolean,
    ) {
        synchronized(lifecycleLock) {
            val updated = MicrophoneProcessingOptions(settings, volumeGate)
            if (updated == options) return
            options = updated
            control?.frames?.reset()
            control?.let { current ->
                activateLevels(current)
                publishState(current)
            }
        }
    }

    override fun setPreferredDevice(device: AudioDeviceInfo?) = setInputRoute(device, bluetoothCall = false)

    override fun setInputRoute(
        device: AudioDeviceInfo?,
        bluetoothCall: Boolean,
    ) {
        synchronized(lifecycleLock) {
            val deviceChanged = preferredDevice?.id != device?.id
            if (!deviceChanged && bluetoothCallInput == bluetoothCall) return
            bluetoothCallInput = bluetoothCall
            preferredDevice = device
            invalidateRouting()
            if (!deviceChanged) return
            resources?.record?.let { record ->
                runCatching {
                    check(record.setPreferredDevice(device)) { "Microphone device preference was rejected" }
                }
                    .onFailure { error ->
                        System.err.println(
                            "TS3_AUDIO: failed to route microphone: ${error.sanitizedFailureTypes()}",
                        )
                    }
            }
        }
    }

    override fun invalidateRouting() {
        synchronized(lifecycleLock) {
            control?.let { current ->
                current.frames.reset()
                current.routeRevision.incrementAndGet()
                activateLevels(current)
                current.echoState = EchoCancellationState.PENDING
                publishState(current)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        synchronized(lifecycleLock) {
            if (control?.running?.get() == true) return
            check(isReleased) { "Previous capture resources have not stopped" }
            check(
                applicationContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
            ) { "Microphone permission is not granted" }

            val captureResources = CaptureResources.create(preferredDevice, options.settings, encodeAudio)
            val newControl = CaptureControl(captureGeneration.incrementAndGet())
            newControl.routingListener =
                AudioRouting.OnRoutingChangedListener { routing ->
                    synchronized(lifecycleLock) {
                        if (control !== newControl || !newControl.running.get()) return@synchronized
                        val device = routing.routedDevice
                        newControl.routedDeviceType = device?.type
                        val deviceId = device?.id
                        if (newControl.routedDeviceId != deviceId) {
                            newControl.routedDeviceId = deviceId
                            invalidateRouting()
                        }
                    }
                }
            control = newControl
            resources = captureResources
            activateLevels(newControl)
            try {
                captureResources.startRecording()
                captureResources.listen(newControl.routingListener)
                newControl.routedDeviceType = runCatching { captureResources.record.routedDevice?.type }.getOrNull()
            } catch (error: RuntimeException) {
                newControl.running.set(false)
                levelMeter.stop(newControl.generation)
                captureResources.close()
                if (captureResources.released) {
                    control = null
                    resources = null
                }
                throw error
            }
            newControl.echoState = captureResources.effects.echoState
            publishState(newControl)
            worker =
                Thread(
                    { captureLoop(captureResources, newControl) },
                    "ts3-opus-capture",
                ).apply { start() }
        }
    }

    override val isReleased: Boolean get() = resources?.released != false && worker?.isAlive != true

    override fun requestStop() {
        control?.let { current ->
            current.running.set(false)
            levelMeter.stop(current.generation)
            current.frames.stop()
        }
        worker?.interrupt()
    }

    override fun stop() {
        requestStop()
        val activeResources = resources
        activeResources?.stopRecording()
        val activeWorker = worker
        if (activeWorker?.isAlive != true && activeResources?.released == false) {
            activeResources.close()
            control?.let(::publishState)
        }
        if (activeWorker !== Thread.currentThread()) {
            try {
                activeWorker?.join(STOP_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        check(isReleased) { "Capture resources have not stopped; restart is blocked" }
    }

    override val isTransmitting: Boolean get() = control?.frames?.isTransmitting() == true

    override fun isReady(): Boolean = control?.frames?.isReady() == true

    fun takeCaptureOverflow(): Long = captureOverflow.getAndSet(0)

    override fun pollTimedEncodedFrame(): TimedEncodedVoiceFrame? {
        val current = control ?: return null
        val frame = current.frames.pollTimed()
        if (frame != null) current.provided.incrementAndGet()
        publishState(current)
        return frame
    }

    override fun close() = stop()

    private fun activateLevels(current: CaptureControl) {
        levelMeter.activate(current.generation, current.frames.revision(), current.routeRevision.get()) { current.running.get() }
    }

    private fun captureLoop(
        captureResources: CaptureResources,
        captureControl: CaptureControl,
    ) {
        val record = captureResources.record
        val encoder = captureResources.encoder
        val denoiser = captureResources.denoiser
        val audioEffects = captureResources.effects
        val stats = CaptureSessionStats()
        val inputPcm = ShortArray(RNNOISE_FRAME_SAMPLES)
        val assembler = CaptureInputAssembler()
        val encodedTimestamps = ArrayList<Long>(CaptureVoiceBuffer.CAPACITY)
        val pipeline =
            MicrophoneCapturePipeline(denoiser::processInPlace, encoder?.let { it::encode }, stats) { settings ->
                denoiser.reset()
                encoder?.reset()
                audioEffects.configure(settings.echoCancellation)
                captureControl.echoState = audioEffects.echoState
            }
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            while (captureControl.running.get()) {
                val (current, revision) =
                    synchronized(lifecycleLock) {
                        options to captureControl.frames.revision()
                    }
                val routeRevision = captureControl.routeRevision.get()
                assembler.prepare(revision)
                val read =
                    record.read(
                        inputPcm,
                        0,
                        assembler.remaining,
                        AudioRecord.READ_BLOCKING,
                    )
                if (!captureControl.running.get()) break
                if (read < 0) error("AudioRecord read failed: $read")
                if (read == 0) continue
                if (revision != captureControl.frames.revision()) continue
                if (bluetoothCallInput && record.routedDevice?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) continue
                val denoiserPcm = assembler.append(inputPcm, read) ?: continue
                encodedTimestamps.clear()
                val encoded = pipeline.process(denoiserPcm, current, revision, routeRevision, levelMeter.isObserved(), encodedTimestamps)
                if (encoded == null) continue
                if (encodeAudio) {
                    val dropped = captureControl.frames.publish(revision, encoded, pipeline.isSpeaking, encodedTimestamps)
                    if (dropped != 0) captureOverflow.addAndGet(dropped.toLong())
                }
                if (pipeline.levelDb.isFinite()) {
                    levelMeter.offer(
                        captureControl.generation,
                        revision,
                        routeRevision,
                        pipeline.levelDb,
                        current.volumeGate,
                        pipeline.isSpeaking,
                        captureControl.frames.isTransmitting(),
                        current.settings.gateThresholdDb,
                    )
                }
                captureControl.encoded.addAndGet(encoded.size.toLong())
                publishState(captureControl)
            }
        } catch (error: Throwable) {
            if (captureControl.running.get()) {
                System.err.println(
                    "TS3_AUDIO: microphone capture stopped: ${error.sanitizedFailureTypes()}",
                )
                synchronized(lifecycleLock) {
                    if (control === captureControl && captureControl.running.get()) onFailure(error)
                }
            }
        } finally {
            captureControl.running.set(false)
            levelMeter.stop(captureControl.generation)
            captureControl.frames.stop()
            val encodedCount = captureControl.encoded.get()
            if (encodedCount > 0L) {
                System.err.println(
                    stats.summaryLine(encodedCount, captureControl.provided.get()),
                )
            }
            captureResources.close()
            publishState(captureControl)
            synchronized(lifecycleLock) {
                if (captureResources.released) {
                    if (control === captureControl) control = null
                    if (resources === captureResources) resources = null
                }
                if (worker === Thread.currentThread()) worker = null
            }
        }
    }

    private fun publishState(current: CaptureControl) {
        if (control !== current) return
        val running = current.running.get()
        val capturing = resources?.released == false
        val transmitting = running && current.frames.isTransmitting()
        val echo = if (running) current.echoState else EchoCancellationState.PENDING
        val deviceType = if (running) current.routedDeviceType else null
        val previous = current.lastState
        if (previous != null &&
            previous.capturing == capturing &&
            previous.transmitting == transmitting &&
            previous.echoCancellation == echo &&
            previous.recording == running &&
            current.lastPublishedDeviceType == deviceType
        ) {
            return
        }
        synchronized(lifecycleLock) {
            if (control !== current) return
            val lockedRunning = current.running.get()
            val lockedDeviceType = if (lockedRunning) current.routedDeviceType else null
            val snapshot =
                MicrophoneCaptureState(
                    capturing = resources?.released == false,
                    transmitting = lockedRunning && current.frames.isTransmitting(),
                    echoCancellation = if (lockedRunning) current.echoState else EchoCancellationState.PENDING,
                    recording = lockedRunning,
                    actualInputDevice = actualAudioDevice(lockedDeviceType),
                )
            if (snapshot == current.lastState) {
                current.lastPublishedDeviceType = lockedDeviceType
                return
            }
            current.lastState = snapshot
            current.lastPublishedDeviceType = lockedDeviceType
            onStateChanged(snapshot)
        }
    }

    private class CaptureControl(val generation: Long) {
        val running = AtomicBoolean(true)
        val frames = CaptureVoiceBuffer()
        val encoded = AtomicLong(0)
        val provided = AtomicLong(0)
        val routeRevision = AtomicLong()

        @Volatile var echoState = EchoCancellationState.PENDING

        @Volatile var lastState: MicrophoneCaptureState? = null
        lateinit var routingListener: AudioRouting.OnRoutingChangedListener
        var routedDeviceId: Int? = null

        @Volatile var routedDeviceType: Int? = null

        @Volatile var lastPublishedDeviceType: Int? = null
    }

    companion object {
        const val RNNOISE_FRAME_SAMPLES = 480
        private const val STOP_JOIN_TIMEOUT_MS = 1_500L

        fun localMeter(context: Context): OpusMicrophoneCapture =
            OpusMicrophoneCapture(context, onFailure = {}, onStateChanged = {}, encodeAudio = false)
    }
}

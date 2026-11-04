package io.github.ts3mobile.audio.opus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.AudioRouting
import android.os.Process
import io.github.ts3mobile.protocol.sanitizedFailureTypes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class OpusMicrophoneCapture(
    context: Context,
    private val onFailure: (Throwable) -> Unit,
    private val onStateChanged: (MicrophoneCaptureState) -> Unit,
) : MicrophoneCapture {
    constructor(context: Context, onFailure: (Throwable) -> Unit = {}) : this(context, onFailure, {})

    private val applicationContext = context.applicationContext
    private val lifecycleLock = Any()
    private var options = MicrophoneProcessingOptions()
    private val captureGeneration = AtomicLong(0)
    override val generation: Long get() = captureGeneration.get()

    @Volatile
    private var control: CaptureControl? = null

    @Volatile
    private var resources: CaptureResources? = null

    @Volatile
    private var preferredDevice: AudioDeviceInfo? = null

    @Volatile
    private var worker: Thread? = null

    override val isCapturing: Boolean
        get() = control?.running?.get() == true

    override fun configure(
        settings: MicrophoneSettings,
        volumeGate: Boolean,
    ) {
        synchronized(lifecycleLock) {
            val updated = MicrophoneProcessingOptions(settings, volumeGate)
            if (updated == options) return
            options = updated
            control?.frames?.reset()
            control?.let(::publishState)
        }
    }

    override fun setPreferredDevice(device: AudioDeviceInfo?) {
        synchronized(lifecycleLock) {
            if (preferredDevice?.id == device?.id) return
            preferredDevice = device
            invalidateRouting()
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

            val captureResources = CaptureResources.create(preferredDevice, options.settings)
            captureGeneration.incrementAndGet()
            val newControl = CaptureControl()
            newControl.routingListener =
                AudioRouting.OnRoutingChangedListener { routing ->
                    synchronized(lifecycleLock) {
                        if (control !== newControl || !newControl.running.get()) return@synchronized
                        val deviceId = routing.routedDevice?.id
                        if (newControl.routedDeviceId != deviceId) {
                            newControl.routedDeviceId = deviceId
                            invalidateRouting()
                        }
                    }
                }
            control = newControl
            resources = captureResources
            try {
                captureResources.startRecording()
                captureResources.listen(newControl.routingListener)
            } catch (error: RuntimeException) {
                newControl.running.set(false)
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

    override fun pollEncodedFrame(): ByteArray? {
        val current = control ?: return null
        val frame = current.frames.poll()
        if (frame != null) current.provided.incrementAndGet()
        publishState(current)
        return frame
    }

    override fun close() = stop()

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
        val pipeline =
            MicrophoneCapturePipeline(denoiser::processInPlace, encoder::encode, stats) { settings ->
                denoiser.reset()
                encoder.reset()
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
                val denoiserPcm = assembler.append(inputPcm, read) ?: continue
                val encoded = pipeline.process(denoiserPcm, current, revision, routeRevision)
                if (encoded == null) continue
                captureControl.frames.publish(revision, encoded, pipeline.isSpeaking)
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
        synchronized(lifecycleLock) {
            if (control !== current) return
            val snapshot =
                MicrophoneCaptureState(
                    capturing = resources?.released == false,
                    transmitting = current.running.get() && current.frames.isTransmitting(),
                    echoCancellation = if (current.running.get()) current.echoState else EchoCancellationState.PENDING,
                )
            if (snapshot == current.lastState) return
            current.lastState = snapshot
            onStateChanged(snapshot)
        }
    }

    private class CaptureControl {
        val running = AtomicBoolean(true)
        val frames = CaptureVoiceBuffer()
        val encoded = AtomicLong(0)
        val provided = AtomicLong(0)
        val routeRevision = AtomicLong()

        @Volatile var echoState = EchoCancellationState.PENDING
        var lastState: MicrophoneCaptureState? = null
        lateinit var routingListener: AudioRouting.OnRoutingChangedListener
        var routedDeviceId: Int? = null
    }

    private companion object {
        const val RNNOISE_FRAME_SAMPLES = 480
        const val STOP_JOIN_TIMEOUT_MS = 1_500L
    }
}

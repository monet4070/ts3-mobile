package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.MicrophoneLevel
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import io.github.ts3mobile.audio.opus.OpusMicrophoneCapture
import io.github.ts3mobile.protocol.sanitizedFailureTypes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class MicrophoneTrialPhase { IDLE, RUNNING, TIMED_OUT, UNAVAILABLE, FAILED }

internal data class MicrophoneTrialState(
    val phase: MicrophoneTrialPhase = MicrophoneTrialPhase.IDLE,
    val deviceType: Int? = null,
    val level: MicrophoneLevel? = null,
)

internal interface MicrophoneTrialSource : AutoCloseable {
    val isCapturing: Boolean
    val isSilenced: Boolean

    fun start(settings: MicrophoneSettings)

    fun requestStop() = Unit

    fun reading(): MicrophoneTrialState
}

internal class LocalMicrophoneTrialSource(private val capture: OpusMicrophoneCapture) : MicrophoneTrialSource {
    private val levels = capture.observeLevels()
    override val isCapturing: Boolean get() = capture.isCapturing
    override val isSilenced: Boolean get() = capture.isInputSilenced

    override fun start(settings: MicrophoneSettings) {
        capture.configure(settings, volumeGate = true)
        capture.start()
    }

    override fun requestStop() = capture.requestStop()

    override fun reading() = MicrophoneTrialState(MicrophoneTrialPhase.RUNNING, capture.actualInputType, levels.latest)

    override fun close() {
        levels.close()
        capture.close()
    }
}

internal class MicrophoneTrial(
    private val scope: CoroutineScope,
    private val captureDispatcher: CoroutineDispatcher?,
    private val createSource: () -> MicrophoneTrialSource,
) {
    constructor(
        scope: CoroutineScope,
        createSource: () -> MicrophoneTrialSource,
    ) : this(scope, captureDispatcher = null, createSource = createSource)

    private val mutableState = MutableStateFlow(MicrophoneTrialState())
    val state = mutableState.asStateFlow()

    private val lifecycleLock = Any()
    private val ioMutex = Mutex()
    private var generation = 0L
    private var startingToken: Long? = null
    private var inStartCriticalSection = false
    private var pendingStop: CompletableDeferred<Boolean>? = null
    private var pendingStopResult = MicrophoneTrialPhase.IDLE

    @Volatile private var source: MicrophoneTrialSource? = null

    private var observer: Job? = null
    val isInputReleased: Boolean
        get() =
            if (captureDispatcher == null) {
                source == null
            } else {
                synchronized(lifecycleLock) {
                    source == null && startingToken == null && pendingStop?.isCompleted != false
                }
            }

    fun start(settings: MicrophoneSettings) {
        if (captureDispatcher == null) {
            startSynchronously(settings)
        } else {
            startAsynchronously(settings)
        }
    }

    private fun startSynchronously(settings: MicrophoneSettings) {
        if (mutableState.value.phase == MicrophoneTrialPhase.RUNNING || !stop()) return
        try {
            val capture = createSource().also { source = it }
            capture.start(settings)
            mutableState.value = MicrophoneTrialState(MicrophoneTrialPhase.RUNNING)
            observer =
                scope.launch {
                    repeat(TRIAL_DURATION_MS / SAMPLE_INTERVAL_MS) {
                        delay(SAMPLE_INTERVAL_MS.toLong())
                        try {
                            if (!capture.isCapturing || capture.isSilenced) {
                                stop(MicrophoneTrialPhase.UNAVAILABLE)
                                return@launch
                            }
                            mutableState.value = capture.reading()
                        } catch (error: Exception) {
                            fail(error)
                            return@launch
                        }
                    }
                    stop(MicrophoneTrialPhase.TIMED_OUT)
                }
        } catch (error: Exception) {
            fail(error)
        } catch (error: LinkageError) {
            fail(error)
        }
    }

    private fun startAsynchronously(settings: MicrophoneSettings) {
        val dispatcher = checkNotNull(captureDispatcher)
        var shouldStopExisting = false
        var startJob: Job? = null
        val token =
            synchronized(lifecycleLock) {
                if (mutableState.value.phase == MicrophoneTrialPhase.RUNNING) return
                if (source != null || startingToken != null || pendingStop?.isCompleted == false) {
                    shouldStopExisting = true
                    0L
                } else {
                    val next = ++generation
                    startingToken = next
                    val createdJob =
                        scope.launch(dispatcher, start = CoroutineStart.LAZY) {
                            launchAsyncCapture(next, settings)
                        }
                    observer = createdJob
                    startJob = createdJob
                    next
                }
            }
        if (shouldStopExisting) {
            enqueueAsyncStop(MicrophoneTrialPhase.IDLE)
            return
        }
        val launched = checkNotNull(startJob)
        launched.invokeOnCompletion {
            synchronized(lifecycleLock) {
                if (startingToken == token && !inStartCriticalSection) startingToken = null
                if (observer === launched) observer = null
            }
        }
        launched.start()
        if (launched.isCompleted) {
            synchronized(lifecycleLock) {
                if (startingToken == token && !inStartCriticalSection) startingToken = null
                if (observer === launched) observer = null
            }
        }
    }

    private suspend fun launchAsyncCapture(
        token: Long,
        settings: MicrophoneSettings,
    ) {
        var startError: Throwable? = null
        ioMutex.withLock {
            val shouldCreate =
                synchronized(lifecycleLock) {
                    if (token != generation) {
                        if (startingToken == token) startingToken = null
                        false
                    } else {
                        inStartCriticalSection = true
                        true
                    }
                }
            if (!shouldCreate) return@withLock
            var created: MicrophoneTrialSource? = null
            try {
                created = createSource()
                if (synchronized(lifecycleLock) { token != generation }) {
                    closeOrRetain(created)
                    return@withLock
                }
                created.start(settings)
                val committed =
                    synchronized(lifecycleLock) {
                        if (token != generation) {
                            false
                        } else {
                            source = created
                            mutableState.value = MicrophoneTrialState(MicrophoneTrialPhase.RUNNING)
                            true
                        }
                    }
                if (!committed) {
                    runCatching { created.requestStop() }
                    closeOrRetain(created)
                }
            } catch (error: Exception) {
                created?.let {
                    runCatching { it.requestStop() }
                    closeOrRetain(it)
                }
                startError = error
            } catch (error: LinkageError) {
                created?.let {
                    runCatching { it.requestStop() }
                    closeOrRetain(it)
                }
                startError = error
            } finally {
                synchronized(lifecycleLock) {
                    inStartCriticalSection = false
                    if (startingToken == token) startingToken = null
                }
            }
        }
        startError?.let { error ->
            System.err.println("TS3_AUDIO: local microphone test failed: ${error.sanitizedFailureTypes()}")
            synchronized(lifecycleLock) {
                if (token == generation) {
                    generation++
                    mutableState.value = MicrophoneTrialState(MicrophoneTrialPhase.FAILED)
                }
            }
            return
        }
        pollAsync(token)
    }

    private fun closeOrRetain(candidate: MicrophoneTrialSource) {
        if (runCatching { candidate.close() }.isFailure) {
            synchronized(lifecycleLock) {
                if (source == null) source = candidate
            }
        }
    }

    private suspend fun pollAsync(token: Long) {
        repeat(TRIAL_DURATION_MS / SAMPLE_INTERVAL_MS) {
            delay(SAMPLE_INTERVAL_MS.toLong())
            val active = synchronized(lifecycleLock) { if (token == generation) source else null } ?: return
            try {
                if (!active.isCapturing || active.isSilenced) {
                    enqueueAsyncStop(MicrophoneTrialPhase.UNAVAILABLE, expectedToken = token)
                    return
                }
                val reading = active.reading()
                synchronized(lifecycleLock) {
                    if (token == generation && source === active) {
                        mutableState.value = reading
                    }
                }
            } catch (error: Exception) {
                System.err.println("TS3_AUDIO: local microphone test failed: ${error.sanitizedFailureTypes()}")
                enqueueAsyncStop(MicrophoneTrialPhase.FAILED, expectedToken = token)
                return
            }
        }
        enqueueAsyncStop(MicrophoneTrialPhase.TIMED_OUT, expectedToken = token)
    }

    private fun fail(error: Throwable) {
        System.err.println("TS3_AUDIO: local microphone test failed: ${error.sanitizedFailureTypes()}")
        stop(MicrophoneTrialPhase.FAILED)
    }

    fun stop(result: MicrophoneTrialPhase = MicrophoneTrialPhase.IDLE): Boolean {
        if (captureDispatcher != null) {
            enqueueAsyncStop(result)
            return true
        }
        observer?.cancel()
        observer = null
        return try {
            source?.requestStop()
            source?.close()
            source = null
            mutableState.value = MicrophoneTrialState(result)
            true
        } catch (error: Exception) {
            System.err.println("TS3_AUDIO: local microphone test release failed: ${error.sanitizedFailureTypes()}")
            mutableState.value = MicrophoneTrialState(MicrophoneTrialPhase.FAILED)
            false
        }
    }

    suspend fun stopAndAwait(result: MicrophoneTrialPhase = MicrophoneTrialPhase.IDLE): Boolean =
        if (captureDispatcher == null) {
            stop(result)
        } else {
            enqueueAsyncStop(result).await()
        }

    private fun enqueueAsyncStop(
        result: MicrophoneTrialPhase,
        expectedToken: Long? = null,
    ): Deferred<Boolean> {
        val dispatcher = checkNotNull(captureDispatcher)
        var stopGeneration = 0L
        var targetSource: MicrophoneTrialSource? = null
        var previousStartJob: Job? = null
        var shouldLaunchCleanup = false
        val completion =
            synchronized(lifecycleLock) {
                if (expectedToken != null && expectedToken != generation) {
                    return@synchronized pendingStop ?: CompletableDeferred(source == null && startingToken == null)
                }
                pendingStop?.takeIf { !it.isCompleted }?.let { existing ->
                    pendingStopResult = result
                    return@synchronized existing
                }
                stopGeneration = ++generation
                pendingStopResult = result
                previousStartJob = observer
                observer = null
                targetSource = source
                if (!inStartCriticalSection) startingToken = null
                val stillStarting = startingToken != null || inStartCriticalSection
                if (targetSource == null && !stillStarting) {
                    mutableState.value = MicrophoneTrialState(result)
                    CompletableDeferred(true)
                } else {
                    shouldLaunchCleanup = true
                    CompletableDeferred<Boolean>().also { pendingStop = it }
                }
            }
        previousStartJob?.cancel()
        targetSource?.let { runCatching { it.requestStop() } }
        if (!shouldLaunchCleanup) return completion

        scope.launch(NonCancellable + dispatcher) {
            previousStartJob?.join()
            var releasedOk = true
            ioMutex.withLock {
                val toClose = synchronized(lifecycleLock) { source }
                if (toClose != null) {
                    releasedOk =
                        try {
                            runCatching { toClose.requestStop() }
                            toClose.close()
                            synchronized(lifecycleLock) {
                                if (source === toClose) source = null
                            }
                            true
                        } catch (error: Exception) {
                            System.err.println(
                                "TS3_AUDIO: local microphone test release failed: ${error.sanitizedFailureTypes()}",
                            )
                            false
                        }
                }
                synchronized(lifecycleLock) {
                    if (generation == stopGeneration || !releasedOk) {
                        mutableState.value =
                            MicrophoneTrialState(if (releasedOk) pendingStopResult else MicrophoneTrialPhase.FAILED)
                    }
                    if (pendingStop === completion) pendingStop = null
                }
            }
            completion.complete(releasedOk)
        }
        return completion
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 100
        const val TRIAL_DURATION_MS = 30_000
    }
}

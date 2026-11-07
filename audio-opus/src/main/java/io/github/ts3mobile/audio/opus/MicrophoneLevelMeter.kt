package io.github.ts3mobile.audio.opus

/** One scalar, no callback on the audio thread, no coroutine or allocation per input frame. */
internal class MicrophoneLevelMeter(private val clockNanos: () -> Long = System::nanoTime) {
    private val lock = Any()
    private var observers = 0
    private var generation: Long? = null
    private var processingRevision = 0L
    private var routeRevision = 0L
    private var snapshot: MicrophoneLevel? = null
    private var lastPublication: Long? = null

    fun isObserved(): Boolean = synchronized(lock) { observers > 0 }

    fun subscribe(): MicrophoneLevelSubscription =
        synchronized(lock) {
            observers++
            object : MicrophoneLevelSubscription {
                private var closed = false
                override val latest: MicrophoneLevel?
                    get() = synchronized(lock) { if (closed) null else snapshot }

                override fun close() {
                    synchronized(lock) {
                        if (closed) return
                        closed = true
                        observers--
                        if (observers == 0) clear()
                    }
                }
            }
        }

    fun activate(
        captureGeneration: Long,
        revision: Long,
        route: Long,
        isActive: () -> Boolean = { true },
    ) {
        synchronized(lock) {
            if (!isActive()) return
            if (generation != captureGeneration) lastPublication = null
            generation = captureGeneration
            processingRevision = revision
            routeRevision = route
            clear()
        }
    }

    fun stop(captureGeneration: Long) {
        synchronized(lock) {
            if (generation != captureGeneration) return
            generation = null
            clear()
        }
    }

    fun offer(
        captureGeneration: Long,
        revision: Long,
        route: Long,
        rmsDb: Double,
        gateEnabled: Boolean,
        gateOpen: Boolean,
        transmitting: Boolean,
        thresholdDb: Int,
    ) {
        synchronized(lock) {
            if (observers == 0 || generation != captureGeneration || processingRevision != revision || routeRevision != route) return
            require(rmsDb.isFinite() && rmsDb >= -96.0 && rmsDb <= 0.0)
            val now = clockNanos()
            if (lastPublication?.let { now - it < PUBLICATION_INTERVAL_NANOS } == true) return
            snapshot = MicrophoneLevel(captureGeneration, revision, route, now, rmsDb, gateEnabled, gateOpen, transmitting, thresholdDb)
            lastPublication = now
        }
    }

    private fun clear() {
        snapshot = null
    }

    companion object {
        const val PUBLICATION_INTERVAL_NANOS = 100_000_000L
    }
}

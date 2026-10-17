package io.github.ts3mobile.protocol

interface Ts3SessionListener {
    fun onStatusChanged(status: ConnectionStatus)

    fun onSnapshotChanged(snapshot: SessionSnapshot)

    fun onVoiceFrame(frame: VoiceFrame)
}

interface Ts3SessionClient : AutoCloseable {
    fun connect(
        config: ServerConfig,
        identityMaterial: String,
        listener: Ts3SessionListener,
    )

    fun setVoiceSource(source: EncodedVoiceSource?)

    fun joinChannel(
        channelId: Int,
        password: String = "",
    )

    fun disconnect(reason: String = DEFAULT_DISCONNECT_REASON)

    /**
     * Leaves the current session inside [timeoutMs] and reports how far it got.
     *
     * This is the bounded counterpart of [disconnect]: the implementation must
     * capture the current session, immediately stop using it, send a protocol
     * leave request through the transport's reliable command path, and release
     * the captured session when it returns. [timeoutMs] bounds the send and the
     * confirmation wait on one monotonic deadline, and the release gets one short
     * bounded window afterwards. Reaching the budget must not leave a background
     * task that keeps waiting for the server.
     *
     * [DisconnectOutcome.SERVER_CONFIRMED] is reserved for the server confirming
     * this session's own leave; a local close, a watchdog transition or a UDP
     * acknowledgement alone is never enough. The session is dropped either way,
     * so the caller must treat every outcome except
     * [DisconnectOutcome.NO_ACTIVE_SESSION] as "the session is gone locally".
     *
     * The default reports [DisconnectOutcome.UNSUPPORTED] and performs no I/O, so
     * existing implementations and fakes stay source-compatible.
     * [onRequestSent] runs after the first exit datagram is written, on the IO
     * worker. It must be quick and must not store the reason or connection data.
     * It reports sending only; it never establishes server confirmation.
     */
    suspend fun disconnectBounded(
        timeoutMs: Long,
        reason: String = DEFAULT_DISCONNECT_REASON,
        onRequestSent: () -> Unit = {},
    ): DisconnectOutcome = DisconnectOutcome.UNSUPPORTED

    /**
     * Bounded liveness check used after a default-network switch.
     *
     * Returns true only when the server answered a control round trip within
     * [timeoutMs]. It never blocks longer than that budget and never judges the
     * session from voice traffic, so a silent channel is not mistaken for a
     * dropped connection. Implementations must not write state once the caller
     * cancels the surrounding coroutine.
     *
     * The default assumes the session is alive so simple fakes keep working;
     * the real client overrides it with a protocol round trip.
     */
    suspend fun verifyLiveness(timeoutMs: Long): Boolean = true

    /**
     * Non-blocking transport-level connection state exposed by the implementation.
     *
     * null means there is no usable signal: either this implementation does not
     * expose transport state, or a failure or server-issued disconnect for the
     * current session is already being reported through the listener callbacks.
     * false means the transport confirmed the session is down while nothing else
     * has explained why, even when no listener callback was delivered yet. true
     * means the transport still reports an open session.
     *
     * Reading it must never block, perform I/O or send a protocol probe, and voice
     * silence is never evidence of failure: a silent channel must not be judged
     * disconnected from this property alone.
     *
     * The default returns null so existing implementations stay source-compatible.
     */
    val transportConnected: Boolean?
        get() = null

    override fun close()
}

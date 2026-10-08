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

    fun disconnect(reason: String = "Client disconnected")

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

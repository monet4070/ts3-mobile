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

    override fun close()
}

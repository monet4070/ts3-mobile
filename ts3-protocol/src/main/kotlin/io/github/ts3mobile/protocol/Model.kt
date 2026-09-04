package io.github.ts3mobile.protocol

data class ServerConfig(
    val host: String,
    val port: Int = 9987,
    val nickname: String,
    val password: String = "",
) {
    fun normalized(): ServerConfig =
        copy(
            host = host.trim(),
            nickname = nickname.trim(),
        )

    fun validationError(): String? =
        when {
            host.trim().isEmpty() -> "Server address is required"
            port !in 1..65535 -> "Port must be between 1 and 65535"
            nickname.trim().length !in 3..30 -> "Nickname must contain 3 to 30 characters"
            else -> null
        }
}

enum class ConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    RECONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR,
}

data class ConnectionStatus(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val detail: String? = null,
    val retryable: Boolean = false,
)

data class Ts3Channel(
    val id: Int,
    val parentId: Int,
    val orderAfterId: Int,
    val name: String,
    val clientCount: Int,
    val hasPassword: Boolean,
    val isDefault: Boolean,
)

data class Ts3Participant(
    val id: Int,
    val channelId: Int,
    val nickname: String,
    val isTalking: Boolean,
    val isInputMuted: Boolean,
    val isOutputMuted: Boolean,
    val uniqueIdentifier: String = "",
)

data class SessionSnapshot(
    val channels: List<Ts3Channel> = emptyList(),
    val participants: List<Ts3Participant> = emptyList(),
    val ownClientId: Int? = null,
) {
    val currentChannelId: Int?
        get() = participants.firstOrNull { it.id == ownClientId }?.channelId

    companion object {
        val Empty = SessionSnapshot()
    }
}

data class ChannelRow(
    val channel: Ts3Channel,
    val depth: Int,
)

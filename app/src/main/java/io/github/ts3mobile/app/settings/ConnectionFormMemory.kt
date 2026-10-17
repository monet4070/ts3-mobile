package io.github.ts3mobile.app.settings

import io.github.ts3mobile.app.ConnectionFormState

internal data class ConnectionFormEdits(
    val host: Boolean = false,
    val port: Boolean = false,
    val nickname: Boolean = false,
    val password: Boolean = false,
)

internal fun ConnectionFormState.toRememberedConnection() = RememberedConnection(host, port, nickname, password)

/** A delayed disk load cannot overwrite edits or attach an old password to a new endpoint. */
internal fun ConnectionFormState.restoreRememberedConnection(
    saved: RememberedConnection,
    edits: ConnectionFormEdits,
): ConnectionFormState {
    val restoredHost = if (edits.host) host else saved.host
    val restoredPort = if (edits.port) port else saved.port
    val sameEndpoint = restoredHost.trim().equals(saved.host.trim(), ignoreCase = true) && restoredPort == saved.port
    return copy(
        host = restoredHost,
        port = restoredPort,
        nickname = if (edits.nickname) nickname else saved.nickname,
        password = if (edits.password || !sameEndpoint) password else saved.password,
    )
}

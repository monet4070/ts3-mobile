package io.github.ts3mobile.app.invite

import io.github.ts3mobile.app.service.allowsOfflineActions
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface InvitePreview {
    data object Invalid : InvitePreview

    class Target(val invite: ServerInvite) : InvitePreview
}

internal class InviteEntry {
    private val mutablePreview = MutableStateFlow<InvitePreview?>(null)
    val preview = mutablePreview.asStateFlow()

    fun accept(text: String) {
        val invite = ServerInvite.parse(text)
        if (invite != null && (mutablePreview.value as? InvitePreview.Target)?.invite == invite) return
        mutablePreview.value = invite?.let(InvitePreview::Target) ?: InvitePreview.Invalid
    }

    fun dismiss() {
        mutablePreview.value = null
    }

    fun confirm(
        target: InvitePreview.Target,
        phase: ConnectionPhase?,
        nickname: String,
        password: String,
        channelPassword: String,
        connect: (ServerConfig) -> Unit,
    ) {
        if (phase?.allowsOfflineActions != true) return
        val config = target.invite.configuration(nickname, password, channelPassword)
        if (config.validationError() != null || !mutablePreview.compareAndSet(target, null)) return
        connect(config)
    }
}

internal fun ServerInvite.configuration(
    nickname: String,
    password: String,
    channelPassword: String,
): ServerConfig = ServerConfig(host, port, nickname, password, channelId, if (channelId == null) "" else channelPassword).normalized()

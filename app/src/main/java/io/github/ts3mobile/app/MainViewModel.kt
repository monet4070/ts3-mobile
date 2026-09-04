package io.github.ts3mobile.app

import androidx.lifecycle.ViewModel
import io.github.ts3mobile.protocol.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ConnectionFormState(
    val host: String = "",
    val port: String = "9987",
    val nickname: String = "TS3 Mobile",
    val password: String = "",
    val submitted: Boolean = false,
) {
    fun toServerConfigOrNull(): ServerConfig? {
        val config =
            ServerConfig(
                host = host,
                port = port.toIntOrNull() ?: return null,
                nickname = nickname,
                password = password,
            ).normalized()
        return config.takeIf { it.validationError() == null }
    }
}

class MainViewModel : ViewModel() {
    private val mutableForm = MutableStateFlow(ConnectionFormState())
    val form = mutableForm.asStateFlow()

    fun setHost(value: String) = update { copy(host = value, submitted = false) }

    fun setPort(value: String) = update { copy(port = value.filter(Char::isDigit), submitted = false) }

    fun setNickname(value: String) = update { copy(nickname = value, submitted = false) }

    fun setPassword(value: String) = update { copy(password = value, submitted = false) }

    fun submit(): ServerConfig? {
        mutableForm.update { it.copy(submitted = true) }
        return mutableForm.value.toServerConfigOrNull()
    }

    private inline fun update(transform: ConnectionFormState.() -> ConnectionFormState) {
        mutableForm.update { it.transform() }
    }
}

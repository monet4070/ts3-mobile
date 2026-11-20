package io.github.ts3mobile.app.invite

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.Locale

internal enum class IgnoredInviteField { NICKNAME, PASSWORD, CHANNEL_NAME, CHANNEL_PASSWORD, TOKEN, BOOKMARK, OTHER }

internal data class ServerInvite(
    val host: String,
    val port: Int,
    val channelId: Int?,
    val ignored: Set<IgnoredInviteField> = emptySet(),
) {
    val endpoint: String get() = "${uriHost(host)}:$port"

    override fun toString(): String = "ServerInvite(<redacted>)"

    companion object {
        const val SCHEME = "ts3server"
        const val MAX_LENGTH = 4096
        private const val DEFAULT_PORT = 9987

        fun parse(text: String): ServerInvite? {
            if (text.length > MAX_LENGTH) return null
            val uri =
                try {
                    URI(text.trim())
                } catch (_: URISyntaxException) {
                    return null
                }
            if (!SCHEME.equals(uri.scheme, ignoreCase = true) || uri.rawUserInfo != null || uri.rawFragment != null) return null
            val host = uri.host?.removeSurrounding("[", "]") ?: return null
            if (host.length > 253 || '%' in host) return null
            val authorityPort = uri.rawAuthority.removePrefix(uriHost(host))
            val port = if (authorityPort.isEmpty()) DEFAULT_PORT else authorityPort.removePrefix(":").portOrNull() ?: return null
            var selectedPort = port
            var channelId: Int? = null
            val ignored = mutableSetOf<IgnoredInviteField>()
            if (uri.rawPath.isNotEmpty() && uri.rawPath != "/") ignored += IgnoredInviteField.CHANNEL_NAME
            val seen = mutableSetOf<String>()
            uri.rawQuery?.split('&')?.forEach { parameter ->
                val key = decode(parameter.substringBefore('=')).lowercase(Locale.ROOT)
                if (key.isBlank() || !seen.add(key)) return null
                val value = parameter.substringAfter('=', "")
                when (key) {
                    "port" -> {
                        if (authorityPort.isNotEmpty()) return null
                        selectedPort = decode(value).portOrNull() ?: return null
                    }
                    "cid" -> channelId = decode(value).positiveIntegerOrNull() ?: return null
                    else -> ignored += ignoredField(key)
                }
            }
            return ServerInvite(host, selectedPort, channelId, ignored)
        }

        fun shareText(
            host: String,
            port: Int,
            channelId: Int? = null,
        ): String? {
            val text =
                "$SCHEME://${uriHost(host.removeSurrounding("[", "]"))}?port=$port" +
                    if (channelId == null) "" else "&cid=$channelId"
            val invite = parse(text) ?: return null
            return text.takeIf { invite.host == host.removeSurrounding("[", "]") && invite.channelId == channelId && invite.port == port }
        }

        private fun ignoredField(key: String): IgnoredInviteField =
            when (key) {
                "nickname" -> IgnoredInviteField.NICKNAME
                "password" -> IgnoredInviteField.PASSWORD
                "channel" -> IgnoredInviteField.CHANNEL_NAME
                "channelpassword" -> IgnoredInviteField.CHANNEL_PASSWORD
                "token" -> IgnoredInviteField.TOKEN
                "addbookmark" -> IgnoredInviteField.BOOKMARK
                else -> IgnoredInviteField.OTHER
            }

        private fun uriHost(host: String): String = if (':' in host) "[$host]" else host

        private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

        private fun String.portOrNull(): Int? = positiveIntegerOrNull()?.takeIf { it <= 65535 }

        private fun String.positiveIntegerOrNull(): Int? =
            takeIf { all { character -> character in '0'..'9' } }?.toIntOrNull()?.takeIf { it > 0 }
    }
}

package io.github.ts3mobile.protocol

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.file.Path
import java.util.Base64

class VirtualServerUid private constructor(private val value: String) {
    override fun equals(other: Any?): Boolean = other is VirtualServerUid && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "VirtualServerUid(<redacted>)"

    companion object {
        fun parse(value: String): VirtualServerUid? {
            if (value.length != 28 && value.length != 44) return null
            val bytes =
                try {
                    Base64.getDecoder().decode(value)
                } catch (_: IllegalArgumentException) {
                    return null
                }
            if (bytes.size != 20 && bytes.size != 32 || Base64.getEncoder().encodeToString(bytes) != value) return null
            return VirtualServerUid(value)
        }
    }
}

fun interface AttachmentDownload {
    suspend fun stage(privateDirectory: Path): ChannelFileResult<Path>
}

class ChannelAttachment private constructor(
    val serverUid: VirtualServerUid,
    val channelId: Int,
    val directory: String,
    val fileName: String,
    internal val remotePath: String,
) {
    override fun toString(): String = "ChannelAttachment(<redacted>)"

    companion object {
        private val queryKey = Regex("[a-zA-Z][a-zA-Z0-9_]{0,31}")

        fun parse(reference: String): ChannelAttachment? {
            if (reference.length !in 1..4096 || reference.any { it.isWhitespace() || it.isISOControl() }) return null
            val uri =
                try {
                    URI(reference)
                } catch (_: URISyntaxException) {
                    return null
                }
            if (!uri.scheme.equals("ts3file", ignoreCase = true) || uri.host == null || uri.rawUserInfo != null ||
                uri.rawFragment != null || uri.port != -1 || uri.rawPath !in setOf("", "/")
            ) {
                return null
            }
            val fields = queryFields(uri.rawQuery ?: return null) ?: return null
            fields["port"].positiveInt()?.takeIf { it <= 65_535 } ?: return null
            val uid = fields["serverUID"]?.let(VirtualServerUid::parse) ?: return null
            if ("channel" in fields && "cid" in fields) return null
            val channel = (fields["channel"] ?: fields["cid"]).positiveInt() ?: return null
            if (fields["isDir"] != "0") return null
            val directory = fields["path"] ?: return null
            val file = fields["filename"]?.takeIf { it.isChannelFileName() } ?: return null
            val canonicalDirectory = directory.removeSuffix("/").ifEmpty { "/" }
            if (!directory.startsWith('/') || directory.endsWith("//")) return null
            if (!canonicalDirectory.isChannelFilePath(allowRoot = true)) return null
            val remotePath = canonicalDirectory.trimEnd('/') + "/" + file
            if (!remotePath.isChannelFilePath(allowRoot = false)) return null
            return ChannelAttachment(uid, channel, canonicalDirectory, file, remotePath)
        }

        private fun queryFields(query: String): Map<String, String>? {
            val parts = query.split('&', limit = 25)
            if (parts.size > 24) return null
            val fields = mutableMapOf<String, String>()
            for (part in parts) {
                val separator = part.indexOf('=')
                if (separator <= 0) return null
                val key = part.substring(0, separator)
                if (!queryKey.matches(key) || fields.containsKey(key)) return null
                val value = URLDecoder.decode(part.substring(separator + 1).replace("+", "%2B"), Charsets.UTF_8.name())
                if ('\uFFFD' in value || value.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) return null
                fields[key] = value
            }
            return fields
        }

        private fun String?.positiveInt(): Int? =
            this?.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toIntOrNull()?.takeIf { it > 0 }
    }
}

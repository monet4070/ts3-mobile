package io.github.ts3mobile.protocol

import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

interface ServerImages {
    suspend fun download(image: ServerImage): ChannelFileResult<ByteArray>
}

sealed class ServerImage {
    internal abstract val path: String

    internal open fun matchesVersion(bytes: ByteArray): Boolean = true

    class Icon internal constructor(val id: Long) : ServerImage() {
        init {
            require(id in 1..0xffff_ffffL)
        }

        override val path: String get() = "/icon_$id"

        override fun equals(other: Any?): Boolean = other is Icon && id == other.id

        override fun hashCode(): Int = id.hashCode()
    }

    class Avatar internal constructor(private val encodedUid: String, private val version: String) : ServerImage() {
        init {
            require(encodedUid.length == 40 && encodedUid.all { it in 'a'..'p' } && avatarVersion.matches(version))
        }

        override val path: String get() = "/avatar_$encodedUid"

        override fun matchesVersion(bytes: ByteArray): Boolean {
            val digest = MessageDigest.getInstance("MD5").digest(bytes)
            return digest.toNibbles("0123456789abcdef") == version
        }

        override fun equals(other: Any?): Boolean = other is Avatar && encodedUid == other.encodedUid && version == other.version

        override fun hashCode(): Int = 31 * encodedUid.hashCode() + version.hashCode()
    }

    companion object {
        private val avatarVersion = Regex("[0-9a-fA-F]{32}")

        fun icon(wireId: String): Icon? {
            if (wireId.length !in 1..11 || wireId.first() == '+' || wireId.any { it != '-' && it !in '0'..'9' }) return null
            val id = wireId.toLongOrNull() ?: return null
            if (id == 0L || id !in Int.MIN_VALUE.toLong()..0xffff_ffffL) return null
            return Icon(id and 0xffff_ffffL)
        }

        fun avatar(
            uid: String,
            version: String,
        ): Avatar? {
            if (uid.length != 28 || !avatarVersion.matches(version)) return null
            val bytes =
                try {
                    Base64.getDecoder().decode(uid)
                } catch (_: IllegalArgumentException) {
                    return null
                }
            if (bytes.size != 20 || Base64.getEncoder().encodeToString(bytes) != uid) return null
            return Avatar(bytes.toNibbles("abcdefghijklmnop"), version.lowercase(Locale.ROOT))
        }

        private fun ByteArray.toNibbles(alphabet: String): String =
            buildString(size * 2) {
                for (byte in this@toNibbles) {
                    append(alphabet[(byte.toInt() and 0xff) ushr 4])
                    append(alphabet[byte.toInt() and 0x0f])
                }
            }
    }
}

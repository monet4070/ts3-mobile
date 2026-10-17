package io.github.ts3mobile.app.settings

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** One form snapshot; values must never be included in logs or diagnostics. */
data class RememberedConnection(val host: String, val port: String, val nickname: String, val password: String) {
    override fun toString(): String = "RememberedConnection(<redacted>)"
}

internal object RememberedConnectionCodec {
    fun encode(value: RememberedConnection): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(1)
                listOf(value.host, value.port, value.nickname, value.password).forEach(output::writeUTF)
            }
            bytes.toByteArray()
        }

    fun decode(bytes: ByteArray): RememberedConnection =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 1) { "Unsupported connection settings version" }
            val result = RememberedConnection(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF())
            require(input.available() == 0) { "Unexpected connection settings content" }
            result
        }
}

package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.identity.LocalIdentity
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

data class Ts3IdentitySummary(val uid: String, val keyOffset: Long, val securityLevel: Int) {
    override fun toString(): String = "Ts3IdentitySummary(uid=<redacted>, offset=$keyOffset, security=$securityLevel)"
}

class InvalidIdentityMaterial : IllegalArgumentException("Invalid identity material")

object Ts3IdentityCodec {
    const val MAX_MATERIAL_BYTES = 64 * 1_024

    fun generate(securityLevel: Int = 10): String = LocalIdentity.generateNew(securityLevel).export()

    fun normalizeDesktopIni(bytes: ByteArray): String = DesktopIdentityIni.normalize(bytes)

    /** Bounded summary/validation only; never repairs, improves or logs imported material. */
    fun inspect(material: String): Ts3IdentitySummary {
        try {
            val identity = decode(material)
            val publicKey = identity.publicKey
            val privateKey = identity.privateKey
            require(privateKey.signum() > 0 && privateKey < publicKey.curve.order)
            require(publicKey.isValid && !publicKey.isInfinity)
            require(LocalIdentity.load(privateKey).publicKey.normalize() == publicKey.normalize())
            require(identity.keyOffset >= 0)
            return Ts3IdentitySummary(identity.uid.toBase64(), identity.keyOffset, identity.securityLevel)
        } catch (_: Exception) {
            // Parser/provider messages may contain the source INI or private scalar.
            throw InvalidIdentityMaterial()
        }
    }

    internal fun decode(material: String): LocalIdentity {
        require(material.length <= MAX_MATERIAL_BYTES)
        val bytes = material.toByteArray(StandardCharsets.UTF_8)
        try {
            require(bytes.size <= MAX_MATERIAL_BYTES)
            return ByteArrayInputStream(bytes).use(LocalIdentity::read)
        } finally {
            bytes.fill(0)
        }
    }
}

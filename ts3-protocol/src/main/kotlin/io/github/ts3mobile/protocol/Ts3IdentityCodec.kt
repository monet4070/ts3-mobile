package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.identity.LocalIdentity
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

data class Ts3IdentitySummary(val uid: String, val keyOffset: Long, val securityLevel: Int) {
    override fun toString(): String = "Ts3IdentitySummary(uid=<redacted>, offset=$keyOffset, security=$securityLevel)"
}

class InvalidIdentityMaterial : IllegalArgumentException("Invalid identity material")

object Ts3IdentityCodec {
    fun generate(securityLevel: Int = 10): String = LocalIdentity.generateNew(securityLevel).export()

    /** Bounded summary/validation only; never repairs, improves or logs imported material. */
    fun inspect(material: String): Ts3IdentitySummary {
        try {
            require(material.length <= 64 * 1_024 && material.toByteArray(StandardCharsets.UTF_8).size <= 64 * 1_024)
            val identity = decode(material)
            val publicKey = identity.publicKey
            val privateKey = identity.privateKey
            require(privateKey.signum() > 0 && privateKey < publicKey.curve.order)
            require(publicKey.isValid && !publicKey.isInfinity)
            require(LocalIdentity.load(privateKey).publicKey.normalize() == publicKey.normalize())
            require(identity.keyOffset >= 0)
            return Ts3IdentitySummary(identity.uid.toString(), identity.keyOffset, identity.securityLevel)
        } catch (_: Exception) {
            // Parser/provider messages may contain the source INI or private scalar.
            throw InvalidIdentityMaterial()
        }
    }

    internal fun decode(material: String): LocalIdentity =
        ByteArrayInputStream(material.toByteArray(StandardCharsets.UTF_8)).use(LocalIdentity::read)
}

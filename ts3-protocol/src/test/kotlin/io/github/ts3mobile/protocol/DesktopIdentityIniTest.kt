package io.github.ts3mobile.protocol

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class DesktopIdentityIniTest {
    @Test fun independentEphemeralVectorPreservesUidOffsetAndLevelAcrossBomAndLineEndings() {
        val (value, expected) = desktopVector()
        for (separator in listOf("\n", "\r\n")) {
            for (bom in listOf("", "\uFEFF")) {
                val lines = listOf("[Identity]", "id=fixture", "nickname=桌面", "identity=$value", "phonetic_nickname=Fixture", "")
                val source = bom + lines.joinToString(separator)
                val material = Ts3IdentityCodec.normalizeDesktopIni(source.toByteArray())
                assertEquals("[Identity]\r\nidentity=$value\r\n", material)
                assertEquals(expected, Ts3IdentityCodec.inspect(material))
                assertFalse(material.contains("nickname"))
            }
        }
    }

    @Test fun unsupportedSectionsKeysReferencesEncodingsAndOversizeAreSanitized() {
        val keyLine = "identity=\"17VYQ==\""
        val single = "[Identity]\n$keyLine\n"
        val textVariants =
            listOf(
                single + keyLine,
                single + "Identity=$keyLine",
                single + "[Identity]\n$keyLine",
                single + "[Other]\nnickname=Fixture",
                single + "include=fixture.ini",
                single.replace("\n", "\r"),
                "[Identity]\nidentity=\"17VYQ==",
                "TS3MBAK\u0000",
                "\uFEFF$single\uFEFF",
            )
        val binaryVariants =
            listOf(
                single.toByteArray(Charsets.UTF_16LE),
                byteArrayOf(0xc3.toByte(), 0x28),
                ByteArray(Ts3IdentityCodec.MAX_MATERIAL_BYTES + 1),
            )
        (textVariants.map { it.toByteArray() } + binaryVariants).forEach { bytes ->
            val error = assertThrows(InvalidIdentityMaterial::class.java) { Ts3IdentityCodec.normalizeDesktopIni(bytes) }
            assertEquals("Invalid identity material", error.message)
            assertNull(error.cause)
        }
    }

    @Test fun canonicalizationDoesNotReplaceProtocolValidationOfTruncatedOrMismatchedKeys() {
        val (mismatch, _) = desktopVector(mismatch = true)
        listOf(mismatch, "\"17VYQ==\"").forEach { value ->
            val canonical = Ts3IdentityCodec.normalizeDesktopIni("[Identity]\nidentity=$value\n".toByteArray())
            assertThrows(InvalidIdentityMaterial::class.java) { Ts3IdentityCodec.inspect(canonical) }
        }
    }

    private fun desktopVector(mismatch: Boolean = false): Pair<String, Ts3IdentitySummary> {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val point = (pair.public as ECPublicKey).w
        val scalar = if (mismatch) BigInteger.ZERO else (pair.private as ECPrivateKey).s
        val coordinates = listOf(ASN1Integer(32), ASN1Integer(point.affineX), ASN1Integer(point.affineY))
        val publicData = DERSequence((listOf(DERBitString(byteArrayOf(0), 7)) + coordinates).toTypedArray()).encoded
        val privateData = DERSequence((listOf(DERBitString(128)) + coordinates + ASN1Integer(scalar)).toTypedArray()).encoded
        val publicText = Base64.getEncoder().encodeToString(publicData)
        val payload = Base64.getEncoder().encode(privateData)
        val mask =
            "b9dfaa7bee6ac57ac7b65f1094a1c155e747327bc2fe5d51c512023fe54a280" +
                "201004e90ad1daaae1075d53b7d571c30e063b5a62a4a017bb394833aa0983e6e"

        fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes)
        for (index in 0 until minOf(100, payload.size)) payload[index] = (payload[index].toInt() xor mask[index].code).toByte()
        val suffix = payload.copyOfRange(20, payload.size).takeWhile { it != 0.toByte() }.toByteArray()
        val digest = sha1(suffix)
        for (index in digest.indices) payload[index] = (payload[index].toInt() xor digest[index].toInt()).toByte()
        val offset = 137L
        val levelDigest = sha1("$publicText$offset".toByteArray(Charsets.US_ASCII))
        val zeroBytes = levelDigest.takeWhile { it == 0.toByte() }.size
        val level = zeroBytes * 8 + (levelDigest.getOrNull(zeroBytes)?.let { Integer.numberOfTrailingZeros(it.toInt() and 255) } ?: 0)
        val uid = Base64.getEncoder().encodeToString(sha1(publicText.toByteArray(Charsets.US_ASCII)))
        val expected = Ts3IdentitySummary(uid, offset, level)
        return "\"${offset}V${Base64.getEncoder().encodeToString(payload)}\"" to expected
    }
}

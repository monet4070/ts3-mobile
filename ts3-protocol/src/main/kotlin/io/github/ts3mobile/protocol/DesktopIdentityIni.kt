package io.github.ts3mobile.protocol

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal object DesktopIdentityIni {
    private val displayKeys = setOf("id", "nickname", "phonetic_nickname")
    private val identityValue = Regex("\"[0-9]{1,19}V[A-Za-z0-9+/]+={0,2}\"")

    fun normalize(bytes: ByteArray): String {
        if (bytes.size !in 1..Ts3IdentityCodec.MAX_MATERIAL_BYTES) throw InvalidIdentityMaterial()
        val decoded = CharBuffer.allocate(bytes.size)
        return try {
            val decoder =
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes), decoded, true).let { if (!it.isUnderflow) it.throwException() }
            decoder.flush(decoded).let { if (!it.isUnderflow) it.throwException() }
            decoded.flip()
            extract(decoded.toString().removePrefix("\uFEFF"))
        } catch (_: CharacterCodingException) {
            throw InvalidIdentityMaterial()
        } finally {
            decoded.array().fill('\u0000')
        }
    }

    private fun extract(text: String): String {
        val invalidCharacter =
            text.indices.any { index ->
                val char = text[index]
                char == '\uFEFF' || (char == '\r' && text.getOrNull(index + 1) != '\n') || (char.isISOControl() && char !in "\r\n\t")
            }
        if (invalidCharacter) throw InvalidIdentityMaterial()
        var header = false
        var value: String? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim(' ', '\t')
            if (line.isEmpty()) continue
            if (!header) {
                if (line != "[Identity]") throw InvalidIdentityMaterial()
                header = true
                continue
            }
            val separator = line.indexOf('=')
            if (separator < 0) throw InvalidIdentityMaterial()
            when (line.substring(0, separator).trimEnd(' ', '\t')) {
                "identity" -> {
                    val field = line.substring(separator + 1).trimStart(' ', '\t')
                    if (value != null || !identityValue.matches(field)) throw InvalidIdentityMaterial()
                    value = field
                }
                in displayKeys -> Unit
                else -> throw InvalidIdentityMaterial()
            }
        }
        val canonicalValue = value ?: throw InvalidIdentityMaterial()
        return "[Identity]\r\nidentity=$canonicalValue\r\n"
    }
}

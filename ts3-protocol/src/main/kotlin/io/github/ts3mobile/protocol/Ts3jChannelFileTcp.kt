package io.github.ts3mobile.protocol

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class Ts3jChannelFileTcp(
    maximumBytes: Long = 20L * 1024 * 1024,
    private val timeoutMs: Long = 30_000,
) {
    private val download = ChannelFileDownload(maximumBytes)
    private val imageDownload = ChannelFileDownload(MAX_IMAGE_BYTES)
    private var closed = false
    private var activeSocket: Socket? = null

    fun stage(
        peer: InetAddress,
        offer: Ts3jDownloadOffer,
        directory: Path,
        current: () -> Boolean,
    ): Path {
        download.validateSize(offer.size)
        return receive(peer, offer, current, timeoutMs) { source ->
            download.stageVerified(source, directory, offer.size, current)
        }
    }

    fun readImage(
        peer: InetAddress,
        offer: Ts3jDownloadOffer,
        current: () -> Boolean,
    ): ByteArray {
        if (offer.size == 0L) throw FileReplyFailure(ChannelFileFailure.INVALID_RESPONSE)
        imageDownload.validateSize(offer.size)
        return receive(peer, offer, current, minOf(timeoutMs, IMAGE_TIMEOUT_MS)) { source ->
            val bytes = ByteArrayOutputStream(offer.size.toInt())
            imageDownload.copyVerified(source, bytes, offer.size, current)
            bytes.toByteArray()
        }
    }

    private fun <Value> receive(
        peer: InetAddress,
        offer: Ts3jDownloadOffer,
        current: () -> Boolean,
        budgetMs: Long,
        read: (InputStream) -> Value,
    ): Value {
        if (!current()) throw FileReplyFailure(ChannelFileFailure.SESSION_CHANGED)
        if (!matchesTransferPeer(offer.address, peer)) throw FileReplyFailure(ChannelFileFailure.INVALID_RESPONSE)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        val socket = openSocket()
        try {
            return socket.use {
                it.connect(InetSocketAddress(peer, offer.port), budgetMs.coerceAtMost(3_000).toInt())
                if (!current() || Thread.currentThread().isInterrupted) throw FileReplyFailure(ChannelFileFailure.SESSION_CHANGED)
                val key = offer.key.toByteArray(Charsets.US_ASCII)
                try {
                    it.getOutputStream().write(key)
                } finally {
                    key.fill(0)
                }
                read(pollingInput(it, deadline, current))
            }
        } finally {
            synchronized(this) { if (activeSocket === socket) activeSocket = null }
        }
    }

    private fun pollingInput(
        socket: Socket,
        deadline: Long,
        current: () -> Boolean,
    ): FilterInputStream =
        object : FilterInputStream(socket.getInputStream()) {
            override fun read(): Int = boundedRead { super.read() }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int = boundedRead { super.read(buffer, offset, length) }

            private fun boundedRead(action: () -> Int): Int {
                while (true) {
                    if (!current() || Thread.currentThread().isInterrupted) throw FileReplyFailure(ChannelFileFailure.SESSION_CHANGED)
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) throw SocketTimeoutException()
                    socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1, 250).toInt()
                    try {
                        return action()
                    } catch (_: SocketTimeoutException) {
                        if (System.nanoTime() >= deadline) throw SocketTimeoutException()
                    }
                }
            }
        }

    @Synchronized
    fun close() {
        closed = true
        val socket = activeSocket ?: return
        activeSocket = null
        try {
            socket.close()
        } catch (_: java.io.IOException) {
            System.err.println("TS3_FILE: closing the transfer socket failed")
        }
    }

    @Synchronized
    private fun openSocket(): Socket {
        if (closed) throw FileReplyFailure(ChannelFileFailure.SESSION_CHANGED)
        return Socket().also { activeSocket = it }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 256L * 1024
        const val IMAGE_TIMEOUT_MS = 5_000L
    }
}

/**
 * Validates the server's `notifystartdownload` `ip` field against the already-resolved [peer]
 * using strict numeric IP literal parsing (never DNS resolution). TCP always connects to [peer].
 *
 * Accepts missing/empty values, exact address matches in a comma-separated list, or wildcard
 * unspecified wildcard IPv4 or `::` addresses as long as no non-wildcard address in the same IP family
 * explicitly points somewhere other than [peer].
 */
internal fun matchesTransferPeer(
    rawOfferAddress: String?,
    peer: InetAddress,
): Boolean {
    if (rawOfferAddress == null) return true
    val tokens =
        rawOfferAddress
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
    if (tokens.isEmpty()) return true

    val parsed = ArrayList<ByteArray>(tokens.size)
    for (token in tokens) {
        val normalized = parseNumericIpBytes(token)?.let(::normalizeIpv4Mapped) ?: return false
        parsed.add(normalized)
    }
    val peerBytes = normalizeIpv4Mapped(peer.address)
    if (parsed.any { it.contentEquals(peerBytes) }) return true

    val hasSameFamilyMismatch = parsed.any { it.size == peerBytes.size && !isUnspecifiedAddress(it) }
    if (hasSameFamilyMismatch) return false

    return parsed.any(::isUnspecifiedAddress)
}

private fun isUnspecifiedAddress(bytes: ByteArray): Boolean = bytes.all { it == 0.toByte() }

private fun normalizeIpv4Mapped(bytes: ByteArray): ByteArray {
    if (bytes.size != 16) return bytes
    val prefixIsIpv4Mapped = (0..9).all { bytes[it] == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
    return if (prefixIsIpv4Mapped) bytes.copyOfRange(12, 16) else bytes
}

private fun parseNumericIpBytes(rawToken: String): ByteArray? {
    val unbracketed =
        if (rawToken.startsWith('[') && rawToken.endsWith(']') && rawToken.length > 2) {
            rawToken.substring(1, rawToken.length - 1)
        } else {
            rawToken
        }
    return if (':' in unbracketed) parseIpv6Bytes(unbracketed) else parseIpv4Bytes(unbracketed)
}

private fun parseIpv4Bytes(literal: String): ByteArray? {
    val parts = literal.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for (index in parts.indices) {
        val part = parts[index]
        if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
        if (part.length > 1 && part[0] == '0') return null
        val value = part.toIntOrNull() ?: return null
        if (value !in 0..255) return null
        bytes[index] = value.toByte()
    }
    return bytes
}

private fun parseIpv6Bytes(raw: String): ByteArray? {
    // Strip an optional numeric/interface zone suffix (e.g. %eth0) while keeping literal IPv6 parsing offline.
    val zoneIndex = raw.indexOf('%')
    val address = if (zoneIndex >= 0) raw.substring(0, zoneIndex) else raw
    if (address.isEmpty() || address.contains(":::")) return null

    val compressionIndex = address.indexOf("::")
    val hasCompression = compressionIndex >= 0
    if (hasCompression && address.indexOf("::", compressionIndex + 2) >= 0) return null

    val (leftText, rightText) =
        if (hasCompression) {
            address.substring(0, compressionIndex) to address.substring(compressionIndex + 2)
        } else {
            address to ""
        }

    val leftHextets = parseHextetGroups(leftText, allowTrailingIpv4 = !hasCompression && rightText.isEmpty()) ?: return null
    val rightHextets =
        if (hasCompression) {
            parseHextetGroups(rightText, allowTrailingIpv4 = true) ?: return null
        } else {
            emptyList()
        }

    val totalGroups = leftHextets.size + rightHextets.size
    if (hasCompression) {
        if (totalGroups >= 8) return null
    } else if (totalGroups != 8) {
        return null
    }

    val bytes = ByteArray(16)
    var outIndex = 0
    for (hextet in leftHextets) {
        bytes[outIndex++] = (hextet ushr 8).toByte()
        bytes[outIndex++] = (hextet and 0xff).toByte()
    }
    val zeroGroups = 8 - totalGroups
    outIndex += zeroGroups * 2
    for (hextet in rightHextets) {
        bytes[outIndex++] = (hextet ushr 8).toByte()
        bytes[outIndex++] = (hextet and 0xff).toByte()
    }
    return bytes
}

private fun parseHextetGroups(
    segment: String,
    allowTrailingIpv4: Boolean,
): List<Int>? {
    if (segment.isEmpty()) return emptyList()
    val parts = segment.split(':')
    val groups = ArrayList<Int>(parts.size + 1)
    for (index in parts.indices) {
        val part = parts[index]
        if (part.isEmpty()) return null
        if ('.' in part) {
            if (!allowTrailingIpv4 || index != parts.lastIndex) return null
            val ipv4 = parseIpv4Bytes(part) ?: return null
            groups += ((ipv4[0].toInt() and 0xff) shl 8) or (ipv4[1].toInt() and 0xff)
            groups += ((ipv4[2].toInt() and 0xff) shl 8) or (ipv4[3].toInt() and 0xff)
        } else {
            if (part.length > 4 || !part.all(::isHexDigit)) return null
            groups += part.toInt(16)
        }
    }
    return groups
}

private fun isHexDigit(ch: Char): Boolean = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

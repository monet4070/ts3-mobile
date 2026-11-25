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
        if (offer.address != null && offer.address != peer.hostAddress) throw FileReplyFailure(ChannelFileFailure.INVALID_RESPONSE)
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

package io.github.ts3mobile.app.ui

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal class UsbLoopbackVoiceRelay(private val bridgePort: Int) : AutoCloseable {
    private val udp = DatagramSocket(InetSocketAddress(LOOPBACK, 0))
    private val tunnel = Socket()
    private val client = AtomicReference<InetSocketAddress?>()
    private val failure = AtomicReference<IOException?>()
    private val workers = mutableListOf<Thread>()

    val clientHost: String get() = checkNotNull(LOOPBACK.hostAddress)
    val clientPort: Int get() = udp.localPort

    init {
        try {
            tunnel.connect(InetSocketAddress(LOOPBACK, bridgePort), 3_000)
            tunnel.tcpNoDelay = true
            val outgoing = DataOutputStream(tunnel.getOutputStream())
            val incoming = DataInputStream(tunnel.getInputStream())
            pump("usb-fixture-send") {
                val buffer = ByteArray(MAX_PACKET + 1)
                val packet = DatagramPacket(buffer, buffer.size)
                while (!udp.isClosed) {
                    packet.length = buffer.size
                    udp.receive(packet)
                    val sender = packet.socketAddress as InetSocketAddress
                    if (sender.address == LOOPBACK && packet.length in 1..MAX_PACKET) {
                        client.compareAndSet(null, sender)
                        if (sender == client.get()) {
                            outgoing.writeShort(packet.length)
                            outgoing.write(packet.data, 0, packet.length)
                        }
                    }
                }
            }
            pump("usb-fixture-receive") {
                val buffer = ByteArray(MAX_PACKET)
                while (!udp.isClosed) {
                    val size = incoming.readUnsignedShort()
                    if (size !in 1..MAX_PACKET) throw IOException("Invalid fixture datagram length")
                    incoming.readFully(buffer, 0, size)
                    client.get()?.let { udp.send(DatagramPacket(buffer, size, it)) }
                }
            }
        } catch (error: IOException) {
            close()
            throw error
        }
    }

    fun checkHealthy() {
        failure.get()?.let { throw AssertionError("USB fixture bridge stopped", it) }
    }

    private fun pump(
        name: String,
        forward: () -> Unit,
    ) {
        workers +=
            thread(isDaemon = true, name = name) {
                try {
                    forward()
                } catch (error: IOException) {
                    if (!udp.isClosed) failure.compareAndSet(null, error)
                }
            }
    }

    override fun close() {
        udp.close()
        tunnel.close()
        workers.forEach { it.join(1_000) }
    }

    companion object {
        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private const val MAX_PACKET = 4_096
    }
}

package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.protocol.packet.PacketBody
import com.github.manevolent.ts3j.protocol.packet.PacketBody0Voice
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkspurtMicrophoneTest {
    @Test fun theRealTs3jSenderSkipsQueueGapsAndOnlyEndsOnExplicitStop() {
        val source = Source()
        val socket = CapturingSocket()
        val task = actualSender(socket, source)
        try {
            source.frames += byteArrayOf(1, 2, 3, 4)
            task.run()
            repeat(10) { task.run() }
            source.frames += byteArrayOf(5, 6, 7, 8)
            task.run()
            assertEquals(listOf(4, 4), socket.payloadSizes)
            source.active = false
            task.run()
            task.run()
            assertEquals(listOf(4, 4, 0), socket.payloadSizes)
        } finally {
            runCatching { socket.close() }
        }
    }

    @Test fun theRealSenderDrainsClosedGateTailAndKeepsSilentListeningOffWire() {
        val frames = ArrayDeque<ByteArray>()
        var gateOpen = false
        val source =
            object : EncodedVoiceSource {
                override val isTransmitting get() = gateOpen || frames.isNotEmpty()

                override fun isReady() = frames.isNotEmpty()

                override fun pollEncodedFrame() = frames.removeFirstOrNull()
            }
        val socket = CapturingSocket()
        val task = actualSender(socket, source)
        try {
            repeat(100) { task.run() }
            assertTrue(socket.payloadSizes.isEmpty())
            gateOpen = true
            repeat(6) { frames += byteArrayOf(1, 2) }
            repeat(6) { task.run() }
            repeat(10) { task.run() }
            assertEquals(List(6) { 2 }, socket.payloadSizes)
            frames += byteArrayOf(3, 4)
            frames += byteArrayOf(5, 6)
            gateOpen = false
            repeat(100) { task.run() }
            assertEquals(List(8) { 2 } + 0, socket.payloadSizes)
            gateOpen = true
            frames += byteArrayOf(7, 8)
            task.run()
            gateOpen = false
            repeat(2) { task.run() }
            assertEquals(List(8) { 2 } + listOf(0, 2, 0), socket.payloadSizes)
        } finally {
            runCatching { socket.close() }
        }
    }

    @Test fun sourceFailuresAreReportedOnceAndEndReadiness() {
        val failure = IllegalStateException("fixture failure")
        val source =
            object : EncodedVoiceSource {
                override fun isReady() = true

                override fun pollEncodedFrame(): ByteArray = throw failure
            }
        val failures = mutableListOf<Throwable>()
        val adapter = TalkspurtMicrophone(source, failures::add)
        assertTrue(adapter.isReady)
        assertEquals(0, adapter.provide().size)
        assertFalse(adapter.isReady)
        adapter.provide()
        assertEquals(listOf(failure), failures)
    }

    private fun actualSender(
        socket: CapturingSocket,
        source: EncodedVoiceSource,
    ): Runnable {
        val adapter = TalkspurtMicrophone(source) { throw AssertionError(it) }
        // Drive the pinned library's actual sender without its timer, network or Android.
        val field = LocalTeamspeakClientSocket::class.java.getDeclaredField("microphone").apply { isAccessible = true }
        field.set(socket, adapter)
        val taskClass = Class.forName("com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket\$MicrophoneTask")
        val constructor = taskClass.getDeclaredConstructor(LocalTeamspeakClientSocket::class.java).apply { isAccessible = true }
        val task = constructor.newInstance(socket) as Runnable
        return task
    }

    private class Source : EncodedVoiceSource {
        var active = true
        val frames = ArrayDeque<ByteArray>()
        override val isTransmitting get() = active

        override fun isReady() = frames.isNotEmpty()

        override fun pollEncodedFrame() = frames.removeFirstOrNull()
    }

    private class CapturingSocket : LocalTeamspeakClientSocket() {
        val payloadSizes = mutableListOf<Int>()

        override fun isConnected() = true

        override fun writePacket(body: PacketBody) {
            if (body is PacketBody0Voice) payloadSizes += body.codecData.size
        }
    }
}

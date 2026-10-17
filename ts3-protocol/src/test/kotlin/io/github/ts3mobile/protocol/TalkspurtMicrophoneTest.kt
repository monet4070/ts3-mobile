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
        val adapter = TalkspurtMicrophone(source) { throw AssertionError(it) }
        // Drive the pinned library's actual sender without its timer, network or Android.
        val field = LocalTeamspeakClientSocket::class.java.getDeclaredField("microphone").apply { isAccessible = true }
        field.set(socket, adapter)
        val taskClass = Class.forName("com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket\$MicrophoneTask")
        val constructor = taskClass.getDeclaredConstructor(LocalTeamspeakClientSocket::class.java).apply { isAccessible = true }
        val task = constructor.newInstance(socket) as Runnable
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

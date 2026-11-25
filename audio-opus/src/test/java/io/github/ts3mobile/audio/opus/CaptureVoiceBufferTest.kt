package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureVoiceBufferTest {
    @Test fun queueGapsDoNotEndOpenTalkspurt() {
        val queue = CaptureVoiceBuffer()
        queue.publish(queue.revision(), listOf(byteArrayOf(1)), true)
        assertArrayEquals(byteArrayOf(1), queue.poll())
        assertFalse(queue.isReady())
        assertTrue(queue.isTransmitting())
        assertNull(queue.poll())
        assertTrue(queue.isTransmitting())
    }

    @Test fun closedGateDrainsTailBeforeEndingTalkspurt() {
        val queue = CaptureVoiceBuffer()
        queue.publish(queue.revision(), listOf(byteArrayOf(1), byteArrayOf(2)), false)
        assertTrue(queue.isTransmitting())
        queue.poll()
        assertTrue(queue.isTransmitting())
        queue.poll()
        assertFalse(queue.isTransmitting())
    }

    @Test fun slowConsumerDropsOldestWithFiniteBacklog() {
        val queue = CaptureVoiceBuffer()
        repeat(100) { queue.publish(queue.revision(), listOf(byteArrayOf(it.toByte())), true) }
        val retained = generateSequence { queue.poll()?.single()?.toInt() }.toList()
        assertEquals((91..99).toList(), retained)
    }

    @Test fun overflowCountsOnlyEvictionsAndRetainsEachSurvivingFrameTimestamp() {
        val queue = CaptureVoiceBuffer()
        val revision = queue.revision()
        val bytes = List(CaptureVoiceBuffer.CAPACITY + 2) { byteArrayOf(it.toByte()) }
        val timestamps = bytes.indices.map { it * 100_000L }
        assertEquals(2, queue.publish(revision, bytes, true, timestamps))
        assertEquals(timestamps.drop(2), generateSequence(queue::pollTimed).map { it.encodedAtNanos }.toList())
        queue.reset()
        assertEquals(0, queue.publish(revision, bytes, true, timestamps))
        assertNull(queue.pollTimed())
    }

    @Test fun resetRejectsInFlightOldConfigurationAndClearsSpeech() {
        val queue = CaptureVoiceBuffer()
        val old = queue.revision()
        queue.publish(old, listOf(byteArrayOf(1)), true)
        queue.reset()
        queue.publish(old, listOf(byteArrayOf(2)), true)
        assertNull(queue.poll())
        assertFalse(queue.isTransmitting())
        queue.publish(queue.revision(), listOf(byteArrayOf(3)), true)
        assertArrayEquals(byteArrayOf(3), queue.poll())
    }

    @Test fun stoppedWorkerCannotReviveQueueOrAffectNextCapture() {
        val retired = CaptureVoiceBuffer()
        retired.publish(retired.revision(), listOf(byteArrayOf(1)), true)
        retired.stop()
        retired.publish(retired.revision(), listOf(byteArrayOf(2)), true)
        val current = CaptureVoiceBuffer()
        current.publish(current.revision(), listOf(byteArrayOf(3)), true)
        retired.stop()
        assertFalse(retired.isTransmitting())
        assertNull(retired.poll())
        assertArrayEquals(byteArrayOf(3), current.poll())
    }
}

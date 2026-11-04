package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureInputAssemblerTest {
    @Test fun routeRevisionDiscardsPartialInputBeforeNewSamplesAreAssembled() {
        val assembler = CaptureInputAssembler()
        assembler.prepare(0)
        assertNull(assembler.append(ShortArray(120) { 1 }, 120))
        assembler.prepare(1)
        assertEquals(480, assembler.remaining)
        assertNull(assembler.append(ShortArray(240) { 2 }, 240))
        assembler.prepare(1)
        assertArrayEquals(ShortArray(240) { 2 } + ShortArray(240) { 3 }, assembler.append(ShortArray(240) { 3 }, 240))
    }
}

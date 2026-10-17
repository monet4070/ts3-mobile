package io.github.ts3mobile.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRequestOwnershipTest {
    @Test
    fun aConnectRegisteredAfterAnExitTakesOwnershipBack() {
        val ownership = SessionRequestOwnership()
        val exitToken = ownership.beginExit()
        assertTrue(ownership.isLatestExit(exitToken))

        ownership.beginConnect()

        assertFalse(ownership.isLatestExit(exitToken))
    }

    @Test
    fun aSecondExitKeepsTheRunningExitsTokenSoItCanFinishTheTeardown() {
        val ownership = SessionRequestOwnership()
        val first = ownership.beginExit()

        val second = ownership.beginExit()

        assertEquals(first, second)
        assertTrue(ownership.isLatestExit(first))
    }

    @Test
    fun anExitAfterAConnectGetsAFreshToken() {
        val ownership = SessionRequestOwnership()
        val first = ownership.beginExit()
        ownership.beginConnect()

        val second = ownership.beginExit()

        assertNotEquals(first, second)
        assertFalse(ownership.isLatestExit(first))
        assertTrue(ownership.isLatestExit(second))
    }

    @Test
    fun withLatestExitRunsOnlyForTheNewestExitToken() {
        val ownership = SessionRequestOwnership()
        val exitToken = ownership.beginExit()

        val ran = ownership.withLatestExit(exitToken) { "ran" }
        ownership.beginConnect()
        val skipped = ownership.withLatestExit(exitToken) { "ran" }

        assertEquals("ran", ran)
        assertEquals(null, skipped)
    }

    @Test
    fun everyRegistrationAdvancesTheRevisionMonotonically() {
        val ownership = SessionRequestOwnership()
        val start = ownership.currentRevision

        ownership.beginExit()
        ownership.beginConnect()
        ownership.beginExit()
        ownership.beginExit()

        assertEquals(start + 3, ownership.currentRevision)
    }
}

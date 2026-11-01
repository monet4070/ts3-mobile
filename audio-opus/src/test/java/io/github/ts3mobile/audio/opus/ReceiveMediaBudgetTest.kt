package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiveMediaBudgetTest {
    @Test fun clientLimitEvictsOldestOutstandingMediaRatherThanOldestClient() {
        val budget = ReceiveMediaBudget()
        budget.reserve(1, 120, 1, 0)!!.release()
        val oldestMedia = budget.reserve(2, 120, 1, 1)!!
        for (client in 3..64) budget.reserve(client, 120, 1, client.toLong())
        val freshFromOldClient = budget.reserve(1, 120, 1, 100)!!
        budget.reserve(65, 120, 1, 101)
        assertTrue(freshFromOldClient.valid)
        assertFalse(oldestMedia.valid)
    }

    @Test fun releaseIsIdempotentAndCannotCreateNegativePacketOrByteAccounting() {
        val budget = ReceiveMediaBudget()
        val lease = budget.reserve(1, 960, 100, 0)!!
        lease.consume(480)
        assertEquals(480, budget.samples(1))
        lease.release()
        lease.release()
        lease.consume(0)
        assertFalse(lease.valid)
        repeat(96) { assertTrue(budget.reserve(1, 120, 1, 0)!!.valid) }
        assertEquals(11520, budget.samples(1))
        assertEquals(0L, budget.stats.budgetResets.get())
    }

    @Test fun concealmentCannotEvictOrRenewTheRealPacketsMediaReservation() {
        val budget = ReceiveMediaBudget()
        val first = budget.reserve(1, 5760, 100, 0)!!
        val second = budget.reserve(1, 5760, 100, 0)!!
        assertNull(budget.conceal(second, 960, 0))
        assertTrue(first.valid && second.valid)
        first.release()
        val concealed = budget.conceal(second, 960, 10_000_000)!!
        assertEquals(6720, budget.samples(1))
        concealed.release()
        second.release()
        assertEquals(0, budget.samples(1))
    }

    @Test fun retiredGenerationReleaseCannotReduceTheFreshGenerationsBudget() {
        val budget = ReceiveMediaBudget()
        val old = budget.reserve(1, 5760, 100, 0)!!
        budget.invalidate(1)
        val fresh = budget.reserve(1, 960, 100, 1)!!
        old.release()
        assertEquals(960, budget.samples(1))
        assertTrue(fresh.valid)
    }
}

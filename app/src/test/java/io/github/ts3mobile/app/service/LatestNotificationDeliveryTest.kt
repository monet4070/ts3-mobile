package io.github.ts3mobile.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestNotificationDeliveryTest {
    @Test fun aBurstCoalescesAndARejectedFinalPostIsRetriedThenVerified() {
        val fixture = Fixture(drop = { attempt -> attempt <= 2 })
        fixture.delivery.offer("Mute")
        fixture.delivery.offer("Resume")
        fixture.delivery.offer("Check route")
        assertEquals(listOf(LatestNotificationDelivery.COALESCE_MS), fixture.delays)
        repeat(4) { fixture.runScheduled() }
        assertEquals(List(3) { "Check route" }, fixture.posts)
        assertEquals("Check route", fixture.visible)
        assertEquals(0, fixture.failures)
        assertTrue(fixture.tasks.isEmpty())
    }

    @Test fun aNewOfferSupersedesAnUnconfirmedPostAndStopCancelsTheRetry() {
        val fixture = Fixture(drop = { true })
        fixture.delivery.offer("Resume")
        fixture.runScheduled()
        fixture.delivery.offer("Check route")
        fixture.runScheduled()
        val retired = fixture.tasks.single()
        fixture.delivery.clear()
        fixture.delivery.offer("Mute")
        retired.run()
        assertEquals(listOf("Resume", "Check route"), fixture.posts)
        fixture.runScheduled()
        assertEquals("Mute", fixture.posts.last())
    }

    @Test fun failedImmediatePromotionContentIsRetriedWithoutAnotherPromotionAndStopsAtTheBudget() {
        val fixture = Fixture(drop = { true })
        fixture.delivery.offer("Promoted", alreadyPosted = true)
        assertEquals(emptyList<String>(), fixture.posts)
        repeat(3) { fixture.runScheduled() }
        assertEquals(List(2) { "Promoted" }, fixture.posts)
        assertEquals(1, fixture.failures)
        assertTrue(fixture.tasks.isEmpty())
    }

    private class Fixture(drop: (Int) -> Boolean) {
        val tasks = ArrayDeque<Runnable>()
        val delays = mutableListOf<Long>()
        val posts = mutableListOf<String>()
        var visible: String? = null
        var failures = 0
        val delivery =
            LatestNotificationDelivery<String>(
                schedule = { task, delay ->
                    tasks.addLast(task)
                    delays.add(delay)
                },
                cancel = { tasks.remove(it) },
                deliver = { content ->
                    posts.add(content)
                    if (!drop(posts.size)) visible = content
                },
                confirmed = { it == visible },
                onUndelivered = { failures++ },
            )

        fun runScheduled() = tasks.removeFirst().run()
    }
}

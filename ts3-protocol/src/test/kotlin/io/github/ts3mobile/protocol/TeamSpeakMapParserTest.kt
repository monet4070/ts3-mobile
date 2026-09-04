package io.github.ts3mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

class TeamSpeakMapParserTest {
    @Test
    fun channelFixtureMapsToStableDomainModel() {
        val channel = loadFixture("channel-list.properties").toTs3Channel(id = 7)

        assertEquals(
            Ts3Channel(
                id = 7,
                parentId = 0,
                orderAfterId = 0,
                name = "Lobby",
                clientCount = 0,
                hasPassword = false,
                isDefault = true,
            ),
            channel,
        )
    }

    @Test
    fun editFixtureChangesOnlyFieldsPresentInTheEvent() {
        val initial = Ts3Channel(7, 0, 0, "Lobby", 3, false, true)
        val updated = initial.withTeamSpeakUpdates(loadFixture("channel-edit.properties"))

        assertEquals("Games", updated.name)
        assertTrue(updated.hasPassword)
        assertEquals(3, updated.clientCount)
        assertTrue(updated.isDefault)
    }

    private fun loadFixture(name: String): Map<String, String> {
        val properties = Properties()
        TeamSpeakMapParserTest::class.java
            .getResourceAsStream("/fixtures/$name")
            .use { stream -> properties.load(stream) }
        return properties.stringPropertyNames().associateWith(properties::getProperty)
    }
}

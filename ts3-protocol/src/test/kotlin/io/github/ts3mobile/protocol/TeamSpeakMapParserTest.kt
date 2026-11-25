package io.github.ts3mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun imageFieldsChangeOnlyWhenPresentAndZeroClearsChannelIcon() {
        val channel = mapOf("channel_icon_id" to "-1").toTs3Channel(id = 7)
        assertEquals(ServerImage.icon("4294967295"), channel.icon)
        assertEquals(channel.icon, channel.withTeamSpeakUpdates(mapOf("channel_name" to "changed")).icon)
        assertNull(channel.withTeamSpeakUpdates(mapOf("channel_icon_id" to "0")).icon)
        assertNull(channel.withTeamSpeakUpdates(mapOf("channel_icon_id" to "invalid")).icon)

        val fixture = serverImageFixture()
        val uid = fixture.getProperty("uid")
        val version = fixture.getProperty("version")
        val participant = Ts3Participant(1, 7, "Fixture", false, false, false, uid)
        val updated = participant.withTeamSpeakUpdates(mapOf("client_flag_avatar" to version))
        assertEquals(ServerImage.avatar(uid, version), updated.avatar)
        assertEquals(updated.avatar, updated.withTeamSpeakUpdates(mapOf("client_nickname" to "New name")).avatar)
        assertNull(updated.withTeamSpeakUpdates(mapOf("client_flag_avatar" to "")).avatar)
        assertNull(updated.withTeamSpeakUpdates(mapOf("client_unique_identifier" to "different")).avatar)
    }

    private fun loadFixture(name: String): Map<String, String> {
        val properties = Properties()
        TeamSpeakMapParserTest::class.java
            .getResourceAsStream("/fixtures/$name")
            .use { stream -> properties.load(stream) }
        return properties.stringPropertyNames().associateWith(properties::getProperty)
    }
}

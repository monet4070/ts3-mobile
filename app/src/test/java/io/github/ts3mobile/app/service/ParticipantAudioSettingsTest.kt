package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.Ts3Participant
import org.junit.Assert.assertEquals
import org.junit.Test

class ParticipantAudioSettingsTest {
    @Test
    fun gainRespectsMuteAndVolumeBounds() {
        assertEquals(1f, ParticipantAudioSettings().gain)
        assertEquals(1.5f, ParticipantAudioSettings(volumePercent = 150).gain)
        assertEquals(0f, ParticipantAudioSettings(volumePercent = 150, muted = true).gain)
        assertEquals(2f, ParticipantAudioSettings(volumePercent = 500).gain)
        assertEquals(0f, ParticipantAudioSettings(volumePercent = 0).gain)
        assertEquals(0f, ParticipantAudioSettings(volumePercent = -10).gain)
    }

    @Test
    fun listeningAttenuationApproximatesLoudnessRatherThanAmplitude() {
        assertEquals(0.0992f, ParticipantAudioSettings(volumePercent = 25).gain, 0.0001f)
        assertEquals(0.3150f, ParticipantAudioSettings(volumePercent = 50).gain, 0.0001f)
        assertEquals(0.6191f, ParticipantAudioSettings(volumePercent = 75).gain, 0.0001f)
    }

    @Test
    fun controlKeyPrefersStableTeamSpeakIdentity() {
        val participant = Ts3Participant(7, 1, "Alice", false, false, false, "stable-id")

        assertEquals("stable-id", participant.audioControlKey())
        assertEquals("session:7", participant.copy(uniqueIdentifier = "").audioControlKey())
    }
}

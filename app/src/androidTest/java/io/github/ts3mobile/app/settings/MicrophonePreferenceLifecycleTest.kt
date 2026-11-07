package io.github.ts3mobile.app.settings

import android.media.AudioDeviceInfo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.DiagnosticsRecorder
import io.github.ts3mobile.app.service.MicrophoneController
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.audio.opus.MicrophoneCapture
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.UUID

/** Scratch preferences and a fake capture only; no recording or production preference file. */
class MicrophonePreferenceLifecycleTest {
    @Test fun loadAndResetConfigureCaptureWithoutStartingItOrRequestingPermission() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.cacheDir, "microphone-${UUID.randomUUID()}.preferences_pb")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val state = MutableStateFlow(TeamSpeakServiceState())
            val capture = Capture()
            var permissionChecks = 0
            val microphone =
                MicrophoneController(
                    context, scope, state, DiagnosticsRecorder(), {}, {}, {}, { "fixture failure" },
                    captureFactory = { _, _, _ -> capture },
                    permissionGranted = {
                        permissionChecks++
                        false
                    },
                )
            var preferences: MicrophonePreferenceController? = null
            try {
                val store = MicrophonePreferenceDataStore(PreferenceDataStoreFactory.create(scope = scope) { file })
                val saved = MicrophoneSettings(155, false, false, -28)
                store.write(saved)
                preferences = MicrophonePreferenceController(scope, store, microphone::setSettings)
                withTimeout(3_000) { preferences.state.first { it.status == MicrophonePreferenceStatus.SAVED } }
                assertEquals(saved, capture.settings)
                assertEquals(saved, state.value.microphoneSettings)
                assertEquals(MicrophoneMode.PUSH_TO_TALK, state.value.microphoneMode)
                assertEquals(0, capture.starts)
                assertEquals(0, permissionChecks)
                preferences.reset()
                withTimeout(3_000) { preferences.state.first { it.status == MicrophonePreferenceStatus.SAVED } }
                assertEquals(MicrophoneSettings(), store.read())
                assertEquals(0, capture.starts)
                assertEquals(0, permissionChecks)
            } finally {
                preferences?.close()
                microphone.close()
                scope.coroutineContext.job.cancelAndJoin()
                file.delete()
            }
        }

    @Test fun tuningIsExcludedFromBackupAndDeviceTransfer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf(R.xml.backup_rules to 1, R.xml.data_extraction_rules to 2).forEach { (resource, expected) ->
            var count = 0
            context.resources.getXml(resource).use { parser ->
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude" &&
                        parser.getAttributeValue(null, "domain") == "file" &&
                        parser.getAttributeValue(null, "path") == "datastore/ts3_microphone_preferences.preferences_pb"
                    ) {
                        count++
                    }
                    parser.next()
                }
            }
            assertEquals(expected, count)
        }
    }

    private class Capture : MicrophoneCapture {
        override val isCapturing = false
        override val generation = 0L
        var starts = 0
        var settings = MicrophoneSettings()

        override fun configure(
            settings: MicrophoneSettings,
            volumeGate: Boolean,
        ) {
            this.settings = settings
        }

        override fun setPreferredDevice(device: AudioDeviceInfo?) = Unit

        override fun start() {
            starts++
        }

        override fun stop() = Unit

        override fun close() = Unit

        override fun isReady() = false

        override fun pollEncodedFrame(): ByteArray? = null
    }
}

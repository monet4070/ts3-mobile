package io.github.ts3mobile.audio.opus

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** Measurement assertions enforce the fixture/safety contract, not a preferred implementation. */
class ParticipantMuteMeasurementTest {
    @Test fun recordUnmixedMediaLossAndDecoderOwnershipAcrossControlledMuteBoundaries() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val label = InstrumentationRegistry.getArguments().getString("muteStudyLabel", "measurement")
        require(label in setOf("baseline", "candidate", "measurement"))
        val rows = JSONArray()
        repeat(2) { repetition ->
            for (point in listOf(MuteProbePoint.PENDING, MuteProbePoint.PARTIAL, MuteProbePoint.BLOCKED)) {
                val trace = ParticipantMuteProbe.run(point)
                rows.put(
                    JSONObject().apply {
                        put("point", trace.point)
                        put("repetition", repetition)
                        put("bUnmixedBeforeMute", trace.bUnmixedBeforeMute)
                        put("bInputBeforeMute", trace.bInputBeforeMute)
                        put("bEncodedBeforeMute", trace.bEncodedBeforeMute)
                        put("bPcmBeforeMute", trace.bPcmBeforeMute)
                        put("bUnmixedAfterMute", trace.bUnmixedAfterMute)
                        put("bUnmixedBeforeUnmute", trace.bUnmixedBeforeUnmute)
                        put("bUnmixedAfterUnmute", trace.bUnmixedAfterUnmute)
                        put("bDecoderClosedOnMute", trace.bDecoderClosedOnMute)
                        put("bDecoderRebuiltOnMute", trace.bDecoderRebuiltOnMute)
                        put("bDecoderRebuiltOnUnmute", trace.bDecoderRebuiltOnUnmute)
                        put("oldOutputAcceptedFrames", trace.oldOutputAcceptedFrames)
                        put("muteMainNanos", trace.muteMainNanos)
                        put("unmuteMainNanos", trace.unmuteMainNanos)
                        put("firstBWriteAfterMuteNanos", trace.firstBWriteAfterMuteNanos)
                        put("firstBWriteAfterUnmuteNanos", trace.firstBWriteAfterUnmuteNanos)
                        put("outputCreations", trace.outputCreations)
                    },
                )
            }
        }
        val report =
            JSONObject().apply {
                put("label", label)
                put("codec", NativeOpusInfo.version)
                put("sampleRate", 48_000)
                put("mixedBlockSamples", TalkerJitterPipeline.MIX_TICK_SAMPLES)
                put("fakeOutputOnly", true)
                put("microphoneCaptured", false)
                put("serverConnected", false)
                put("traces", rows)
            }
        File(instrumentation.targetContext.getExternalFilesDir(null), "participant-mute-$label.json").writeText(report.toString(2))
        // Small lines avoid logcat truncation; CI extracts only this synthetic report after uninstall.
        report.toString(2).lineSequence().forEach { println("PARTICIPANT_MUTE_JSON=$it") }
    }
}

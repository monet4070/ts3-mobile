package io.github.ts3mobile.app.probe

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.descendants
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3Channel
import io.github.ts3mobile.protocol.Ts3Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ProbeOverlayCardTest {
    @Test fun compactConnectedHeaderShowsOnlyTheCurrentChannelAndUpdatesItsMemberCount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val channel = Ts3Channel(2, 0, 0, "Game lobby", 8, false, false)
        val own = Ts3Participant(10, channel.id, "Self", false, false, false)
        val peer = Ts3Participant(11, channel.id, "Teammate", true, false, false)
        val elsewhere = Ts3Participant(12, 3, "Other channel", false, false, false)
        val snapshot = SessionSnapshot(listOf(channel), listOf(own, peer, elsewhere), own.id)
        val session = TeamSpeakServiceState(snapshot = snapshot)
        val twoMembers = context.resources.getQuantityString(R.plurals.ptt_overlay_members, 2, 2)
        val oneMember = context.resources.getQuantityString(R.plurals.ptt_overlay_members, 1, 1)
        instrumentation.runOnMainSync {
            val card = ProbeOverlayCard(context, R.string.ptt_connected_hold, {}, {}, {}, {})
            card.renderDetails(context.connectedPttOverlayDetails(session, MicrophoneMode.PUSH_TO_TALK))
            card.render(R.string.ptt_overlay_idle, false)
            assertFalse(card.expanded)
            assertEquals(listOf(channel.name, twoMembers, peer.nickname), card.headerTexts())
            assertEquals(context.getString(R.string.ptt_overlay_hold_action), card.microphone.actionText())
            assertFalse(card.microphone.isClickable)
            card.measure(View.MeasureSpec.makeMeasureSpec(card.preferredWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            assertEquals(context.probeDp(216), card.measuredWidth)
            assertEquals(card.headerHeight(), card.measuredHeight)
            assertEquals(context.probeDp(96), card.microphone.measuredWidth)
            assertEquals(context.probeDp(48), card.microphone.measuredHeight)
            assertEquals(LinearLayout.HORIZONTAL, card.microphone.orientation)

            card.render(R.string.ptt_connected_capture, true)
            assertEquals(listOf(channel.name, twoMembers, peer.nickname), card.headerTexts())
            assertEquals(context.getString(R.string.ptt_connected_capture), ViewCompat.getStateDescription(card.microphone))

            val peerLeft = session.copy(snapshot = snapshot.copy(participants = listOf(own, elsewhere)))
            card.renderDetails(context.connectedPttOverlayDetails(peerLeft, MicrophoneMode.PUSH_TO_TALK))
            assertEquals(listOf(channel.name, oneMember), card.headerTexts())
            card.expand(true)
            assertEquals(1, card.descendants.filterIsInstance<TextView>().count { it.text == channel.name })
            assertTrue(card.descendants.filterIsInstance<TextView>().any { it.text == context.getString(R.string.ptt_overlay_no_speakers) })
        }
    }

    @Test fun expandedControlsShowModeAndMicrophoneStateWithoutReplacingTheChannelHeader() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val selected = mutableListOf<MicrophoneMode>()
            val card = ProbeOverlayCard(context, R.string.ptt_connected_hold, {}, {}, {}, {}, { selected.add(it) })
            val details =
                ProbeOverlayDetails(
                    "Lobby",
                    membersLabel = "5 people",
                    speakers = listOf("Echo", "Nova"),
                    mode = MicrophoneMode.VOLUME_GATE,
                )
            card.renderDetails(details)
            card.render(R.string.ptt_overlay_idle, false)
            assertEquals(context.getString(R.string.ptt_overlay_idle), card.microphone.actionText())
            assertFalse(card.microphone.isClickable)
            card.expand(true)
            card.render(R.string.ptt_connected_listening, true)
            val texts = card.descendants.filterIsInstance<TextView>().toList()
            val gate = texts.single { it.text == context.getString(R.string.ptt_overlay_gate_short) }
            val off = texts.single { it.text == context.getString(R.string.microphone_mode_off) }
            assertTrue(gate.isSelected)
            assertTrue(gate.parent === off.parent)
            assertTrue(texts.indexOfFirst { it.text == "Nova" } in 0 until texts.indexOf(gate))
            assertEquals(context.getString(R.string.ptt_connected_listening), ViewCompat.getStateDescription(card.microphone))
            assertEquals(context.getString(R.string.ptt_overlay_gate_waiting), card.microphone.actionText())
            assertFalse(card.microphone.isClickable)
            assertEquals(listOf("Lobby", "5 people"), card.headerTexts())

            assertTrue(off.performClick())
            assertEquals(listOf(MicrophoneMode.OFF), selected)
            card.renderDetails(details.copy(mode = MicrophoneMode.OFF))
            card.render(R.string.ptt_overlay_idle, false)
            assertFalse(card.microphone.isEnabled)
            assertEquals(context.getString(R.string.ptt_overlay_mic_disabled), card.microphone.actionText())
        }
    }

    @Test fun localHeaderKeepsItsMicrophoneStatusInsteadOfInventingAChannelCount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val title = context.getString(R.string.ptt_overlay_local_title)
        instrumentation.runOnMainSync {
            val card = ProbeOverlayCard(context, R.string.ptt_probe_hold, {}, {}, {}, {})
            card.renderDetails(ProbeOverlayDetails(title, "Local only", "0 presses"))
            assertEquals(listOf(title), card.headerTexts())
            assertEquals(context.getString(R.string.ptt_overlay_hold_test), card.microphone.actionText())
            card.render(R.string.ptt_overlay_sampling, true)
            assertEquals(listOf(title), card.headerTexts())
            assertEquals(context.getString(R.string.ptt_overlay_sampling), card.microphone.actionText())
        }
    }

    @Test fun shortChannelTitleIsCenteredWithinTheDraggableRegionInBothLayouts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val card = ProbeOverlayCard(context, R.string.ptt_connected_hold, {}, {}, {}, {})
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                card.layoutDirection = direction
                for (expanded in listOf(false, true)) {
                    card.expand(expanded)
                    card.renderDetails(ProbeOverlayDetails("A", membersLabel = "5", mode = MicrophoneMode.CONTINUOUS))
                    card.render(R.string.ptt_connected_capture, true)
                    card.measure(
                        View.MeasureSpec.makeMeasureSpec(card.preferredWidth(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.UNSPECIFIED,
                    )
                    card.layout(0, 0, card.measuredWidth, card.measuredHeight)
                    val title = card.handle.descendants.filterIsInstance<TextView>().single { it.text == "A" }
                    val bounds = Rect(0, 0, title.width, title.height)
                    card.handle.offsetDescendantRectToMyCoords(title, bounds)
                    assertTrue(kotlin.math.abs(bounds.centerX() - card.handle.width / 2) <= 1)
                    assertEquals("android.widget.TextView", card.microphone.createAccessibilityNodeInfo().className)
                    assertFalse(card.microphone.performClick())
                }
            }
        }
    }

    @Test fun speakerPreviewAndExpandedListKeepTheSamePrimaryName() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val override = Configuration().apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val context = instrumentation.targetContext.createConfigurationContext(override)
        instrumentation.runOnMainSync {
            val card = ProbeOverlayCard(context, R.string.ptt_connected_hold, {}, {}, {}, {}, {})
            card.renderDetails(
                ProbeOverlayDetails(
                    "小队",
                    membersLabel = "6 人",
                    speakers = listOf("小岚", "阿澈", "Nova"),
                    moreSpeakers = 1,
                    mode = MicrophoneMode.PUSH_TO_TALK,
                ),
            )
            card.render(R.string.ptt_overlay_idle, false)
            assertEquals(listOf("小队", "6 人", "小岚"), card.headerTexts())
            val title = card.handle.descendants.filterIsInstance<TextView>().single { it.text == "小队" }
            assertEquals(Gravity.CENTER_HORIZONTAL, title.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
            card.captureFixture("ptt-mode-compact.png")
            card.expand(true)
            assertEquals(listOf("小队", "6 人"), card.headerTexts())
            assertTrue(card.descendants.filterIsInstance<TextView>().any { it.text == "阿澈 · Nova · +1" })
            card.captureFixture("ptt-mode-expanded.png")
            card.expand(false)
            card.renderDetails(ProbeOverlayDetails("队", membersLabel = "6 人", mode = MicrophoneMode.VOLUME_GATE))
            card.render(R.string.ptt_connected_listening, activeTone = true)
            card.captureFixture("ptt-mode-gate.png")
        }
    }

    private fun ProbeOverlayCard.captureFixture(filename: String) {
        if (InstrumentationRegistry.getArguments().getString("capturePttFixture") != "true") return
        measure(View.MeasureSpec.makeMeasureSpec(preferredWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        layout(0, 0, measuredWidth, measuredHeight)
        val bitmap = Bitmap.createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(bitmap))
            context.openFileOutput(filename, 0).use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun ProbeOverlayCard.headerTexts(): List<String> =
        handle.descendants.filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.map {
            it.text.toString()
        }.toList()

    private fun ProbeMicButton.actionText(): String = descendants.filterIsInstance<TextView>().single().text.toString()
}

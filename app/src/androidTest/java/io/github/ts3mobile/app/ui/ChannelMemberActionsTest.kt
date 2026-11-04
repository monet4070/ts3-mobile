package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.protocol.TextTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ChannelMemberActionsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun channelMemberOpensPokeDirectlyAndReleasesPttBeforeConfirmation() {
        val fixture = UiFixture(connected = true)
        fixture.chatStore.updateDraft("channel draft")
        fixture.refreshChat()
        rule.setContent { fixture.Render() }
        rule.onNodeWithContentDescription("Poke Alex").assertDoesNotExist()
        rule.onNodeWithContentDescription("Poke Sam").assertIsDisplayed()
        saveScreen("channel-members-direct-poke")
        rule.onNodeWithContentDescription("Push to talk").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithContentDescription("Poke Sam").performClick()
        rule.onNodeWithText("Poke Sam").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(listOf(true, false), fixture.transmission)
            assertTrue(fixture.sentPokes.isEmpty())
        }
        rule.onNodeWithTag("poke_reason").performTextInput(" 中文😀 | \\ ")
        rule.onNodeWithTag("poke_confirm").performClick()
        rule.onNodeWithText("Poke sent").assertIsDisplayed()
        rule.onNodeWithContentDescription("Poke Sam").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(listOf(TextTarget.Private(2, "fixture-2") to " 中文😀 | \\ "), fixture.sentPokes)
            assertTrue(fixture.sentBodies.isEmpty())
            assertEquals("channel draft", fixture.chat.value.conversations.first().draft)
        }
    }

    @Test fun expandedOtherChannelCanPokeItsMemberWithoutJoining() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Design studio"))
        rule.onNodeWithText("Design studio").performScrollTo().performClick()
        // Expansion waits for the double-tap-to-join gesture's timeout.
        rule.waitUntil(2_000) {
            rule.onAllNodesWithContentDescription("Poke Morgan").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Poke Morgan").performScrollTo().performClick()
        rule.onNodeWithText("Poke Morgan").assertIsDisplayed()
        rule.onNodeWithTag("poke_confirm").performClick()
        rule.runOnIdle {
            assertEquals(listOf(TextTarget.Private(4, "fixture-4") to ""), fixture.sentPokes)
            assertTrue(fixture.joins.isEmpty())
        }
    }

    @Test fun channelPokeRejectsAReusedClientIdWhileTheDialogIsOpen() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        rule.onNodeWithContentDescription("Poke Sam").performClick()
        rule.runOnIdle {
            val participants =
                fixture.state.value.snapshot.participants.map {
                    if (it.id == 2) it.copy(uniqueIdentifier = "replacement") else it
                }
            fixture.state.value = fixture.state.value.copy(snapshot = fixture.state.value.snapshot.copy(participants = participants))
            fixture.chatStore.bind(1, 1, fixture.chat.value.binding, participants = participants)
            fixture.refreshChat()
        }
        rule.onNodeWithTag("poke_confirm").assertIsNotEnabled()
        rule.onNodeWithText("User offline or identity changed. Select the member again.").assertIsDisplayed()
        rule.runOnIdle { assertTrue(fixture.sentPokes.isEmpty()) }
    }

    @Test fun cancelingChannelPokeLeavesTheRosterAndSendsNothing() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        rule.onNodeWithContentDescription("Poke Sam").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithContentDescription("Poke Sam").assertIsDisplayed()
        rule.runOnIdle { assertTrue(fixture.sentPokes.isEmpty()) }
    }

    @Test fun chineseLargeTextKeepsChannelMemberActionsReadableAt320Dp() {
        val fixture = UiFixture(connected = true)
        rule.setContent { Box(Modifier.width(320.dp)) { fixture.Render(locale = "zh-CN", fontScale = 1.8f) } }
        val action = rule.onNodeWithContentDescription("戳一下 Sam").performScrollTo().assertIsDisplayed()
        val bounds = action.fetchSemanticsNode().boundsInRoot
        val touchTarget = with(rule.density) { 48.dp.toPx() }
        assertTrue(bounds.width >= touchTarget && bounds.height >= touchTarget)
        rule.onNodeWithText("戳一下").assertDoesNotExist()
        saveScreen("channel-members-zh-narrow-large")
        action.performClick()
        rule.onNodeWithTag("poke_confirm").assertIsDisplayed()
        rule.onNodeWithText("取消").assertIsDisplayed()
        saveImage("channel-poke-zh-narrow-large", dialog = true)
    }

    private fun saveScreen(name: String) = saveImage(name, dialog = false)

    private fun saveImage(
        name: String,
        dialog: Boolean,
    ) {
        val output = File(rule.activity.getExternalFilesDir(null), "ui-refinement").apply { mkdirs() }
        val node = if (dialog) rule.onNode(isDialog()) else rule.onRoot()
        File(output, "$name.png").outputStream().use {
            check(node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}

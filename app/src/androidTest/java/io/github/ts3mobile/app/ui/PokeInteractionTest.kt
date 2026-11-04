package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.ts3mobile.app.chat.ChatConversationKey
import io.github.ts3mobile.protocol.TextSendOutcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class PokeInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun iconOpensAMinimalDialogAndSendingPreservesTheChannelTab() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render(locale = "zh-CN") }
        rule.onNodeWithContentDescription("戳一下 Sam").assertIsDisplayed()
        rule.onNodeWithText("戳一下").assertDoesNotExist()
        save("channel-poke-icon")
        rule.onNodeWithContentDescription("戳一下 Sam").performClick()
        rule.onNodeWithText("戳一下 Sam").assertIsDisplayed()
        rule.onNodeWithText("最多 100 UTF-8 字节，可不填留言").assertDoesNotExist()
        save("poke-minimal-dialog", dialog = true)
        rule.onNodeWithTag("poke_confirm").performClick()
        rule.onNodeWithText("已戳一下").assertIsDisplayed()
        rule.onNodeWithContentDescription("戳一下 Sam").assertIsDisplayed()
        rule.onNodeWithTag("chat_draft").assertDoesNotExist()
        rule.runOnIdle { assertEquals(ChatConversationKey.Channel, fixture.chat.value.selected) }
    }

    @Test fun unknownSendResultRemainsVisibleWithoutNavigatingOrRetrying() {
        val fixture = UiFixture(connected = true)
        fixture.sendOutcome = TextSendOutcome.Unknown
        rule.setContent { fixture.Render() }
        rule.onNodeWithContentDescription("Poke Sam").performClick()
        rule.onNodeWithTag("poke_confirm").performClick()
        rule.onNodeWithText("Poke result unconfirmed").assertIsDisplayed()
        rule.onNodeWithContentDescription("Poke Sam").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, fixture.sentPokes.size) }
    }

    @Test fun emptyIncomingPokeShowsTheSenderAndKeepsTheCurrentPage() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render(locale = "zh-CN") }
        rule.runOnIdle { fixture.receivePoke("") }
        rule.onNodeWithText("Sam 戳了你").assertIsDisplayed()
        rule.onNodeWithText("未附加留言。").assertDoesNotExist()
        save("incoming-poke-empty", dialog = true)
        rule.onNode(hasText("关闭") and hasAnyAncestor(isDialog())).performClick()
        rule.onNodeWithContentDescription("戳一下 Sam").assertIsDisplayed()
    }

    @Test fun memberWithDuplicateNameKeepsAClientIdInTheDialog() {
        val fixture = UiFixture(connected = true)
        val peers = fixture.state.value.snapshot.participants.map { if (it.id == 3) it.copy(nickname = "Sam") else it }
        fixture.state.value = fixture.state.value.copy(snapshot = fixture.state.value.snapshot.copy(participants = peers))
        fixture.chatStore.bind(1, 1, fixture.chat.value.binding, participants = peers)
        fixture.refreshChat()
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Users").performClick()
        // The first client's action menu still captures its exact ID and UID.
        val nodes = rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("More actions for Sam"))
        nodes[0].performClick()
        rule.onNodeWithText("Poke").performClick()
        rule.onNodeWithText("Poke Sam (#2)").assertIsDisplayed()
    }

    private fun save(
        name: String,
        dialog: Boolean = false,
    ) {
        val node = if (dialog) rule.onNode(isDialog()) else rule.onRoot()
        val output = File(rule.activity.getExternalFilesDir(null), "poke-ux").apply { mkdirs() }
        File(output, "$name.png").outputStream().use {
            check(node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}

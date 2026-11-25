package io.github.ts3mobile.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.bookmarks.BookmarkConnection
import io.github.ts3mobile.app.chat.ChatBinding
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.protocol.AttachmentDownload
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3Channel
import io.github.ts3mobile.protocol.VirtualServerUid
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ChatAttachmentDialogTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun corruptedDesktopLabelShowsTheExactFilenameInChatAndInTheDownloadDialog() {
        val state = fixtureAttachmentState(checkNotNull(VirtualServerUid.parse(UID)))
        val filename = "QA 中 A+100%.txt"
        val damagedLabel = "QA 中 A+100\uFFFDxt"
        val target = fixtureAttachmentReference(UID, 1).replace("filename=probe.txt", "filename=QA%20%E4%B8%AD%20A%2B100%25.txt")
        rule.setContent {
            TestVerifiedFileSaveHost {
                val actions = rememberChatAttachmentActions(state, ChatBinding(11, 12, 1, 4)) { _, _, _ -> null }
                ChatRichBody("[URL=$target]$damagedLabel[/URL]", false, actions)
            }
        }
        rule.onNodeWithText(damagedLabel).assertDoesNotExist()
        rule.onNodeWithText(filename).assertIsDisplayed().performTouchInput { click(center) }
        rule.onNode(hasText(filename) and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    @Test fun renderingAndConfirmingAreSeparateAndSwitchingAttemptsCancelsPendingDownload() {
        val scope = mutableStateOf(ChatBinding(11, 12, 1, 4))
        val state = fixtureAttachmentState(checkNotNull(VirtualServerUid.parse(UID)))
        val opens = AtomicInteger()
        val downloads = AtomicInteger()
        val cancelled = AtomicBoolean(false)
        rule.setContent {
            TestVerifiedFileSaveHost {
                val actions =
                    rememberChatAttachmentActions(state, scope.value) { token, attempt, file ->
                        assertEquals("fixture-token", token)
                        assertEquals(12L, attempt)
                        assertEquals("probe.txt", file.fileName)
                        opens.incrementAndGet()
                        AttachmentDownload {
                            downloads.incrementAndGet()
                            try {
                                awaitCancellation()
                            } finally {
                                cancelled.set(true)
                            }
                        }
                    }
                ChatRichBody("[url=${fixtureAttachmentReference(UID, 1)}]Shown name[/url]", false, actions)
            }
        }
        val target = rule.onNodeWithText("Shown name")
        target.assertIsDisplayed()
        assertEquals(0, opens.get())
        target.performTouchInput { click(center) }
        rule.onNodeWithText("probe.txt").assertIsDisplayed()
        assertEquals(0, downloads.get())
        rule.onNodeWithText(rule.activity.getString(R.string.chat_attachment_download)).performClick()
        rule.waitUntil(3_000) { downloads.get() == 1 }
        rule.onNodeWithText(rule.activity.getString(R.string.chat_attachment_download)).assertDoesNotExist()
        rule.runOnIdle { scope.value = scope.value.copy(attempt = 13) }
        rule.waitUntil(3_000) { cancelled.get() }
        rule.onNodeWithText("probe.txt").assertDoesNotExist()
    }

    private companion object {
        const val UID = "AAECAwQFBgcICQoLDA0ODxAREhM="
    }
}

internal fun fixtureAttachmentState(uid: VirtualServerUid): TeamSpeakServiceState =
    TeamSpeakServiceState(
        status = ConnectionStatus(ConnectionPhase.CONNECTED),
        bookmarkConnection = BookmarkConnection("fixture-token", "example.invalid", 9987, "Fixture"),
        snapshot = SessionSnapshot(channels = listOf(Ts3Channel(1, 0, 0, "Fixture Channel", 0, false, true)), serverUid = uid),
    )

internal fun fixtureAttachmentReference(
    uid: String,
    channel: Int,
): String =
    "ts3file://not-a-network-target.invalid?port=9987&serverUID=$uid&channel=$channel&" +
        "path=%2Fts3mobile-fixture&filename=probe.txt&isDir=0&size=27&fileDateTime=1716445847"

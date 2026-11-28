package io.github.ts3mobile.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.ts3mobile.app.bookmarks.BookmarkChannelChoice
import io.github.ts3mobile.app.bookmarks.BookmarkChannelEndpoint
import io.github.ts3mobile.app.bookmarks.BookmarkChannelSnapshot
import io.github.ts3mobile.app.bookmarks.BookmarkChannelSnapshotStore
import io.github.ts3mobile.app.bookmarks.BookmarkConnection
import io.github.ts3mobile.app.bookmarks.BookmarkConnectionContext
import io.github.ts3mobile.app.bookmarks.BookmarkState
import io.github.ts3mobile.app.bookmarks.ServerBookmark
import io.github.ts3mobile.protocol.Ts3Channel
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class BookmarkOfflineChannelsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val original =
        ServerBookmark(
            "fixture",
            revision = 1,
            name = "Studio",
            host = "voice.example.com",
            defaultChannelId = 2,
            rememberPasswords = true,
            password = "fixture-server",
            channelPassword = "fixture-channel",
        )
    private val endpoint = checkNotNull(BookmarkChannelEndpoint.parse(original.host, original.port))

    @Test fun explicitSaveCanBeReadOfflineWithoutReusingALiveTokenOrChannelPassword() {
        val store = FakeSnapshots()
        val live =
            mutableStateOf<BookmarkConnectionContext?>(
                BookmarkConnectionContext(
                    BookmarkConnection("fixture-session", original.host, original.port, original.nickname),
                    listOf(Ts3Channel(3, 0, 0, "Studio room", 0, true, false)),
                    3,
                ),
            )
        var saved: ServerBookmark? = null
        rule.setContent {
            BookmarkEditor(
                original,
                BookmarkState(listOf(original), loading = false),
                onSave = { saved = it },
                context = live.value,
                onSaveFromConnection = { _, _ -> error("An offline choice cannot claim a live selection") },
                snapshotStore = store,
                onDismiss = {},
            )
        }
        rule.runOnIdle { assertNull(store.snapshot) }
        rule.onNodeWithText("Save channel list for offline use").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Channel list saved on this device.").fetchSemanticsNodes().isNotEmpty() }
        rule.runOnIdle {
            assertEquals(BookmarkChannelSnapshot(endpoint, listOf(BookmarkChannelChoice(3, "Studio room (#3)"))), store.snapshot)
            live.value = null
        }
        rule.onNodeWithText("Browse saved channels").performScrollTo().performClick()
        rule.onNodeWithText("Saved offline list", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("bookmark_channel_3").performClick()
        rule.onNodeWithText("Save").performClick()
        rule.runOnIdle { assertEquals(original.copy(defaultChannelId = 3, channelPassword = ""), saved) }
    }

    @Test fun forgettingOneEndpointLeavesNoOfflineChoiceAndDoesNotEditTheBookmark() {
        val store = FakeSnapshots(BookmarkChannelSnapshot(endpoint, listOf(BookmarkChannelChoice(3, "Studio room (#3)"))))
        rule.setContent {
            BookmarkEditor(
                original,
                BookmarkState(listOf(original), loading = false),
                onSave = { error("Forgetting a snapshot cannot save a bookmark") },
                snapshotStore = store,
                onDismiss = {},
            )
        }
        rule.onNodeWithText("Browse saved channels").performScrollTo().performClick()
        rule.onNodeWithText("Forget saved list").performClick()
        rule.onNodeWithText("No list saved for this server address and port.").assertIsDisplayed()
        rule.runOnIdle { assertNull(store.snapshot) }
    }

    @Test fun delayedReadFromPreviousPortCannotPopulateTheNewEndpoint() {
        val previous = CompletableDeferred<BookmarkChannelSnapshot>()
        val store =
            object : BookmarkChannelSnapshotStore by FakeSnapshots() {
                override suspend fun read(endpoint: BookmarkChannelEndpoint): BookmarkChannelSnapshot? =
                    if (endpoint == this@BookmarkOfflineChannelsTest.endpoint) previous.await() else null
            }
        rule.setContent {
            BookmarkEditor(
                original,
                BookmarkState(listOf(original), loading = false),
                onSave = {},
                snapshotStore = store,
                onDismiss = {},
            )
        }
        rule.onNodeWithText("Browse saved channels").performScrollTo().performClick()
        rule.onNodeWithText("Working with the local channel list…").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        rule.onNode(hasText("Port") and hasSetTextAction()).performTextReplacement("9988")
        rule.onNodeWithText("Browse saved channels").performScrollTo().performClick()
        previous.complete(BookmarkChannelSnapshot(endpoint, listOf(BookmarkChannelChoice(3, "Previous port (#3)"))))
        rule.onNodeWithText("No list saved for this server address and port.").assertIsDisplayed()
        rule.onNodeWithTag("bookmark_channel_3").assertDoesNotExist()
    }

    private class FakeSnapshots(var snapshot: BookmarkChannelSnapshot? = null) : BookmarkChannelSnapshotStore {
        override suspend fun read(endpoint: BookmarkChannelEndpoint) = snapshot?.takeIf { it.endpoint == endpoint }

        override suspend fun save(snapshot: BookmarkChannelSnapshot) {
            this.snapshot = snapshot
        }

        override suspend fun remove(endpoint: BookmarkChannelEndpoint) {
            if (snapshot?.endpoint == endpoint) snapshot = null
        }
    }
}

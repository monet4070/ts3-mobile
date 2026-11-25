package io.github.ts3mobile.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.ts3mobile.app.chat.ChatHistoryAccount
import io.github.ts3mobile.app.chat.ChatHistoryController
import io.github.ts3mobile.app.chat.ChatHistoryConversation
import io.github.ts3mobile.app.chat.ChatHistoryRecord
import io.github.ts3mobile.app.chat.ChatHistoryRoom
import io.github.ts3mobile.app.chat.ChatHistoryVault
import io.github.ts3mobile.app.crypto.AndroidKeystoreKeys
import io.github.ts3mobile.app.crypto.KeystorePayloadCipher
import io.github.ts3mobile.app.identity.IdentityReadVersion
import io.github.ts3mobile.app.identity.IdentitySnapshot
import io.github.ts3mobile.protocol.Ts3IdentitySummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

class ChatHistorySettingsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun optInListsOnlyCurrentIdentityAndTurningOffErasesEncryptedHistory() {
        val file = File(rule.activity.cacheDir, "history-${UUID.randomUUID()}.preferences_pb")
        val alias = "ts3_test_chat_${UUID.randomUUID()}"
        var worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            fun vault() =
                ChatHistoryVault(
                    PreferenceDataStoreFactory.create(scope = worker) { file },
                    KeystorePayloadCipher(AndroidKeystoreKeys(alias)),
                )
            val store = vault()
            val history = ChatHistoryController(worker, store)
            val active = mutableStateOf("fixture-first")
            val version = mutableStateOf(IdentityReadVersion())
            rule.setContent {
                ChatHistorySettingsScreen(
                    history,
                    version.value,
                    readIdentity = { IdentitySnapshot(Ts3IdentitySummary(active.value, 0, 10), null, false, "fixture") },
                    onBack = {},
                )
            }
            rule.waitUntil(5_000) { history.state.value.ready }
            rule.onNodeWithText("Keep local chat history").assertIsOff()
            rule.onNodeWithText("Browse saved messages").assertDoesNotExist()
            rule.onNodeWithText("Keep local chat history").performClick()
            rule.waitUntil(5_000) { history.state.value.enabled }
            rule.onNodeWithText("Keep local chat history").assertIsOn()
            val first = fixture("fixture-first", "First identity text")
            val second = fixture("fixture-second", "Second identity text")
            runBlocking { store.append(listOf(first, second)) }
            assertFalse(file.readText().contains(first.body))
            assertFalse(file.readText().contains(first.conversation.account.host))

            rule.onNodeWithText("Browse saved messages").performClick()
            awaitText("Channel #12", substring = true)
            rule.onNodeWithText("Channel #12", substring = true).performClick()
            awaitText(first.body)
            rule.onNodeWithText(second.body).assertDoesNotExist()
            rule.runOnIdle {
                active.value = "fixture-second"
                version.value = IdentityReadVersion(1)
            }
            rule.onNodeWithText(first.body).assertDoesNotExist()
            awaitText("Channel #12", substring = true)
            rule.onNodeWithText("Channel #12", substring = true).performClick()
            awaitText(second.body)
            rule.onNodeWithText("Delete this saved conversation").performClick()
            rule.onNodeWithText("Delete saved messages").performClick()
            awaitText("No saved messages")
            runBlocking { assertEquals(listOf(first), store.read("fixture-first")) }

            rule.onNodeWithContentDescription("Back").performClick()
            rule.onNodeWithText("Keep local chat history").performClick()
            rule.onNodeWithText("Turn off and delete history?").assertIsDisplayed()
            rule.onNodeWithText("Cancel").performClick()
            rule.onNodeWithText("Keep local chat history").assertIsOn()
            rule.onNodeWithText("Keep local chat history").performClick()
            rule.onNodeWithText("Delete saved messages").performClick()
            rule.waitUntil(5_000) { !history.state.value.enabled && !history.state.value.busy }
            rule.onNodeWithText("Keep local chat history").assertIsOff()
            runBlocking {
                worker.coroutineContext.job.cancelAndJoin()
                worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val reopened = vault()
                assertFalse(reopened.enabled())
                reopened.setEnabled(true)
                assertTrue(reopened.read("fixture-first").isEmpty())
                assertTrue(reopened.read("fixture-second").isEmpty())
            }
        } finally {
            runBlocking { worker.coroutineContext.job.cancelAndJoin() }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            file.delete()
        }
    }

    private fun fixture(
        uid: String,
        body: String,
    ): ChatHistoryRecord {
        val account = checkNotNull(ChatHistoryAccount.parse("voice.example.com", 9987, uid))
        return ChatHistoryRecord(
            ChatHistoryConversation(account, ChatHistoryRoom.Channel(12)),
            System.currentTimeMillis(),
            false,
            "Fixture",
            body,
            false,
        )
    }

    private fun awaitText(
        text: String,
        substring: Boolean = false,
    ) {
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text, substring).fetchSemanticsNodes().isNotEmpty() }
    }
}

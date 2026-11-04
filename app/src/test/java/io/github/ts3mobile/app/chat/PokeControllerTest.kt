package io.github.ts3mobile.app.chat

import io.github.ts3mobile.protocol.EncodedVoiceSource
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.TextNotSentReason
import io.github.ts3mobile.protocol.TextSendOutcome
import io.github.ts3mobile.protocol.TextTarget
import io.github.ts3mobile.protocol.Ts3Participant
import io.github.ts3mobile.protocol.Ts3PokeEvent
import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.Ts3SessionListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PokeControllerTest {
    private val binding = ChatBinding(1, 1, 10, 7)
    private val peer = Ts3Participant(8, 10, "Fixture", false, false, false, "fixture-uid")
    private val recipient = PokeRecipient(binding, TextTarget.Private(8, "fixture-uid"), "Fixture")

    @Test fun pokeUsesDistinctPortCapturedTargetAndOriginalConversationForCompletion() =
        runTest {
            val client = FakeClient()
            val controller = SessionChatController(this)
            controller.begin(1)
            controller.bind(binding, client, false, listOf(peer)) { true }
            assertTrue(controller.poke(recipient, "once"))
            controller.select(ChatConversationKey.Channel)
            controller.updateDraft("channel draft")
            controller.submit()
            assertFalse(controller.poke(recipient, "second"))
            runCurrent()
            assertEquals(listOf(recipient.target to "once"), client.pokes)
            client.response.complete(TextSendOutcome.Accepted)
            runCurrent()
            assertEquals("channel draft", controller.state.value.draft)
            assertEquals(TextSendOutcome.Accepted, controller.state.value.conversations.last().messages.last().outcome)
        }

    @Test fun identityChangeBeforeWorkerSubmissionCannotRedirectPoke() =
        runTest {
            val client = FakeClient()
            val controller = SessionChatController(this)
            controller.begin(1)
            controller.bind(binding, client, false, listOf(peer)) { true }
            assertTrue(controller.poke(recipient, ""))
            controller.bind(binding, client, false, listOf(peer.copy(uniqueIdentifier = "replacement"))) { true }
            runCurrent()
            assertTrue(client.pokes.isEmpty())
            assertTrue(controller.state.value.conversations.last().messages.last().outcome is TextSendOutcome.NotSent)
        }

    @Test fun unknownResultNeverRetriesAndManualClearRejectsOldNoticeAndCompletion() =
        runTest {
            val client = FakeClient()
            val controller = SessionChatController(this, timeoutMs = 100)
            controller.begin(1)
            controller.bind(binding, client, false, listOf(peer)) { true }
            assertTrue(controller.poke(recipient, ""))
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            assertEquals(TextSendOutcome.Unknown, controller.state.value.conversations.last().messages.last().outcome)
            client.response.complete(TextSendOutcome.Accepted)
            runCurrent()
            assertEquals(TextSendOutcome.Unknown, controller.state.value.conversations.last().messages.last().outcome)
            assertEquals(1, client.pokes.size)
            controller.clear()
            controller.receivePoke(1, 1, Ts3PokeEvent(8, "fixture-uid", "Fixture", "old"))
            assertNull(controller.state.value.pokeNotice)
            assertFalse(controller.poke(recipient, "after exit"))
        }

    private class FakeClient : Ts3SessionClient {
        val pokes = mutableListOf<Pair<TextTarget.Private, String>>()
        val response = CompletableDeferred<TextSendOutcome>()

        override suspend fun pokeClient(
            target: TextTarget.Private,
            reason: String,
            timeoutMs: Long,
            canSubmit: () -> Boolean,
        ): TextSendOutcome {
            if (!canSubmit()) return TextSendOutcome.NotSent(TextNotSentReason.CONTEXT_CHANGED)
            pokes += target to reason
            return response.await()
        }

        override suspend fun sendTextMessage(
            target: TextTarget,
            body: String,
            timeoutMs: Long,
            canSubmit: () -> Boolean,
        ): TextSendOutcome = error("poke must not use the text port")

        override fun connect(
            config: ServerConfig,
            identityMaterial: String,
            listener: Ts3SessionListener,
        ) = Unit

        override fun setVoiceSource(source: EncodedVoiceSource?) = Unit

        override fun joinChannel(
            channelId: Int,
            password: String,
        ) = Unit

        override fun disconnect(reason: String) = Unit

        override fun close() = Unit
    }
}

package io.github.ts3mobile.app.chat

import io.github.ts3mobile.protocol.EncodedVoiceSource
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.TextNotSentReason
import io.github.ts3mobile.protocol.TextSendOutcome
import io.github.ts3mobile.protocol.TextTarget
import io.github.ts3mobile.protocol.TextTargetMode
import io.github.ts3mobile.protocol.Ts3Participant
import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.Ts3SessionListener
import io.github.ts3mobile.protocol.Ts3TextEvent
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
class SessionChatControllerTest {
    private val binding = ChatBinding(1, 1, 10, 7)

    @Test fun duplicateClickUsesCapturedClientAndKeepsFollowingDraft() =
        runTest {
            val client = FakeClient()
            val controller = SessionChatController(this)
            controller.begin(1)
            controller.bind(binding, client, false) { true }
            controller.updateDraft("once")
            controller.submit()
            controller.updateDraft("next")
            controller.submit()
            runCurrent()
            assertEquals(listOf("once"), client.bodies)
            assertEquals(listOf(TextTarget.Channel(10)), client.targets)
            client.response.complete(TextSendOutcome.Accepted)
            runCurrent()
            assertEquals(TextSendOutcome.Accepted, controller.state.value.messages.last().outcome)
            assertEquals("next", controller.state.value.draft)
        }

    @Test fun queuedSendCannotBeRedirectedToAnotherChannel() =
        runTest {
            val client = FakeClient()
            val controller = SessionChatController(this)
            controller.begin(1)
            controller.bind(binding, client, false) { true }
            controller.updateDraft("captured")
            controller.submit()
            controller.bind(binding.copy(channelId = 20), client, false) { true }
            runCurrent()
            assertTrue(client.bodies.isEmpty())
            assertTrue(controller.state.value.messages.first { it.kind == ChatMessageKind.OUTGOING }.outcome is TextSendOutcome.NotSent)
        }

    @Test fun timeoutIsUnknownAndLateCompletionNeverChangesItOrResends() =
        runTest {
            val client = FakeClient()
            val controller = SessionChatController(this, timeoutMs = 100)
            controller.begin(1)
            controller.bind(binding, client, false) { true }
            controller.updateDraft("one")
            controller.submit()
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            assertEquals(TextSendOutcome.Unknown, controller.state.value.messages.last().outcome)
            client.response.complete(TextSendOutcome.Accepted)
            runCurrent()
            assertEquals(TextSendOutcome.Unknown, controller.state.value.messages.last().outcome)
            assertEquals(1, client.bodies.size)
        }

    @Test fun replacementAttemptRejectsOldReceiveAndCompletionInSameManualScope() =
        runTest {
            val old = FakeClient()
            val replacement = FakeClient()
            val controller = SessionChatController(this)
            controller.begin(1)
            controller.bind(binding, old, false) { true }
            controller.updateDraft("old")
            controller.submit()
            runCurrent()
            controller.updateDraft("next")
            controller.pause(1, 1)
            assertFalse(controller.state.value.canSend)
            controller.bind(binding.copy(attempt = 2), replacement, false) { true }
            controller.receive(1, 1, Ts3TextEvent(TextTargetMode.CHANNEL, null, 8, null, "Fixture", "stale"))
            old.response.complete(TextSendOutcome.Accepted)
            runCurrent()
            assertFalse(controller.state.value.messages.any { it.body == "stale" })
            assertEquals(TextSendOutcome.Unknown, controller.state.value.messages.last().outcome)
            assertEquals("next", controller.state.value.draft)
        }

    @Test fun terminalClearInvalidatesEverythingBeforeCancelledWorkRuns() =
        runTest {
            val client = FakeClient()
            val controller = SessionChatController(this)
            controller.begin(1)
            controller.bind(binding, client, false) { true }
            controller.updateDraft("private")
            controller.submit()
            controller.clear(1)
            runCurrent()
            assertTrue(client.bodies.isEmpty())
            assertNull(controller.state.value.scope)
            assertTrue(controller.state.value.messages.isEmpty())
        }

    @Test fun cancelledLazySendCannotLeavePermanentSendingWhenChannelDisappears() =
        runTest {
            val client = FakeClient()
            val controller = SessionChatController(this)
            controller.begin(1)
            controller.bind(binding, client, false) { true }
            controller.updateDraft("queued")
            controller.submit()
            controller.unbound(1, 1)
            runCurrent()
            assertTrue(client.bodies.isEmpty())
            assertFalse(controller.state.value.sending)
            assertEquals(TextSendOutcome.Unknown, controller.state.value.messages.last().outcome)
            controller.pause(1, 1)
            assertFalse(controller.state.value.connected)
            assertTrue(controller.state.value.established)
        }

    @Test fun selectedConversationChangeCannotRedirectQueuedPrivateSendOrItsResult() =
        runTest {
            val client = FakeClient()
            val controller = SessionChatController(this)
            val sam = Ts3Participant(8, 10, "Sam", false, false, false, "sam-uid")
            controller.begin(1)
            controller.bind(binding, client, false, listOf(sam)) { true }
            controller.openPrivate(8)
            controller.updateDraft("private once")
            controller.submit()
            controller.select(ChatConversationKey.Channel)
            controller.updateDraft("channel draft")
            controller.submit()
            runCurrent()
            assertEquals(listOf(TextTarget.Private(8, "sam-uid")), client.targets)
            assertEquals(listOf("private once"), client.bodies)
            client.response.complete(TextSendOutcome.Accepted)
            runCurrent()
            assertEquals("channel draft", controller.state.value.draft)
            assertEquals(TextSendOutcome.Accepted, controller.state.value.conversations.last().messages.last().outcome)
        }

    @Test fun privateQueuedBeforeSnapshotIdentityChangeIsNotSubmitted() =
        runTest {
            val client = FakeClient()
            val controller = SessionChatController(this)
            val sam = Ts3Participant(8, 10, "Sam", false, false, false, "sam-uid")
            controller.begin(1)
            controller.bind(binding, client, false, listOf(sam)) { true }
            controller.openPrivate(8)
            controller.updateDraft("must not redirect")
            controller.submit()
            controller.bind(binding, client, false, listOf(sam.copy(uniqueIdentifier = "replacement"))) { true }
            runCurrent()
            assertTrue(client.bodies.isEmpty())
            assertTrue(controller.state.value.messages.last().outcome is TextSendOutcome.NotSent)
        }

    @Test fun channelSpecificCompatibilityEntryNeverSubmitsPrivateDraft() =
        runTest {
            val client = FakeClient()
            val controller = SessionChatController(this)
            val sam = Ts3Participant(8, 10, "Sam", false, false, false, "sam-uid")
            controller.begin(1)
            controller.bind(binding, client, false, listOf(sam)) { true }
            controller.openPrivate(8)
            controller.updateDraft("private draft")
            controller.submitChannel()
            runCurrent()
            assertEquals("private draft", controller.state.value.draft)
            assertTrue(client.bodies.isEmpty())
        }

    private class FakeClient : Ts3SessionClient {
        val bodies = mutableListOf<String>()
        val targets = mutableListOf<TextTarget>()
        val response = CompletableDeferred<TextSendOutcome>()

        override suspend fun sendTextMessage(
            target: TextTarget,
            body: String,
            timeoutMs: Long,
            canSubmit: () -> Boolean,
        ): TextSendOutcome {
            if (!canSubmit()) return TextSendOutcome.NotSent(TextNotSentReason.CONTEXT_CHANGED)
            bodies += body
            targets += target
            return response.await()
        }

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

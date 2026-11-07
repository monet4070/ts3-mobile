package io.github.ts3mobile.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.ts3mobile.app.MainActivity
import io.github.ts3mobile.app.chat.ChatBinding
import io.github.ts3mobile.app.chat.ChatStore
import io.github.ts3mobile.app.chat.SessionChatController
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.TextTargetMode
import io.github.ts3mobile.protocol.Ts3Channel
import io.github.ts3mobile.protocol.Ts3Participant
import io.github.ts3mobile.protocol.Ts3TextEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Activity/Binder/Intent entry, with test-only reflection to seed offline service memory. */
class ChatNotificationActivityTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun coldActivityKeepsRequestUntilItsServiceBindingArrives() =
        withService { fixture ->
            val token = fixture.token(single = true)
            ActivityScenario.launch<MainActivity>(PrivateMessageNotificationIntent.create(context, token)).use {
                rule.onNodeWithText("Synthetic private 2").assertIsDisplayed()
                assertEquals(1, fixture.store.state.value.totalUnread)
                assertFalse(fixture.service.mutableState.value.isCapturing)
            }
        }

    @Test fun warmIntentReusesActivityAndMultipleConversationsWaitForUserSelection() =
        withService { fixture ->
            val launchIntent = Intent(context, MainActivity::class.java)
            ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
                rule.onNodeWithText("Fixture channel").assertIsDisplayed()
                var original: MainActivity? = null
                scenario.onActivity { original = it }
                try {
                    val notification = PrivateMessageNotifications(context).build(fixture.token(single = false), false)
                    scenario.onActivity { notification.contentIntent.send() }
                    // PendingIntent delivery includes a native pause/resume transition,
                    // which can outlive Compose idling. Keep the visibility assertion.
                    rule.waitUntil(5_000) {
                        runCatching { rule.onNodeWithTag("chat_notification_conversations").assertIsDisplayed() }.isSuccess
                    }
                    rule.onNodeWithTag("chat_notification_conversations").assertIsDisplayed()
                    instrumentation.runOnMainSync {
                        assertTrue(checkNotNull(original).window.decorView.isAttachedToWindow)
                        assertTrue(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single() === original)
                    }
                    assertEquals(2, fixture.store.state.value.totalUnread)
                    val second = fixture.store.state.value.conversations.last()
                    rule.onNodeWithTag("chat_notification_conversation_${second.id}").performClick()
                    rule.onNodeWithText("Synthetic private 3").assertIsDisplayed()
                    rule.onNodeWithText("Users").performClick()
                    rule.onNodeWithTag("chat_messages").assertDoesNotExist()
                    assertEquals(1, fixture.store.state.value.totalUnread)
                } finally {
                    finishHotActivity(checkNotNull(original), launchIntent)
                }
            }
        }

    @Test fun closedTargetCannotNavigateAWarmActivity() =
        withService { fixture ->
            val launchIntent = Intent(context, MainActivity::class.java)
            ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
                rule.onNodeWithText("Fixture channel").assertIsDisplayed()
                var original: MainActivity? = null
                scenario.onActivity { original = it }
                try {
                    val token = fixture.token(single = true)
                    fixture.store.close(fixture.store.state.value.conversations[1].key)
                    scenario.onActivity { it.startActivity(PrivateMessageNotificationIntent.create(context, token)) }
                    rule.waitUntil(5_000) {
                        var delivered = false
                        instrumentation.runOnMainSync {
                            delivered = PrivateMessageNotificationIntent.token(checkNotNull(original).intent) == token
                        }
                        delivered
                    }
                    rule.onNodeWithTag("chat_messages").assertDoesNotExist()
                    assertEquals(1, fixture.store.state.value.totalUnread)
                } finally {
                    finishHotActivity(checkNotNull(original), launchIntent)
                }
            }
        }

    private fun finishHotActivity(
        activity: MainActivity,
        launchIntent: Intent,
    ) {
        // ActivityScenario matches lifecycle callbacks against the launch Intent.
        // onNewIntent has already been verified; restore only its harness identity
        // before the real Activity destruction, without a production test branch.
        instrumentation.runOnMainSync {
            activity.intent = launchIntent
            activity.finish()
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun unresolvableColdIntentOnlyOpensDisconnectedApplication() {
        val token = "11111111-1111-4111-8111-111111111111"
        ActivityScenario.launch<MainActivity>(PrivateMessageNotificationIntent.create(context, token)).use {
            rule.onNodeWithTag("chat_messages").assertDoesNotExist()
            rule.onNodeWithText("Connect", useUnmergedTree = true).assertIsDisplayed()
        }
    }

    private class Fixture(val service: TeamSpeakService, val store: ChatStore, val registry: ChatNotificationRegistry) {
        fun token(single: Boolean): String =
            checkNotNull(
                registry.issue(
                    store.state.value,
                    store.state.value.conversations.filter { it.isPrivate }.let { conversations ->
                        if (single) setOf(conversations.first().id) else conversations.map { it.id }.toSet()
                    },
                ),
            )
    }

    private fun withService(test: (Fixture) -> Unit) {
        val connected = CountDownLatch(1)
        var service: TeamSpeakService? = null
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    binder: IBinder?,
                ) {
                    service = field(checkNotNull(binder), "this\$0")
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
        instrumentation.runOnMainSync {
            assertTrue(context.bindService(Intent(context, TeamSpeakService::class.java), connection, Context.BIND_AUTO_CREATE))
        }
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            val actual = checkNotNull(service)
            val controller: SessionChatController = field(actual, "chatController")
            val store: ChatStore = field(controller, "store")
            val registry: ChatNotificationRegistry = field(actual, "chatNotificationRegistry")
            val peers = (1..3).map { Ts3Participant(it, 10, "Fixture $it", false, false, false, "fixture-$it") }
            store.begin(1)
            store.bind(1, 1, ChatBinding(1, 1, 10, 1), participants = peers)
            listOf(2, 3).forEach {
                store.receive(1, 1, Ts3TextEvent(TextTargetMode.PRIVATE, 1, it, "fixture-$it", "Fixture $it", "Synthetic private $it"))
            }
            actual.mutableState.value =
                TeamSpeakServiceState(
                    status = ConnectionStatus(ConnectionPhase.CONNECTED),
                    snapshot =
                        SessionSnapshot(
                            channels = listOf(Ts3Channel(10, 0, 0, "Fixture channel", 3, false, true)),
                            participants = peers,
                            ownClientId = 1,
                        ),
                )
            field<PrivateMessageAlertObserver>(actual, "privateMessageAlerts").setAppVisible(true)
            instrumentation.waitForIdleSync()
            test(Fixture(actual, store, registry))
        } finally {
            instrumentation.runOnMainSync { context.unbindService(connection) }
            context.stopService(Intent(context, TeamSpeakService::class.java))
            instrumentation.waitForIdleSync()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(
        owner: Any,
        name: String,
    ): T = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
}

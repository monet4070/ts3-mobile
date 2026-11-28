package io.github.ts3mobile.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.ManualInstrumentation
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.chat.ChatBinding
import io.github.ts3mobile.app.chat.ChatMessage
import io.github.ts3mobile.app.chat.ChatMessageKind
import io.github.ts3mobile.protocol.AttachmentDownload
import io.github.ts3mobile.protocol.ChannelFileResult
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.ServerImage
import io.github.ts3mobile.protocol.ServerImages
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.TextSendOutcome
import io.github.ts3mobile.protocol.TextTarget
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import io.github.ts3mobile.protocol.Ts3SessionListener
import io.github.ts3mobile.protocol.Ts3TextEvent
import io.github.ts3mobile.protocol.Ts3jSessionClient
import io.github.ts3mobile.protocol.VirtualServerUid
import io.github.ts3mobile.protocol.VoiceFrame
import io.github.ts3mobile.protocol.VoiceTimingCollector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@ManualInstrumentation
class LocalChatAttachmentAndroidProbeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun realServerMessageAndIconUseTheAndroidUiAndSaveExactBytes() {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("ts3UsbFixturePort")?.toIntOrNull()
        val uid = args.getString("ts3FileFixtureUid")
        assumeTrue("Explicit isolated attachment fixture required", port in 1..65_535 && uid != null)
        val fixtureUid = checkNotNull(uid)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val verifyAvatar = args.getString("ts3AvatarFixture") == "true"
        if (args.getString("ts3SystemPicker") == "true") {
            FixtureSystemDocumentPicker(instrumentation.uiAutomation).use { picker ->
                withFixtureClients(checkNotNull(port), fixtureUid, null, verifyAvatar, picker)
                assertArrayEquals("TS3 Mobile file acceptance\n".toByteArray(), picker.readSavedBytes())
            }
            return
        }
        val packageName = instrumentation.context.packageName
        val destination = Uri.parse("content://$packageName.backup-documents/${UUID.randomUUID()}")
        val resolver = rule.activity.contentResolver
        checkNotNull(resolver.acquireContentProviderClient(destination)).use { }
        try {
            withFixtureClients(checkNotNull(port), fixtureUid, destination, verifyAvatar)
            val saved = checkNotNull(resolver.openInputStream(destination)).bufferedReader().use { it.readText() }
            assertEquals("TS3 Mobile file acceptance\n", saved)
        } catch (error: Throwable) {
            runCatching { resolver.delete(destination, null, null) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        resolver.delete(destination, null, null)
    }

    private fun withFixtureClients(
        port: Int,
        uid: String,
        destination: Uri?,
        verifyAvatar: Boolean,
        systemPicker: FixtureSystemDocumentPicker? = null,
    ) {
        UsbLoopbackVoiceRelay(port).use { receive ->
            UsbLoopbackVoiceRelay(port).use { send ->
                Ts3jSessionClient(VoiceTimingCollector(), verifyAttachmentIdentity = true).use { reader ->
                    Ts3jSessionClient().use { sender ->
                        val received = FixtureListener()
                        val sending = FixtureListener()
                        reader.connect(
                            ServerConfig(receive.clientHost, receive.clientPort, "Android Attachment"),
                            Ts3IdentityCodec.generate(),
                            received,
                        )
                        sender.connect(
                            ServerConfig(send.clientHost, send.clientPort, "Android Fixture Sender"),
                            Ts3IdentityCodec.generate(),
                            sending,
                        )
                        rule.waitUntil(6_000) { received.snapshot.currentChannelId != null && sending.snapshot.currentChannelId != null }
                        val channel = checkNotNull(received.snapshot.currentChannelId)
                        assertEquals(channel, sending.snapshot.currentChannelId)
                        assertEquals(VirtualServerUid.parse(uid), received.snapshot.serverUid)
                        checkNotNull(received.snapshot.serverIcon)
                        val body = "[URL=${fixtureAttachmentReference(uid, channel)}]Received sample[/URL]"
                        assertEquals(
                            TextSendOutcome.Accepted,
                            runBlocking { sender.sendTextMessage(TextTarget.Channel(channel), body, 5_000) },
                        )
                        rule.waitUntil(6_000) { received.message?.body == body }
                        if (verifyAvatar) {
                            rule.waitUntil(15_000) {
                                received.snapshot.participants.any { it.nickname == "Fixture Avatar Subject" && it.avatar != null }
                            }
                        }
                        val event = checkNotNull(received.message)
                        showAndDownload(reader, received.snapshot, event.body, destination, verifyAvatar, systemPicker)
                        receive.checkHealthy()
                        send.checkHealthy()
                    }
                }
            }
        }
    }

    private fun showAndDownload(
        session: Ts3jSessionClient,
        snapshot: SessionSnapshot,
        body: String,
        destination: Uri?,
        verifyAvatar: Boolean,
        systemPicker: FixtureSystemDocumentPicker?,
    ) {
        val state = fixtureAttachmentState(checkNotNull(snapshot.serverUid)).copy(snapshot = snapshot)
        val binding = ChatBinding(1, 1, snapshot.currentChannelId, snapshot.ownClientId)
        val inChat = mutableStateOf(false)
        val inUsers = mutableStateOf(false)
        val downloads = AtomicInteger()
        val imageRequests = CopyOnWriteArrayList<ServerImage>()
        val images = checkNotNull(session.serverImages())
        val trackedImages =
            object : ServerImages {
                override suspend fun download(image: ServerImage): ChannelFileResult<ByteArray> {
                    imageRequests.add(image)
                    return images.download(image)
                }
            }
        val owner = destination?.let(::fixturePicker)
        rule.setContent {
            val registry = owner ?: rule.activity
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                if (inChat.value) {
                    val actions =
                        rememberChatAttachmentActions(state, binding) { _, _, file ->
                            val path = session.attachmentDownload(file) ?: return@rememberChatAttachmentActions null
                            AttachmentDownload { directory ->
                                downloads.incrementAndGet()
                                path.stage(directory)
                            }
                        }
                    ChatMessageRow(
                        ChatMessage(1, 1, 0, ChatMessageKind.INCOMING, snapshot.currentChannelId, "Fixture", body),
                        attachments = actions,
                    )
                } else {
                    val previews = remember { ServerImagePreviewScope { trackedImages } }
                    previews.Show(snapshot)
                    if (inUsers.value) {
                        ParticipantList(state, { _, _ -> }, { _, _ -> }, previews = previews)
                    } else {
                        ChannelList(state, { _, _ -> }, previews = previews)
                    }
                }
            }
        }
        val iconLabel = rule.activity.getString(R.string.server_image_server_icon)
        rule.onNodeWithText(iconLabel).assertIsDisplayed()
        assertEquals(emptyList<ServerImage>(), imageRequests.toList())
        rule.onNodeWithText(iconLabel).performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithContentDescription(iconLabel).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(rule.activity.getString(R.string.action_close)).performClick()
        val channelIcon = rule.activity.getString(R.string.server_image_channel_icon)
        rule.onNodeWithContentDescription(channelIcon).performClick()
        rule.waitUntil(6_000) {
            rule.onAllNodes(hasContentDescription(channelIcon) and !hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(rule.activity.getString(R.string.action_close)).performClick()
        if (verifyAvatar) {
            val subject = snapshot.participants.single { it.nickname == "Fixture Avatar Subject" }
            val avatar = checkNotNull(subject.avatar)
            val avatarLabel = rule.activity.getString(R.string.server_image_avatar)
            rule.onNodeWithContentDescription(avatarLabel).assertDoesNotExist()
            assertEquals(listOf(snapshot.serverIcon), imageRequests.toList())
            rule.runOnIdle { inUsers.value = true }
            rule.onNodeWithText(subject.nickname).assertIsDisplayed()
            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("participant_avatar_thumbnail", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithContentDescription(avatarLabel).performClick()
            rule.waitUntil(15_000) {
                rule.onAllNodes(hasContentDescription(avatarLabel) and !hasClickAction()).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText(rule.activity.getString(R.string.action_close)).performClick()
            assertEquals(listOf(snapshot.serverIcon, avatar), imageRequests.toList())
        }
        rule.runOnIdle { inChat.value = true }

        val attachment = rule.onNodeWithText("Received sample")
        attachment.assertIsDisplayed()
        assertEquals(0, downloads.get())
        attachment.performTouchInput { click(center) }
        rule.onNodeWithText("probe.txt").assertIsDisplayed()
        assertEquals(0, downloads.get())
        rule.onNodeWithText(rule.activity.getString(R.string.chat_attachment_download)).performClick()
        val save = rule.activity.getString(R.string.channel_files_save)
        rule.waitUntil(15_000) { rule.onAllNodesWithText(save).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(save).performClick()
        systemPicker?.save("probe.txt")
        val saved = rule.activity.getString(R.string.channel_files_saved)
        rule.waitUntil(6_000) { rule.onAllNodesWithText(saved).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, downloads.get())
    }

    private fun fixturePicker(destination: Uri): ActivityResultRegistryOwner =
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry =
                object : ActivityResultRegistry() {
                    override fun <Input, Output> onLaunch(
                        requestCode: Int,
                        contract: ActivityResultContract<Input, Output>,
                        input: Input,
                        options: ActivityOptionsCompat?,
                    ) {
                        assertTrue(contract is ActivityResultContracts.CreateDocument)
                        assertEquals("probe.txt", input)
                        assertTrue(dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(destination)))
                    }
                }
        }

    private class FixtureListener : Ts3SessionListener {
        @Volatile var snapshot = SessionSnapshot.Empty

        @Volatile var message: Ts3TextEvent? = null

        override fun onStatusChanged(status: ConnectionStatus) = Unit

        override fun onSnapshotChanged(snapshot: SessionSnapshot) {
            this.snapshot = snapshot
        }

        override fun onVoiceFrame(frame: VoiceFrame) = Unit

        override fun onTextMessage(event: Ts3TextEvent) {
            message = event
        }
    }
}

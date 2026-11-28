package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.protocol.ChannelFileResult
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.ServerImage
import io.github.ts3mobile.protocol.ServerImages
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3Channel
import io.github.ts3mobile.protocol.Ts3Participant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

class ServerImagePreviewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun channelIconsDownloadOnlyOnTapAndChannelMembersHaveNoAvatarAction() {
        val icon = checkNotNull(ServerImage.icon("3234688722"))
        val avatar = checkNotNull(ServerImage.avatar("AAECAwQFBgcICQoLDA0ODxAREhM=", "f".repeat(32)))
        val snapshot =
            mutableStateOf(
                SessionSnapshot(
                    channels = listOf(Ts3Channel(1, 0, 0, "Fixture", 1, false, true, icon = icon)),
                    participants = listOf(Ts3Participant(1, 1, "Fixture client", false, false, false, avatar = avatar)),
                    ownClientId = 1,
                    serverIcon = icon,
                ),
            )
        val requests = CopyOnWriteArrayList<ServerImage>()
        val loader =
            object : ServerImages {
                override suspend fun download(image: ServerImage): ChannelFileResult<ByteArray> {
                    requests += image
                    return ChannelFileResult.Success(previewPng())
                }
            }
        rule.setContent {
            val previews = remember { ServerImagePreviewScope { loader } }
            previews.Show(snapshot.value)
            ChannelList(
                TeamSpeakServiceState(status = ConnectionStatus(ConnectionPhase.CONNECTED), snapshot = snapshot.value),
                { _, _ -> },
                previews = previews,
            )
        }
        val server = rule.activity.getString(R.string.server_image_server_icon)
        val channel = rule.activity.getString(R.string.server_image_channel_icon)
        val person = rule.activity.getString(R.string.server_image_avatar)
        val close = rule.activity.getString(R.string.action_close)
        rule.onNodeWithText(server).assertIsDisplayed()
        rule.onNodeWithContentDescription(channel).assertIsDisplayed()
        rule.onNodeWithContentDescription(person).assertDoesNotExist()
        rule.runOnIdle { assertEquals(emptyList<ServerImage>(), requests.toList()) }

        rule.onNodeWithText(server).performClick()
        awaitPreview(server)
        rule.onNodeWithText(close).performClick()
        rule.onNodeWithContentDescription(channel).performClick()
        awaitPreview(channel)
        rule.runOnIdle {
            assertEquals(listOf(icon), requests.toList())
            snapshot.value = snapshot.value.copy(channels = emptyList())
        }
        rule.onNodeWithText(close).assertDoesNotExist()
    }

    @Test fun onlyVisibleAvatarsLoadAndPreviewReusesTheThumbnail() {
        val avatar = checkNotNull(ServerImage.avatar("AAECAwQFBgcICQoLDA0ODxAREhM=", "f".repeat(32)))
        val offscreenAvatar = checkNotNull(ServerImage.avatar("AAECAwQFBgcICQoLDA0ODxAREhM=", "e".repeat(32)))
        val snapshot =
            mutableStateOf(
                SessionSnapshot(
                    participants =
                        listOf(
                            Ts3Participant(1, 1, "With avatar", false, false, false, avatar = avatar),
                            Ts3Participant(2, 1, "Without avatar", false, true, false),
                            Ts3Participant(3, 1, "Another user", false, false, false),
                            Ts3Participant(4, 1, "Offscreen", false, false, false, avatar = offscreenAvatar),
                        ),
                    ownClientId = 1,
                ),
            )
        val requests = CopyOnWriteArrayList<ServerImage>()
        val loader =
            object : ServerImages {
                override suspend fun download(image: ServerImage): ChannelFileResult<ByteArray> {
                    requests += image
                    return ChannelFileResult.Success(previewPng())
                }
            }
        rule.setContent {
            val previews = remember { ServerImagePreviewScope { loader } }
            previews.Show(snapshot.value)
            Box(Modifier.height(120.dp)) {
                ParticipantList(TeamSpeakServiceState(snapshot = snapshot.value), { _, _ -> }, { _, _ -> }, previews = previews)
            }
        }
        val person = rule.activity.getString(R.string.server_image_avatar)
        val thumbnail = rule.onNodeWithTag("participant_avatar_thumbnail", useUnmergedTree = true)
        rule.waitUntil(8_000) { runCatching { thumbnail.fetchSemanticsNode() }.isSuccess }
        thumbnail.assertIsDisplayed()
        rule.onNodeWithText("Without avatar").assertIsDisplayed()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.participant_input_muted)).assertIsDisplayed()
        rule.onAllNodes(hasContentDescription(person) and hasClickAction()).assertCountEquals(1)
        rule.onNode(hasContentDescription(person) and hasClickAction()).performClick()
        awaitPreview(person)
        rule.runOnIdle {
            assertEquals(listOf(avatar), requests.toList())
            snapshot.value = snapshot.value.copy(participants = snapshot.value.participants.map { it.copy(avatar = null) })
        }
        rule.onNodeWithContentDescription(person).assertDoesNotExist()
        thumbnail.assertDoesNotExist()
    }

    private fun awaitPreview(label: String) {
        val image = hasContentDescription(label) and hasAnyAncestor(isDialog())
        rule.waitUntil(8_000) { rule.onAllNodes(image).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(image).assertIsDisplayed()
    }
}

internal fun previewPng(width: Int = 1): ByteArray {
    val bitmap = Bitmap.createBitmap(width, 1, Bitmap.Config.ARGB_8888)
    return try {
        val bytes = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
        bytes.toByteArray()
    } finally {
        bitmap.recycle()
    }
}

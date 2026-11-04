package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.ts3mobile.audio.opus.AudioRouteKind
import io.github.ts3mobile.audio.opus.AudioRouteOption
import io.github.ts3mobile.audio.opus.AudioRoutingState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class WarmSurfaceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun audioRouteMenuUsesWarmPaper() = routeMenu(dark = false)

    @Test fun audioRouteMenuUsesWarmDarkSurface() = routeMenu(dark = true)

    @Test fun participantMenuUsesWarmPaper() = participantMenu(dark = false)

    @Test fun participantMenuUsesWarmDarkSurface() = participantMenu(dark = true)

    @Test fun pokeDialogUsesWarmPaper() = pokeDialog(dark = false)

    @Test fun pokeDialogUsesWarmDarkSurface() = pokeDialog(dark = true)

    @Test fun connectionHasAPlainHeadingAndNoPromotionalCopy() = connection(locale = "en")

    @Test fun chineseConnectionHasAPlainHeadingAndNoPromotionalCopy() = connection(locale = "zh-CN")

    private fun routeMenu(dark: Boolean) {
        val fixture = UiFixture(connected = true)
        fixture.state.value =
            fixture.state.value.copy(
                audioRouting =
                    AudioRoutingState(
                        routes =
                            listOf(
                                AudioRoutingState.SystemRoute,
                                AudioRouteOption(1, AudioRouteKind.EARPIECE),
                                AudioRouteOption(2, AudioRouteKind.SPEAKER),
                            ),
                    ),
            )
        rule.setContent { fixture.Render(dark = dark, locale = "zh-CN") }
        rule.onNodeWithContentDescription("选择音频设备，当前为 系统自动").performClick()
        saveWarmContainer("audio-route-$dark", popup = true)
    }

    private fun participantMenu(dark: Boolean) {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render(dark = dark, locale = "zh-CN") }
        rule.onNodeWithText("用户").performClick()
        rule.onNodeWithContentDescription("用户操作：Sam").performClick()
        rule.onNodeWithText("发送私信").assertIsDisplayed()
        rule.onNodeWithText("戳一下").assertIsDisplayed()
        saveWarmContainer("participant-menu-$dark", popup = true)
    }

    private fun pokeDialog(dark: Boolean) {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render(dark = dark, locale = "zh-CN") }
        rule.onNodeWithContentDescription("戳一下 Sam").performClick()
        rule.onNodeWithTag("poke_confirm").assertIsDisplayed()
        saveWarmContainer("poke-dialog-$dark", popup = false)
    }

    private fun connection(locale: String) {
        val fixture = UiFixture()
        rule.setContent { fixture.Render(locale = locale) }
        val chinese = locale == "zh-CN"
        rule.onNodeWithText(if (chinese) "连接服务器" else "Connect to server").assertIsDisplayed()
        rule.onNodeWithText(if (chinese) "连接" else "Connect").assertIsDisplayed()
        listOf(
            "VOICE, CONNECTED",
            "Your people. Your server. One connection away.",
            "Direct to your server. Your identity stays on this device.",
            "让声音，保持连接",
            "连接你的服务器，让熟悉的声音即刻相聚。",
            "直连你的服务器，身份密钥保留在本机。",
        ).forEach { rule.onNodeWithText(it).assertDoesNotExist() }
        val output = File(rule.activity.getExternalFilesDir(null), "ui-refinement").apply { mkdirs() }
        File(output, "connection-$locale.png").outputStream().use {
            check(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun saveWarmContainer(
        name: String,
        popup: Boolean,
    ) {
        val bitmap = rule.onNode(if (popup) isPopup() else isDialog()).captureToImage()
        val pixels = bitmap.toPixelMap()
        // The middle of the top inset is inside the card and above its text.
        val background = pixels[pixels.width / 2, 4]
        assertTrue("Opaque container: $background", background.alpha > 0.99f)
        assertTrue("Warm rendered container: $background", background.red > background.green && background.green > background.blue)
        val output = File(rule.activity.getExternalFilesDir(null), "ui-refinement").apply { mkdirs() }
        File(output, "$name.png").outputStream().use {
            check(bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}

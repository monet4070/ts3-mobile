package io.github.ts3mobile.app.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import io.github.ts3mobile.app.service.RECOMMENDED_RETRY_WAIT_SECONDS
import io.github.ts3mobile.app.service.UserMessage
import io.github.ts3mobile.protocol.ConnectionFailureCategory
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class ConnectionLimitGuidanceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun manualGuidanceExplainsTheWaitAndSafeExitInBothLanguages() {
        val messages = listOf(UserMessage.ConnectionLimitWait)
        for (locale in listOf("en", "zh-CN")) {
            val context = localizedContext(locale)
            for (message in messages) {
                val text = message.resolve(context)
                assertTrue(text, text.contains(RECOMMENDED_RETRY_WAIT_SECONDS.toString()))
                assertTrue(text, text.contains(if (locale == "en") "Disconnect" else "断开连接"))
                assertTrue(text, text.contains(if (locale == "en") "swip" else "划掉"))
                assertTrue(text, text.contains(if (locale == "en") "another client" else "其他客户端", ignoreCase = true))
                assertFalse(text, text.contains(RAW_REFUSAL, ignoreCase = true))
            }
        }
    }

    @Test fun englishManualGuidanceIsCompleteAndRetryIsReachableAtLargeText() {
        val fixture = UiFixture()
        val message = UserMessage.ConnectionLimitWait
        fixture.state.value =
            fixture.state.value.copy(
                status =
                    ConnectionStatus(
                        ConnectionPhase.ERROR,
                        detail = RAW_REFUSAL,
                        category = ConnectionFailureCategory.CLONES_LIMIT,
                    ),
                statusMessage = message,
            )
        rule.setContent { fixture.Render(fontScale = 1.8f) }
        assertCompleteLayout(message.resolve(localizedContext("en")))
        rule.onNodeWithText(RAW_REFUSAL, substring = true).assertDoesNotExist()
        rule.onNodeWithText("Reconnect").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, fixture.connects) }
    }

    @Test fun chineseManualGuidanceIsNotTruncatedAndRetryIsReachable() {
        val fixture = UiFixture()
        fixture.state.value =
            fixture.state.value.copy(
                status = ConnectionStatus(ConnectionPhase.ERROR, category = ConnectionFailureCategory.CLONES_LIMIT),
                statusMessage = UserMessage.ConnectionLimitWait,
            )
        rule.setContent { fixture.Render(locale = "zh-CN", fontScale = 1.8f) }
        assertCompleteLayout(UserMessage.ConnectionLimitWait.resolve(localizedContext("zh-CN")))
        savePreview("connection-limit-manual-zh-large-top.png")
        rule.onNodeWithText("重新连接").performScrollTo()
        savePreview("connection-limit-manual-zh-large-bottom.png")
        rule.onNodeWithText("重新连接").performClick()
        rule.runOnIdle { assertEquals(1, fixture.connects) }
    }

    @Test fun categorizedRefusalWithoutAServiceMessageStillShowsGuidanceInsteadOfRawText() {
        val fixture = UiFixture()
        fixture.state.value =
            fixture.state.value.copy(
                status = ConnectionStatus(ConnectionPhase.ERROR, detail = RAW_REFUSAL, category = ConnectionFailureCategory.CLONES_LIMIT),
            )
        rule.setContent { fixture.Render() }
        rule.onNodeWithText(RAW_REFUSAL, substring = true).assertDoesNotExist()
        rule.onNodeWithText("Disconnect", substring = true).performScrollTo()
        rule.onNodeWithText("Reconnect").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, fixture.connects) }
    }

    private fun localizedContext(locale: String) =
        rule.activity.createConfigurationContext(
            Configuration(rule.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) },
        )

    private fun assertCompleteLayout(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        assertFalse("Recovery instructions were clipped or ellipsized", layouts.single().hasVisualOverflow)
    }

    private fun savePreview(name: String) {
        val directory = File(rule.activity.getExternalFilesDir(null), "ui-previews").apply { mkdirs() }
        File(directory, name).outputStream().use {
            check(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private companion object {
        const val RAW_REFUSAL = "too many clones already connected"
    }
}

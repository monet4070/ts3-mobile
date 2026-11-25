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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.protocol.ChannelFileEntry
import io.github.ts3mobile.protocol.ChannelFileFailure
import io.github.ts3mobile.protocol.ChannelFileResult
import io.github.ts3mobile.protocol.ChannelFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class ChannelFilesDialogTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun navigatesFoldersAndCancellingSystemSaveDiscardsTheStagedFile() {
        val fixture = FakeFiles()
        rule.setContent { ChannelFilesDialog(fixture) { } }
        rule.onNodeWithText("Samples").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Folder")).performClick()
        rule.onNodeWithText("Parent folder").assertIsDisplayed()
        rule.onNodeWithText("probe.txt").assertIsDisplayed()
        rule.runOnIdle { assertEquals(listOf("/", "/Samples"), fixture.paths) }
        rule.onNodeWithText("probe.txt").performClick()
        rule.onNodeWithText("Download complete. Choose where to save.").assertIsDisplayed()
        rule.onNodeWithText("Parent folder").assertIsNotEnabled()
        rule.onNodeWithText("probe.txt").assertIsNotEnabled()
        rule.onNodeWithText("File saved").assertDoesNotExist()
        rule.onNodeWithText("Save verified file…").assertIsDisplayed().performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        rule.waitUntil(8_000) { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
        automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        rule.waitUntil(5_000) { fixture.staged?.let { !Files.exists(it) } == true }
        rule.runOnIdle { assertEquals(listOf("/Samples/probe.txt"), fixture.downloads) }
        rule.onNodeWithText("File saved").assertDoesNotExist()
        rule.onNodeWithText("Download complete. Choose where to save.").assertDoesNotExist()
        rule.onNodeWithText("probe.txt").assertIsEnabled()
    }

    @Test fun serverDenialNeverLooksLikeAnEmptyDirectoryOrASuccessfulSave() {
        val fixture = FakeFiles(failure = ChannelFileFailure.REJECTED)
        val open = mutableStateOf(true)
        rule.setContent { if (open.value) ChannelFilesDialog(fixture) { open.value = false } }
        rule.onNodeWithText("The server refused this file operation.").assertIsDisplayed()
        rule.onNodeWithText("No files in this folder").assertDoesNotExist()
        rule.onNodeWithText("Save verified file…").assertDoesNotExist()
        rule.onNodeWithText("Close").performClick()
        rule.runOnIdle { assertFalse(open.value) }
    }

    @Test fun providerSaveWritesEveryByteAndRemovesPrivateStaging() {
        val bytes = ByteArray(32_769) { (it % 251).toByte() }
        val fixture = FakeFiles(payload = bytes)
        val destination = Uri.parse("content://io.github.ts3mobile.app.test.backup-documents/${UUID.randomUUID()}")
        val resolver = rule.activity.contentResolver
        try {
            openWithDestination(fixture, destination)
            rule.waitUntil(5_000) { fixture.staged?.let { !Files.exists(it) } == true }
            rule.onNodeWithText("File saved").assertIsDisplayed()
            assertArrayEquals(bytes, checkNotNull(resolver.openInputStream(destination)).use { it.readBytes() })
        } finally {
            resolver.delete(destination, null, null)
        }
    }

    @Test fun unwritableDocumentDoesNotReportSuccessOrRetainStagedBytes() {
        val fixture = FakeFiles()
        val rejectedDocument = Uri.parse("content://io.github.ts3mobile.app.test.backup-documents/unsupported-fixture")
        openWithDestination(fixture, rejectedDocument)
        rule.waitUntil(5_000) { fixture.staged?.let { !Files.exists(it) } == true }
        rule.onNodeWithText("Could not complete the file operation.").assertIsDisplayed()
        rule.onNodeWithText("File saved").assertDoesNotExist()
    }

    @Test fun replacingTheDialogBeforeItDisplaysItsStagedFileStillCleansUp() {
        val open = mutableStateOf(true)
        val fixture = FakeFiles(onStaged = { open.value = false })
        rule.setContent { if (open.value) ChannelFilesDialog(fixture) {} }
        rule.onNodeWithText("Samples").performClick()
        rule.onNodeWithText("probe.txt").performClick()
        rule.waitUntil(5_000) { fixture.staged?.let { !Files.exists(it) } == true }
        rule.onNodeWithText("Save verified file…").assertDoesNotExist()
    }

    private fun openWithDestination(
        fixture: FakeFiles,
        destination: Uri,
    ) {
        val owner =
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
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { ChannelFilesDialog(fixture) {} }
        }
        rule.onNodeWithText("Samples").performClick()
        rule.onNodeWithText("probe.txt").performClick()
        rule.onNodeWithText("Save verified file…").performClick()
    }

    private class FakeFiles(
        private val failure: ChannelFileFailure? = null,
        private val payload: ByteArray = byteArrayOf(1),
        private val onStaged: () -> Unit = {},
    ) : ChannelFiles {
        val paths = mutableListOf<String>()
        val downloads = mutableListOf<String>()

        @Volatile var staged: Path? = null

        override suspend fun list(directory: String): ChannelFileResult<List<ChannelFileEntry>> {
            paths.add(directory)
            return when {
                failure != null -> ChannelFileResult.Failed(failure)
                directory == "/" -> ChannelFileResult.Success(listOf(ChannelFileEntry("Samples", directory = true, size = 0)))
                directory == "/Samples" ->
                    ChannelFileResult.Success(
                        listOf(ChannelFileEntry("probe.txt", directory = false, size = payload.size.toLong())),
                    )
                else -> error("Unexpected fixture directory")
            }
        }

        override suspend fun stageDownload(
            remotePath: String,
            privateDirectory: Path,
        ): ChannelFileResult<Path> {
            downloads.add(remotePath)
            val path = Files.createTempFile(privateDirectory, "fixture-channel-file", ".part")
            Files.write(path, payload)
            staged = path
            onStaged()
            return ChannelFileResult.Success(path)
        }
    }
}

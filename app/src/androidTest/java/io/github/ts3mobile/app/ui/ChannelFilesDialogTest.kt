package io.github.ts3mobile.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
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
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.documents.DocumentSavesViewModel
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
        rule.setContent { TestVerifiedFileSaveHost { ChannelFilesDialog(fixture) { } } }
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
        rule.setContent { TestVerifiedFileSaveHost { if (open.value) ChannelFilesDialog(fixture) { open.value = false } } }
        rule.onNodeWithText("The server refused this file operation.").assertIsDisplayed()
        rule.onNodeWithText("No files in this folder").assertDoesNotExist()
        rule.onNodeWithText("Save verified file…").assertDoesNotExist()
        rule.onNodeWithText("Close").performClick()
        rule.runOnIdle { assertFalse(open.value) }
    }

    @Test fun providerSaveWritesEveryByteAndRemovesPrivateStaging() {
        val bytes = ByteArray(32_769) { (it % 251).toByte() }
        val fixture = FakeFiles(payload = bytes)
        val destination = newDestination()
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

    @Test fun unwritableDocumentWarnsAboutTheTargetAndKeepsStagingForRetry() {
        val fixture = FakeFiles()
        val rejectedDocument = Uri.parse("content://$backupDocumentsAuthority/unsupported-fixture")
        openWithDestination(fixture, rejectedDocument)
        rule.onNodeWithText("The destination may contain an incomplete file.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Save verified file…").assertIsEnabled()
        assertTrue(fixture.staged?.let { Files.exists(it) } == true)
        rule.onNodeWithText("File saved").assertDoesNotExist()
    }

    @Test fun providerPrefixFailureCanRetryWithoutDownloadingOrRemovingThePartialDocument() {
        val bytes = ByteArray(512 * 1024) { (it % 251).toByte() }
        val fixture = FakeFiles(payload = bytes)
        val partial = newDestination().buildUpon().appendQueryParameter("failAfterPrefix", "true").build()
        val separate = newDestination()
        val resolver = rule.activity.contentResolver
        try {
            openWithDestination(fixture, partial, separate)
            rule.onNodeWithText("The destination may contain an incomplete file.", substring = true).assertIsDisplayed()
            val prefix = checkNotNull(resolver.openInputStream(partial)).use { it.readBytes() }
            assertTrue(prefix.size in 1 until bytes.size)
            assertArrayEquals(bytes.copyOf(prefix.size), prefix)

            rule.onNodeWithText("Save verified file…").performClick()
            rule.waitUntil(5_000) { fixture.staged?.let { !Files.exists(it) } == true }
            rule.onNodeWithText("File saved").assertIsDisplayed()
            assertEquals(listOf("/Samples/probe.txt"), fixture.downloads)
            assertArrayEquals(prefix, checkNotNull(resolver.openInputStream(partial)).use { it.readBytes() })
            assertArrayEquals(bytes, checkNotNull(resolver.openInputStream(separate)).use { it.readBytes() })
        } finally {
            resolver.delete(partial, null, null)
            resolver.delete(separate, null, null)
        }
    }

    @Test fun activityRecreationRoutesTheResultAfterTheOriginalDialogLeavesComposition() {
        val bytes = byteArrayOf(1, 3, 5, 7)
        val fixture = FakeFiles(payload = bytes)
        val open = mutableStateOf(true)
        val destination = newDestination()
        val resolver = rule.activity.contentResolver
        var requestCode: Int? = null
        val owner = resultOwner { code -> requestCode = code }
        val scenario = rule.activityRule.scenario
        lateinit var retained: DocumentSavesViewModel

        fun mount(recreated: Boolean) {
            scenario.onActivity { activity ->
                val documents = ViewModelProvider(activity)[DocumentSavesViewModel::class.java]
                if (recreated) assertTrue(retained === documents) else retained = documents
                activity.setContent {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                        VerifiedFileSaveHost(documents.files, documents.downloadDirectory) {
                            if (open.value) ChannelFilesDialog(fixture) { open.value = false }
                        }
                    }
                }
            }
        }
        try {
            mount(recreated = false)
            openPicker()
            scenario.recreate()
            open.value = false
            mount(recreated = true)
            assertTrue(fixture.staged?.let { Files.exists(it) } == true)
            rule.runOnIdle {
                val code = checkNotNull(requestCode)
                assertTrue(owner.activityResultRegistry.dispatchResult(code, Activity.RESULT_OK, Intent().setData(destination)))
            }
            rule.waitUntil(5_000) { fixture.staged?.let { !Files.exists(it) } == true }
            assertArrayEquals(bytes, checkNotNull(resolver.openInputStream(destination)).use { it.readBytes() })
        } finally {
            resolver.delete(destination, null, null)
        }
    }

    @Test fun replacingTheDialogBeforeItDisplaysItsStagedFileStillCleansUp() {
        val open = mutableStateOf(true)
        val fixture = FakeFiles(onStaged = { open.value = false })
        rule.setContent { TestVerifiedFileSaveHost { if (open.value) ChannelFilesDialog(fixture) {} } }
        rule.onNodeWithText("Samples").performClick()
        rule.onNodeWithText("probe.txt").performClick()
        rule.waitUntil(5_000) { fixture.staged?.let { !Files.exists(it) } == true }
        rule.onNodeWithText("Save verified file…").assertDoesNotExist()
    }

    private fun openWithDestination(
        fixture: FakeFiles,
        vararg destinations: Uri,
    ) {
        val results = ArrayDeque(destinations.toList())
        lateinit var owner: ActivityResultRegistryOwner
        owner =
            resultOwner { request ->
                val result = Intent().setData(results.removeFirst())
                assertTrue(owner.activityResultRegistry.dispatchResult(request, Activity.RESULT_OK, result))
            }
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                TestVerifiedFileSaveHost { ChannelFilesDialog(fixture) {} }
            }
        }
        openPicker()
    }

    private fun openPicker() {
        rule.onNodeWithText("Samples").performClick()
        rule.onNodeWithText("probe.txt").performClick()
        rule.onNodeWithText("Save verified file…").performClick()
    }

    private fun resultOwner(dispatch: (Int) -> Unit): ActivityResultRegistryOwner =
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
                        dispatch(requestCode)
                    }
                }
        }

    private val backupDocumentsAuthority: String
        get() = "${InstrumentationRegistry.getInstrumentation().context.packageName}.backup-documents"

    private fun newDestination(): Uri = Uri.parse("content://$backupDocumentsAuthority/${UUID.randomUUID()}")

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

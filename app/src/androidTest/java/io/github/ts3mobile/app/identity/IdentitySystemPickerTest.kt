package io.github.ts3mobile.app.identity

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.identity.documents.AndroidBackupDocument
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** System contract with a synthetic read port: never creates or modifies the installed identity. */
class IdentitySystemPickerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun actualCreateDocumentCancellationReturnsToTheCurrentOperationWithoutWritingAnything() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val material = Ts3IdentityCodec.generate(0)
        val controller =
            IdentityExportController(scope, { material }, {
                IdentitySnapshot(Ts3IdentityCodec.inspect(material), null, false, "fixture")
            })
        try {
            rule.setContent {
                val state by controller.state.collectAsState()
                val launcher =
                    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                        controller.picked(uri?.let { AndroidBackupDocument(rule.activity.contentResolver, it) })
                    }
                LaunchedEffect(state.pickerRequest) {
                    state.pickerRequest?.let { if (controller.claimPickerRequest(it)) launcher.launch("synthetic-picker-fixture.ts3mbak") }
                }
            }
            rule.runOnIdle { controller.refresh() }
            rule.waitUntil(5_000) { controller.state.value.status == IdentityExportStatus.READY }
            rule.runOnIdle { controller.export("synthetic picker password".toCharArray(), "synthetic picker password".toCharArray()) }
            rule.waitUntil(10_000) {
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true
            }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            rule.waitUntil(5_000) { controller.state.value.status == IdentityExportStatus.CANCELLED }
            assertFalse(controller.state.value.pickerOutstanding)
            assertEquals(null, controller.state.value.pickerRequest)
        } finally {
            instrumentation.runOnMainSync { controller.close() }
            scope.cancel()
        }
    }
}

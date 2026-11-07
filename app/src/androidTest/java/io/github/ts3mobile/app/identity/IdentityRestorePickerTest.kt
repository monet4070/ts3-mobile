package io.github.ts3mobile.app.identity

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.crypto.KeystorePayloadCipher
import io.github.ts3mobile.app.crypto.PayloadKeyAccess
import io.github.ts3mobile.app.identity.documents.AndroidBackupDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Real system picker, but cancellation must never touch storage or any Keystore. */
class IdentityRestorePickerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun actualOpenDocumentCancellationConsumesItsSlotWithoutReadingOrChangingIdentity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val neverRead =
            object : DataStore<Preferences> {
                override val data = MutableStateFlow(emptyPreferences())

                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    error(
                        "Cancellation must not access storage",
                    )
            }
        val keys =
            object : PayloadKeyAccess {
                override fun findExistingKey() = error("Cancellation must not read a key")

                override fun getOrCreateKeyForNewData() = error("Cancellation must not create a key")
            }
        val repository = IdentityRepository(IdentityStore(neverRead, KeystorePayloadCipher(keys)))
        val controller = IdentityRestoreController(scope, repository, { error("No change is authorized") })
        try {
            rule.setContent {
                val state by controller.state.collectAsState()
                val launcher =
                    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        controller.picked(uri?.let { AndroidBackupDocument(rule.activity.contentResolver, it) })
                    }
                LaunchedEffect(state.pickerRequest) {
                    state.pickerRequest?.let { if (controller.claimPickerRequest(it)) launcher.launch(arrayOf("*/*")) }
                }
            }
            rule.runOnIdle { controller.beginImport("synthetic picker password".toCharArray()) }
            rule.waitUntil(10_000) {
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true
            }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            rule.waitUntil(5_000) { controller.state.value.status == IdentityRestoreStatus.CANCELLED }
            assertFalse(controller.state.value.pickerOutstanding)
            assertEquals(IdentityUseState.IDLE, repository.gate.state.value)
        } finally {
            instrumentation.runOnMainSync { controller.close() }
            scope.cancel()
        }
    }
}

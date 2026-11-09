package io.github.ts3mobile.app.ui

import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.ui.theme.Ts3MobileTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class InputDialogImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun realKeyboardKeepsBothActionsAboveItsFinalVisibleBoundary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        fun shell(command: String): String =
            instrumentation.uiAutomation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
            }
        val original = shell("settings get secure show_ime_with_hard_keyboard")
        val view = AtomicReference<View>()
        val cancelBottom = AtomicInteger()
        val saveBottom = AtomicInteger()
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            rule.runOnUiThread {
                rule.activity.enableEdgeToEdge()
                rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            rule.setContent {
                Ts3MobileTheme(true) {
                    InputDialog({}, { Text("Synthetic editor") }, {
                        val currentView = LocalView.current
                        SideEffect { view.set(currentView) }
                        val fields = remember { mutableStateListOf(*Array(8) { "" }) }
                        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                            repeat(8) { index ->
                                OutlinedTextField(
                                    fields[index],
                                    { fields[index] = it },
                                    label = { Text("Field $index") },
                                    singleLine = true,
                                )
                            }
                        }
                    }, {
                        val currentView = LocalView.current
                        TextButton(
                            {},
                            Modifier.onGloballyPositioned {
                                val origin = IntArray(2)
                                currentView.getLocationOnScreen(origin)
                                saveBottom.set((it.positionInRoot().y + it.size.height + origin[1]).toInt())
                            },
                        ) { Text("Save") }
                    }, {
                        val currentView = LocalView.current
                        TextButton(
                            {},
                            Modifier.onGloballyPositioned {
                                val origin = IntArray(2)
                                currentView.getLocationOnScreen(origin)
                                cancelBottom.set((it.positionInRoot().y + it.size.height + origin[1]).toInt())
                            },
                        ) { Text("Cancel") }
                    })
                }
            }
            rule.onNodeWithText("Field 0").performClick().performTextInput("fixture")
            var ime = 0
            var lastChange = android.os.SystemClock.uptimeMillis()
            rule.waitUntil(5000) {
                instrumentation.runOnMainSync {
                    val insets = ViewCompat.getRootWindowInsets(view.get())
                    val current = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    if (current != ime) {
                        ime = current
                        lastChange = android.os.SystemClock.uptimeMillis()
                    }
                }
                ime > 0 && android.os.SystemClock.uptimeMillis() - lastChange >= 300
            }
            val visible = Rect()
            val origin = IntArray(2)
            var rootBottom = 0
            instrumentation.runOnMainSync {
                val root = view.get().rootView
                root.getLocationOnScreen(origin)
                rootBottom = origin[1] + root.height
                view.get().getWindowVisibleDisplayFrame(visible)
            }
            val keyboardTop = visible.bottom
            try {
                rule.waitUntil(5000) {
                    saveBottom.get() in 1..keyboardTop && cancelBottom.get() in 1..keyboardTop
                }
            } finally {
                println("IME_FRAME root=$rootBottom frame=${visible.bottom} ime=$ime save=${saveBottom.get()} cancel=${cancelBottom.get()}")
            }
            assertTrue(saveBottom.get() <= keyboardTop)
            assertTrue(cancelBottom.get() <= keyboardTop)
            rule.onNodeWithText("Field 0").assertIsDisplayed()
        } finally {
            if (original == "null") {
                shell("settings delete secure show_ime_with_hard_keyboard")
            } else {
                shell("settings put secure show_ime_with_hard_keyboard ${original.toInt()}")
            }
        }
    }
}

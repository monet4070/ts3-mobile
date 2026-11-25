package io.github.ts3mobile.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertTrue
import java.util.Locale
import java.util.UUID

internal class FixtureSystemDocumentPicker(private val automation: UiAutomation) : AutoCloseable {
    private val originalFlags = automation.serviceInfo.flags
    private val filename = "ts3-mobile-attachment-${UUID.randomUUID()}.txt"
    private val output = "/sdcard/Download/$filename"

    init {
        require(Build.HARDWARE in setOf("ranchu", "goldfish")) { "System picker probe requires a disposable emulator" }
        require(Locale.getDefault().language == "en") { "System picker probe uses the English emulator labels" }
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
    }

    fun save(expectedName: String) {
        val filenameInput = awaitNode("file name") { it.isEditable && it.text?.toString() == expectedName }
        val arguments =
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, filename)
            }
        assertTrue(filenameInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        awaitNode("renamed file") { it.isEditable && it.text?.toString() == filename }
        val saveButton =
            awaitNode("Save button") { it.isEnabled && it.isClickable && it.text?.toString().equals("Save", ignoreCase = true) }
        assertTrue(saveButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    fun readSavedBytes(): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cat $output")).use { it.readBytes() }

    override fun close() {
        automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        automation.executeShellCommand("rm $output").close()
    }

    private fun awaitNode(
        label: String,
        condition: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root?.packageName?.toString() in PICKER_PACKAGES && root != null) {
                val pending = ArrayDeque(listOf(root))
                var inspected = 0
                while (pending.isNotEmpty() && inspected++ < 256) {
                    val current = pending.removeFirst()
                    if (condition(current)) return current
                    for (index in 0 until current.childCount) current.getChild(index)?.let(pending::addLast)
                }
            }
            SystemClock.sleep(50)
        }
        error("Timed out waiting for $label in Android DocumentsUI")
    }

    private companion object {
        val PICKER_PACKAGES = setOf("com.android.documentsui", "com.google.android.documentsui")
    }
}

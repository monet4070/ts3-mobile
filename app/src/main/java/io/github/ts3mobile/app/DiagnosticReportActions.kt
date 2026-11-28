package io.github.ts3mobile.app

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.github.ts3mobile.app.documents.DiagnosticExportOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Export formatting and provider writes run away from the UI/audio threads. */
internal class DiagnosticReportActions(
    private val activity: ComponentActivity,
    private val export: () -> DiagnosticExportOperations,
    private val report: suspend () -> String?,
) {
    private var pickerId: String? = null
    private val saveDocument =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json"), ::completeExport)

    fun restorePicker() {
        val registry = activity.savedStateRegistry
        val offered = registry.consumeRestoredStateForKey(STATE_KEY)?.getString(PICKER_ID) ?: export().pickerId
        pickerId = offered?.takeIf { export().restorePicking(it) }
        registry.registerSavedStateProvider(STATE_KEY) { Bundle().apply { putString(PICKER_ID, pickerId) } }
    }

    fun save() {
        val operations = export()
        val id = operations.reserve()
        if (id == null) {
            toast(R.string.diagnostics_export_busy)
            return
        }
        activity.lifecycleScope.launch {
            var launched = false
            try {
                val text = withContext(Dispatchers.Default) { report() } ?: throw IOException("Diagnostics unavailable")
                operations.prepare(id, text)
                operations.picking(id)
                pickerId = id
                saveDocument.launch("TS3-Mobile-diagnostics-${System.currentTimeMillis()}.json")
                launched = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toast(R.string.diagnostics_export_failed)
            } finally {
                if (!launched) {
                    if (pickerId == id) pickerId = null
                    operations.abort(id)
                }
            }
        }
    }

    fun copy() {
        activity.lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.Default) { report() } ?: throw IOException("Diagnostics unavailable")
                if (text.length > 480_000) {
                    toast(R.string.diagnostics_copy_too_large)
                    return@launch
                }
                activity.getSystemService(
                    ClipboardManager::class.java,
                ).setPrimaryClip(ClipData.newPlainText("TS3 Mobile diagnostics", text))
                toast(R.string.diagnostics_copied)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toast(R.string.diagnostics_export_failed)
            }
        }
    }

    fun mark(action: suspend () -> Boolean) {
        activity.lifecycleScope.launch {
            try {
                val marked = withContext(Dispatchers.Default) { action() }
                toast(if (marked) R.string.diagnostics_marked else R.string.diagnostics_no_audio_session)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toast(R.string.diagnostics_export_failed)
            }
        }
    }

    private fun completeExport(uri: Uri?) {
        val id = pickerId
        pickerId = null
        val context = activity.applicationContext
        val write: ((ByteArray) -> Unit)? =
            uri?.let { destination ->
                { bytes ->
                    context.contentResolver.openOutputStream(destination, "wt")?.use { it.write(bytes) }
                        ?: throw IOException("Document output unavailable")
                }
            }
        val accepted =
            id != null &&
                export().resolve(id, write) { success ->
                    val message =
                        when (success) {
                            true -> R.string.diagnostics_saved
                            false -> R.string.diagnostics_export_failed
                            null -> null
                        }
                    if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
        if (!accepted && uri != null) toast(R.string.diagnostics_export_failed)
    }

    private fun toast(message: Int) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val STATE_KEY = "diagnostic-document-picker"
        const val PICKER_ID = "operation"
    }
}

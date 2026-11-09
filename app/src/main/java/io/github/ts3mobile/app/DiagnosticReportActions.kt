package io.github.ts3mobile.app

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Export formatting and provider writes run away from the UI/audio threads. */
internal class DiagnosticReportActions(
    private val activity: ComponentActivity,
    private val report: () -> String?,
) {
    private val pending by lazy { PendingDiagnosticExport(File(activity.noBackupFilesDir, "pending-diagnostic-export.json")) }
    private var preparing = false
    private val saveDocument =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            perform(if (uri != null) R.string.diagnostics_saved else null) {
                if (uri != null) {
                    val bytes = pending.read()
                    activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: throw IOException("Document output unavailable")
                }
                pending.clear()
            }
        }

    fun save() {
        if (preparing) return
        preparing = true
        activity.lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.Default) { report() } ?: throw IOException("Diagnostics unavailable")
                withContext(Dispatchers.IO) { pending.prepare(text) }
                saveDocument.launch("TS3-Mobile-diagnostics-${System.currentTimeMillis()}.json")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toast(R.string.diagnostics_export_failed)
            } finally {
                preparing = false
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

    fun mark(action: () -> Boolean) {
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

    private fun perform(
        success: Int?,
        write: () -> Unit,
    ) {
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { write() }
                success?.let(::toast)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toast(R.string.diagnostics_export_failed)
            }
        }
    }

    private fun toast(message: Int) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}

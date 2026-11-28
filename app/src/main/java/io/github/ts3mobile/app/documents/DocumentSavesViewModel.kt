package io.github.ts3mobile.app.documents

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File

internal class DocumentSavesViewModel(application: Application) : AndroidViewModel(application) {
    val downloadDirectory: File = application.cacheDir.resolve("verified-document-staging")
    private val diagnosticsDirectory = application.noBackupFilesDir.resolve("diagnostic-exports")
    val diagnostics = DiagnosticExportOperations(viewModelScope, diagnosticsDirectory)
    val files =
        VerifiedFileSaves<Uri>(
            viewModelScope,
            copy = { document, target ->
                runInterruptible(Dispatchers.IO) {
                    copyVerifiedDocument(document) { application.contentResolver.openOutputStream(target, "wt") }
                }
            },
            discard = ::removeVerifiedStaging,
        )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - STALE_STAGING_MS
            for (directory in listOf(downloadDirectory, diagnosticsDirectory)) {
                directory.listFiles()?.forEach { file ->
                    if (file.isFile && file.lastModified() in 1..cutoff) removeVerifiedStaging(file.toPath())
                }
            }
        }
    }

    override fun onCleared() {
        files.close()
    }

    private companion object {
        const val STALE_STAGING_MS = 24 * 60 * 60 * 1_000L
    }
}

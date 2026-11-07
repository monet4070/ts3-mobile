package io.github.ts3mobile.app.identity.documents

import java.io.InputStream
import java.io.OutputStream

/** Caller owns each newly opened stream. No filename, Uri or provider details enter UI state. */
internal interface BackupDocument {
    fun openWrite(): OutputStream

    fun openRead(): InputStream
}

internal class UnverifiedBackupDocument : Exception("Backup document could not be verified")

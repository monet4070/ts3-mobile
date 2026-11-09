package io.github.ts3mobile.app.identity.documents

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException

internal class AndroidBackupDocument(private val resolver: ContentResolver, private val uri: Uri) : BackupDocument {
    override fun openWrite() = resolver.openOutputStream(uri, "wt") ?: throw IOException("Document output unavailable")

    override fun openRead() = resolver.openInputStream(uri) ?: throw IOException("Document input unavailable")
}

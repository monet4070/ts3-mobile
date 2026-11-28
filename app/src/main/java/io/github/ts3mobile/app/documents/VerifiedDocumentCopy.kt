package io.github.ts3mobile.app.documents

import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

internal fun copyVerifiedDocument(
    document: VerifiedDocument,
    openTarget: () -> OutputStream?,
): DocumentSaveResult {
    if (!document.isCurrent()) return DocumentSaveResult.SESSION_CHANGED_WITH_TARGET
    val input =
        try {
            Files.newInputStream(document.path)
        } catch (_: IOException) {
            return DocumentSaveResult.SOURCE_UNAVAILABLE_WITH_TARGET
        } catch (_: SecurityException) {
            return DocumentSaveResult.SOURCE_UNAVAILABLE_WITH_TARGET
        }
    return try {
        input.use { source ->
            if (!document.isCurrent()) return DocumentSaveResult.SESSION_CHANGED_WITH_TARGET
            val output = openTarget() ?: return DocumentSaveResult.TARGET_MAY_BE_INCOMPLETE
            output.use { target ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (!document.isCurrent()) return DocumentSaveResult.SESSION_CHANGED_WITH_TARGET
                    val count = source.read(buffer)
                    if (count < 0) break
                    target.write(buffer, 0, count)
                }
            }
        }
        if (document.isCurrent()) DocumentSaveResult.SAVED else DocumentSaveResult.SESSION_CHANGED_WITH_TARGET
    } catch (_: IOException) {
        DocumentSaveResult.TARGET_MAY_BE_INCOMPLETE
    } catch (_: SecurityException) {
        DocumentSaveResult.TARGET_MAY_BE_INCOMPLETE
    } catch (_: IllegalArgumentException) {
        DocumentSaveResult.TARGET_MAY_BE_INCOMPLETE
    }
}

internal fun removeVerifiedStaging(path: Path) {
    try {
        Files.deleteIfExists(path)
    } catch (_: IOException) {
        System.err.println("TS3_FILE: discarding local staging failed")
    }
}

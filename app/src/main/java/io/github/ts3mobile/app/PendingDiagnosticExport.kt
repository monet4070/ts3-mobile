package io.github.ts3mobile.app

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One bounded, already-redacted report survives the document picker's Activity/process recreation. */
internal class PendingDiagnosticExport(private val file: File) {
    fun prepare(report: String) {
        val bytes = report.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw IOException("Diagnostic report exceeds export bound")
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use {
            it.write(bytes)
            it.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun read(): ByteArray {
        if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) throw IOException("Prepared diagnostic report unavailable")
        return file.readBytes().also { if (it.isEmpty() || it.size > MAX_BYTES) throw IOException("Invalid prepared report") }
    }

    fun clear() {
        if (file.exists() && !file.delete()) throw IOException("Could not remove prepared diagnostic report")
    }

    companion object {
        const val MAX_BYTES = 512_000
    }
}

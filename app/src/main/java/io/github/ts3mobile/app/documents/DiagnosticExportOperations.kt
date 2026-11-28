package io.github.ts3mobile.app.documents

import io.github.ts3mobile.app.PendingDiagnosticExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

internal class DiagnosticExportOperations(
    private val scope: CoroutineScope,
    private val directory: File,
) {
    private enum class Phase { PREPARING, PICKING, WRITING }

    private data class Operation(val id: String, val phase: Phase)

    private var active: Operation? = null

    val pickerId: String? get() = active?.takeIf { it.phase == Phase.PICKING }?.id

    fun reserve(): String? {
        if (active != null) return null
        return UUID.randomUUID().toString().also { active = Operation(it, Phase.PREPARING) }
    }

    suspend fun prepare(
        id: String,
        text: String,
    ) {
        check(active == Operation(id, Phase.PREPARING))
        withContext(Dispatchers.IO) { document(id).prepare(text) }
    }

    fun picking(id: String) {
        check(active == Operation(id, Phase.PREPARING))
        active = Operation(id, Phase.PICKING)
    }

    fun restorePicking(id: String): Boolean {
        if (active == Operation(id, Phase.PICKING)) return true
        if (active != null || runCatching { UUID.fromString(id).toString() }.getOrNull() != id) return false
        active = Operation(id, Phase.PICKING)
        return true
    }

    suspend fun abort(id: String) {
        val current = active?.takeIf { it.id == id && it.phase != Phase.WRITING } ?: return
        try {
            clear(current.id)
        } finally {
            if (active === current) active = null
        }
    }

    fun resolve(
        id: String,
        write: ((ByteArray) -> Unit)?,
        finished: (Boolean?) -> Unit,
    ): Boolean {
        if (active != Operation(id, Phase.PICKING)) return false
        val writing = Operation(id, Phase.WRITING)
        active = writing
        scope.launch {
            var success: Boolean? = null
            try {
                if (write != null) {
                    withContext(Dispatchers.IO) { write(document(id).read()) }
                    success = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                success = false
            } finally {
                clear(id)
                if (active === writing) active = null
            }
            finished(success)
        }
        return true
    }

    private suspend fun clear(id: String) =
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                document(id).clear()
            } catch (_: IOException) {
                System.err.println("TS3_DIAG: removing prepared report failed")
            }
        }

    private fun document(id: String) = PendingDiagnosticExport(directory.resolve("$id.json"))
}

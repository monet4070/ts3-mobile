package io.github.ts3mobile.app.documents

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.UUID

internal data class VerifiedDocument(val name: String, val path: Path, val isCurrent: () -> Boolean)

internal enum class DocumentSaveResult {
    SAVED,
    CANCELED,
    SESSION_CHANGED,
    SOURCE_UNAVAILABLE,
    PICKER_UNAVAILABLE,
    TARGET_MAY_BE_INCOMPLETE,
    SESSION_CHANGED_WITH_TARGET,
    SOURCE_UNAVAILABLE_WITH_TARGET,
}

internal data class DocumentPickerRequest(val id: String, val fileName: String)

internal class VerifiedFileSaves<Target : Any>(
    private val scope: CoroutineScope,
    private val copy: suspend (VerifiedDocument, Target) -> DocumentSaveResult,
    private val discard: (Path) -> Unit,
    private val retryLifetimeMs: Long = 600_000,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private enum class Phase { PICKING, WRITING, RETRY }

    private data class Operation<Target>(
        val owner: Any,
        val document: VerifiedDocument,
        val finish: (DocumentSaveResult) -> Unit,
        val attached: Boolean = true,
        val phase: Phase = Phase.RETRY,
        val pickerId: String? = null,
        val issue: DocumentSaveResult? = null,
        val retryUntil: Long? = null,
        val failedTargets: Set<Target> = emptySet(),
    )

    private var operation by mutableStateOf<Operation<Target>?>(null)
    private var retryExpiry: Job? = null
    var notice by mutableStateOf<DocumentSaveResult?>(null)
        private set

    val available: Boolean get() = operation == null
    val detachedRetry: Any? get() = operation?.takeIf { !it.attached && it.phase == Phase.RETRY }?.owner

    fun isSaving(owner: Any): Boolean = operation?.let { it.owner === owner && it.phase != Phase.RETRY } == true

    fun canRetry(owner: Any): Boolean = operation?.let { it.owner === owner && it.phase == Phase.RETRY } == true

    fun issue(owner: Any): DocumentSaveResult? = operation?.takeIf { it.owner === owner }?.issue

    fun start(
        owner: Any,
        document: VerifiedDocument,
        finish: (DocumentSaveResult) -> Unit,
        launch: (DocumentPickerRequest) -> Boolean,
    ): Boolean {
        if (operation != null) return false
        operation = Operation(owner, document, finish)
        retry(owner, launch)
        return true
    }

    fun retry(
        owner: Any,
        launch: (DocumentPickerRequest) -> Boolean,
    ) {
        val current = operation?.takeIf { it.owner === owner && it.phase == Phase.RETRY } ?: return
        if (!current.document.isCurrent()) {
            complete(current, DocumentSaveResult.SESSION_CHANGED)
            return
        }
        if (isExpired(current)) {
            complete(current, DocumentSaveResult.SOURCE_UNAVAILABLE)
            return
        }
        val id = UUID.randomUUID().toString()
        val picking = current.copy(phase = Phase.PICKING, pickerId = id, issue = null)
        operation = picking
        if (!launch(DocumentPickerRequest(id, current.document.name)) && operation === picking) {
            retainForRetry(picking, DocumentSaveResult.PICKER_UNAVAILABLE)
        }
    }

    fun accept(
        requestId: String,
        target: Target?,
    ): Boolean {
        val current = operation?.takeIf { it.phase == Phase.PICKING && it.pickerId == requestId } ?: return false
        when {
            target == null -> complete(current, DocumentSaveResult.CANCELED)
            !current.document.isCurrent() -> complete(current, DocumentSaveResult.SESSION_CHANGED_WITH_TARGET)
            isExpired(current) -> complete(current, DocumentSaveResult.SOURCE_UNAVAILABLE_WITH_TARGET)
            target in current.failedTargets -> retainForRetry(current, DocumentSaveResult.TARGET_MAY_BE_INCOMPLETE)
            else -> {
                operation = current.copy(phase = Phase.WRITING)
                scope.launch {
                    val result = copy(current.document, target)
                    val writing = operation?.takeIf { it.phase == Phase.WRITING && it.pickerId == requestId } ?: return@launch
                    if (result == DocumentSaveResult.TARGET_MAY_BE_INCOMPLETE && writing.document.isCurrent() &&
                        !isExpired(writing) && writing.failedTargets.size < MAX_RETRIES
                    ) {
                        retainForRetry(writing.copy(failedTargets = writing.failedTargets + target), result)
                    } else {
                        val finalResult =
                            when {
                                result != DocumentSaveResult.TARGET_MAY_BE_INCOMPLETE -> result
                                writing.document.isCurrent() -> DocumentSaveResult.SOURCE_UNAVAILABLE_WITH_TARGET
                                else -> DocumentSaveResult.SESSION_CHANGED_WITH_TARGET
                            }
                        complete(writing, finalResult)
                    }
                }
            }
        }
        return true
    }

    fun detach(owner: Any) {
        val current = operation?.takeIf { it.owner === owner } ?: return
        operation = current.copy(attached = false)
    }

    fun discardRetry(owner: Any) {
        operation?.takeIf { it.owner === owner && it.phase == Phase.RETRY }?.let { complete(it, DocumentSaveResult.CANCELED) }
    }

    fun consumeNotice() {
        notice = null
    }

    fun close() {
        retryExpiry?.cancel()
        val current = operation
        operation = null
        if (current != null) discard(current.document.path)
    }

    private fun isExpired(current: Operation<Target>): Boolean = current.retryUntil?.let { nowMs() >= it } == true

    private fun retainForRetry(
        current: Operation<Target>,
        issue: DocumentSaveResult,
    ) {
        val until = current.retryUntil ?: nowMs() + retryLifetimeMs
        operation = current.copy(phase = Phase.RETRY, pickerId = null, issue = issue, retryUntil = until)
        retryExpiry?.cancel()
        retryExpiry =
            scope.launch {
                delay((until - nowMs()).coerceAtLeast(0))
                val expired = operation?.takeIf { it.owner === current.owner && it.phase == Phase.RETRY } ?: return@launch
                complete(expired, DocumentSaveResult.SOURCE_UNAVAILABLE)
            }
    }

    private fun complete(
        current: Operation<Target>,
        result: DocumentSaveResult,
    ) {
        operation = null
        retryExpiry?.cancel()
        retryExpiry = null
        discard(current.document.path)
        if (current.attached) {
            current.finish(result)
        } else if (result != DocumentSaveResult.CANCELED) {
            notice = result
        }
    }

    private companion object {
        const val MAX_RETRIES = 8
    }
}

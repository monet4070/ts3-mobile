package io.github.ts3mobile.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.documents.DocumentPickerRequest
import io.github.ts3mobile.app.documents.DocumentSaveResult
import io.github.ts3mobile.app.documents.VerifiedDocument
import io.github.ts3mobile.app.documents.VerifiedFileSaves
import io.github.ts3mobile.app.documents.removeVerifiedStaging
import io.github.ts3mobile.protocol.ChannelFileFailure
import io.github.ts3mobile.protocol.ChannelFileResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal data class VerifiedFileTransferUi(val transfer: VerifiedFileTransfer, val chooseDestination: () -> Unit)

@Composable
internal fun rememberVerifiedFileTransfer(
    owner: Any,
    isCurrent: () -> Boolean,
): VerifiedFileTransferUi =
    key(owner) {
        val scope = rememberCoroutineScope()
        val binding = LocalVerifiedFileSaves.current
        val transfer = remember(scope, binding.files) { VerifiedFileTransfer(scope, binding.files, binding.directory.toPath(), isCurrent) }
        DisposableEffect(transfer) { onDispose { transfer.dispose() } }
        VerifiedFileTransferUi(transfer) { transfer.chooseDestination(binding.launch) }
    }

internal class VerifiedFileTransfer(
    private val scope: CoroutineScope,
    private val saves: VerifiedFileSaves<Uri>,
    private val directory: Path,
    private val isCurrent: () -> Boolean,
) {
    private val owner = Any()
    private var active = true
    private var staged by mutableStateOf<VerifiedDocument?>(null)
    private var lastSaveResult by mutableStateOf<DocumentSaveResult?>(null)
    var downloading by mutableStateOf(false)
        private set
    var failure by mutableStateOf<ChannelFileFailure?>(null)
        private set

    val saving: Boolean get() = saves.isSaving(owner)
    val saved: Boolean get() = lastSaveResult == DocumentSaveResult.SAVED
    val saveIssue: DocumentSaveResult?
        get() = saves.issue(owner) ?: lastSaveResult?.takeUnless { it == DocumentSaveResult.CANCELED || it == DocumentSaveResult.SAVED }
    val canSave: Boolean get() = staged != null && saves.available || saves.canRetry(owner)
    val locked: Boolean get() = downloading || saving || staged != null || saves.canRetry(owner)

    fun resetFeedback() {
        failure = null
        lastSaveResult = null
    }

    fun start(
        name: String,
        download: suspend (Path) -> ChannelFileResult<Path>,
    ) {
        if (!active || locked) return
        resetFeedback()
        downloading = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Files.createDirectories(directory) }
                when (val result = download(directory)) {
                    is ChannelFileResult.Success ->
                        if (active && isCurrent()) {
                            staged = VerifiedDocument(name, result.value, isCurrent)
                        } else {
                            removeVerifiedStaging(result.value)
                            if (active) failure = ChannelFileFailure.SESSION_CHANGED
                        }
                    is ChannelFileResult.Failed -> if (active) failure = result.reason
                }
            } catch (_: IOException) {
                if (active) failure = ChannelFileFailure.IO
            } catch (_: SecurityException) {
                if (active) failure = ChannelFileFailure.IO
            } finally {
                downloading = false
            }
        }
    }

    fun chooseDestination(launch: (DocumentPickerRequest) -> Boolean) {
        if (!active) return
        val file = staged
        if (file == null) {
            saves.retry(owner, launch)
        } else if (saves.start(owner, file, { lastSaveResult = it }, launch)) {
            staged = null
        }
    }

    fun dismiss() = saves.discardRetry(owner)

    fun dispose() {
        active = false
        staged?.let { removeVerifiedStaging(it.path) }
        staged = null
        saves.detach(owner)
    }
}

internal fun ChannelFileFailure.fileMessage(): Int =
    when (this) {
        ChannelFileFailure.REJECTED -> R.string.channel_files_denied
        ChannelFileFailure.TIMED_OUT -> R.string.channel_files_timeout
        ChannelFileFailure.RECONNECT_REQUIRED -> R.string.channel_files_reconnect
        ChannelFileFailure.TOO_LARGE -> R.string.channel_files_too_large
        ChannelFileFailure.SESSION_CHANGED -> R.string.channel_files_session_changed
        else -> R.string.channel_files_failed
    }

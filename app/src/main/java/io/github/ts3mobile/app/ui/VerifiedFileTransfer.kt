package io.github.ts3mobile.app.ui

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.ts3mobile.app.R
import io.github.ts3mobile.protocol.ChannelFileFailure
import io.github.ts3mobile.protocol.ChannelFileResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal data class VerifiedFileTransferUi(val transfer: VerifiedFileTransfer, val chooseDestination: () -> Unit)

@Composable
internal fun rememberVerifiedFileTransfer(owner: Any): VerifiedFileTransferUi =
    key(owner) {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val transfer = remember(scope, context) { VerifiedFileTransfer(scope, context.contentResolver, context.cacheDir.toPath()) }
        DisposableEffect(transfer) { onDispose { transfer.dispose() } }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), transfer::saveTo)
        VerifiedFileTransferUi(transfer) { transfer.chooseDestination { name -> picker.launch(name) } }
    }

internal class VerifiedFileTransfer(
    private val scope: CoroutineScope,
    private val resolver: ContentResolver,
    private val directory: Path,
) {
    private data class StagedFile(val name: String, val path: Path)

    private var active = true
    private var staged by mutableStateOf<StagedFile?>(null)
    var downloading by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var saved by mutableStateOf(false)
        private set
    var failure by mutableStateOf<ChannelFileFailure?>(null)
        private set

    val canSave: Boolean get() = staged != null && !saving
    val locked: Boolean get() = downloading || saving || staged != null

    fun resetFeedback() {
        failure = null
        saved = false
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
                when (val result = download(directory)) {
                    is ChannelFileResult.Success -> if (active) staged = StagedFile(name, result.value) else deleteStaging(result.value)
                    is ChannelFileResult.Failed -> if (active) failure = result.reason
                }
            } finally {
                downloading = false
            }
        }
    }

    fun chooseDestination(launch: (String) -> Unit) {
        val file = staged ?: return
        if (!active || saving) return
        saving = true
        try {
            launch(file.name)
        } catch (_: ActivityNotFoundException) {
            saving = false
            failure = ChannelFileFailure.IO
        }
    }

    fun saveTo(uri: Uri?) {
        val file = staged ?: return
        if (!active || !saving) return
        if (uri == null) {
            saving = false
            discardStaging()
            return
        }
        scope.launch {
            try {
                runInterruptible(Dispatchers.IO) {
                    val output = resolver.openOutputStream(uri, "w") ?: throw IOException("Document is not writable")
                    output.use { stream -> Files.newInputStream(file.path).use { it.copyTo(stream) } }
                }
                saved = true
            } catch (_: IOException) {
                failure = ChannelFileFailure.IO
            } catch (_: SecurityException) {
                failure = ChannelFileFailure.IO
            } finally {
                saving = false
                discardStaging()
            }
        }
    }

    fun dispose() {
        active = false
        discardStaging()
    }

    private fun discardStaging() {
        val file = staged ?: return
        staged = null
        deleteStaging(file.path)
    }

    private fun deleteStaging(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            System.err.println("TS3_FILE: discarding local staging failed")
        }
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

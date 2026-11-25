package io.github.ts3mobile.app.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.documents.DocumentPickerRequest
import io.github.ts3mobile.app.documents.DocumentSaveResult
import io.github.ts3mobile.app.documents.DocumentSavesViewModel
import io.github.ts3mobile.app.documents.VerifiedFileSaves
import io.github.ts3mobile.app.ui.theme.ApplicationTheme
import java.io.File

internal data class VerifiedFileSaveBinding(
    val files: VerifiedFileSaves<Uri>,
    val directory: File,
    val launch: (DocumentPickerRequest) -> Boolean,
)

internal val LocalVerifiedFileSaves = staticCompositionLocalOf<VerifiedFileSaveBinding> { error("Verified file save host is missing") }

@Composable
internal fun ApplicationDocumentTheme(
    documents: DocumentSavesViewModel,
    content: @Composable () -> Unit,
) {
    ApplicationTheme { VerifiedFileSaveHost(documents.files, documents.downloadDirectory, content) }
}

@Composable
internal fun VerifiedFileSaveHost(
    files: VerifiedFileSaves<Uri>,
    directory: File,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var pickerId by rememberSaveable { mutableStateOf<String?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val request = pickerId
            pickerId = null
            val accepted = request != null && files.accept(request, uri)
            if (!accepted && uri != null) {
                Toast.makeText(context, R.string.channel_files_save_expired_target, Toast.LENGTH_LONG).show()
            }
        }
    val launch: (DocumentPickerRequest) -> Boolean = { request ->
        if (pickerId != null) {
            false
        } else {
            pickerId = request.id
            try {
                picker.launch(request.fileName)
                true
            } catch (_: ActivityNotFoundException) {
                pickerId = null
                false
            } catch (_: SecurityException) {
                pickerId = null
                false
            }
        }
    }
    CompositionLocalProvider(LocalVerifiedFileSaves provides VerifiedFileSaveBinding(files, directory, launch)) {
        content()
        files.detachedRetry?.let { owner ->
            InputDialog(
                onDismissRequest = { files.discardRetry(owner) },
                title = { FeatureTitle(UiIcon.FileText, stringResource(R.string.channel_files_save)) },
                text = {
                    files.issue(owner)?.let { FeatureNotice(UiIcon.Info, stringResource(it.documentMessage()), error = true) }
                },
                confirmButton = {
                    FeatureAction(UiIcon.Refresh, stringResource(R.string.channel_files_save), { files.retry(owner, launch) })
                },
                dismissButton = {
                    FeatureAction(UiIcon.Close, stringResource(R.string.action_close), { files.discardRetry(owner) })
                },
            )
        }
    }
    val notice = files.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            Toast.makeText(context, notice.documentMessage(), Toast.LENGTH_LONG).show()
            files.consumeNotice()
        }
    }
}

internal fun DocumentSaveResult.documentMessage(): Int =
    when (this) {
        DocumentSaveResult.SAVED -> R.string.channel_files_saved
        DocumentSaveResult.CANCELED -> R.string.action_close
        DocumentSaveResult.SESSION_CHANGED -> R.string.channel_files_session_changed
        DocumentSaveResult.SOURCE_UNAVAILABLE -> R.string.channel_files_source_unavailable
        DocumentSaveResult.PICKER_UNAVAILABLE -> R.string.channel_files_picker_unavailable
        DocumentSaveResult.TARGET_MAY_BE_INCOMPLETE -> R.string.channel_files_partial_target
        DocumentSaveResult.SESSION_CHANGED_WITH_TARGET -> R.string.channel_files_save_expired_target
        DocumentSaveResult.SOURCE_UNAVAILABLE_WITH_TARGET -> R.string.channel_files_source_unavailable_target
    }

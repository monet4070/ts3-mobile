package io.github.ts3mobile.app.identity

import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.ts3mobile.app.Ts3MobileApplication
import io.github.ts3mobile.app.identity.documents.AndroidBackupDocument
import io.github.ts3mobile.app.ui.theme.ApplicationTheme

/** Private in-memory operation survives rotation, never process death or SavedStateHandle. */
internal class IdentityManagementViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as Ts3MobileApplication).identities
    val useState = repository.gate.state
    val exports = IdentityExportController(viewModelScope, repository::readForBackup, repository::snapshot)

    val restores = IdentityRestoreController(viewModelScope, repository, exports::refresh)

    fun export(
        password: CharArray,
        confirmation: CharArray,
    ) {
        if (restores.state.value.active) {
            password.fill('\u0000')
            confirmation.fill('\u0000')
        } else {
            exports.export(password, confirmation)
        }
    }

    fun import(password: CharArray) {
        if (exports.state.value.working || exports.state.value.pickerOutstanding) {
            password.fill('\u0000')
        } else {
            restores.beginImport(password)
        }
    }

    init {
        exports.refresh()
    }

    override fun onCleared() {
        restores.close()
        exports.close()
    }
}

class IdentityManagementActivity : ComponentActivity() {
    private val model by viewModels<IdentityManagementViewModel>()
    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            model.exports.picked(uri?.let { AndroidBackupDocument(contentResolver, it) })
        }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            model.restores.picked(uri?.let { AndroidBackupDocument(contentResolver, it) })
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            ApplicationTheme {
                val state by model.exports.state.collectAsStateWithLifecycle()
                val useState by model.useState.collectAsStateWithLifecycle()
                val restore by model.restores.state.collectAsStateWithLifecycle()
                LaunchedEffect(restore.pickerRequest) {
                    restore.pickerRequest?.let { token ->
                        if (model.restores.claimPickerRequest(token)) {
                            try {
                                openDocument.launch(arrayOf("*/*"))
                            } catch (_: Exception) {
                                model.restores.pickerUnavailable()
                            }
                        }
                    }
                }
                LaunchedEffect(state.pickerRequest) {
                    state.pickerRequest?.let { token ->
                        if (model.exports.claimPickerRequest(token)) {
                            try {
                                createDocument.launch("ts3-mobile-identity.ts3mbak")
                            } catch (_: Exception) {
                                model.exports.pickerUnavailable()
                            }
                        }
                    }
                }
                IdentityManagementScreen(
                    state,
                    useState,
                    model::export,
                    model.exports::refresh,
                    model.exports::cancel,
                    ::finish,
                    restoreActive = restore.active,
                    restoreContent = {
                        IdentityRestoreSection(
                            restore, useState, state.snapshot?.rollback != null,
                            state.working || state.pickerOutstanding,
                            model::import, model.restores::beginRollback, model.restores::confirm,
                            model.restores::refreshConfirmation, model.restores::cancel,
                            { io.github.ts3mobile.app.service.TeamSpeakService.disconnect(this) },
                        )
                    },
                )
            }
        }
    }
}

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
import io.github.ts3mobile.app.ui.theme.Ts3MobileTheme

/** Private in-memory operation survives rotation, never process death or SavedStateHandle. */
internal class IdentityManagementViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as Ts3MobileApplication).identities
    val useState = repository.gate.state
    val exports = IdentityExportController(viewModelScope, repository::readForBackup, repository::snapshot)

    init {
        exports.refresh()
    }

    override fun onCleared() {
        exports.close()
    }
}

class IdentityManagementActivity : ComponentActivity() {
    private val model by viewModels<IdentityManagementViewModel>()
    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            model.exports.picked(uri?.let { AndroidBackupDocument(contentResolver, it) })
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            Ts3MobileTheme {
                val state by model.exports.state.collectAsStateWithLifecycle()
                val useState by model.useState.collectAsStateWithLifecycle()
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
                IdentityManagementScreen(state, useState, model.exports::export, model.exports::refresh, model.exports::cancel, ::finish)
            }
        }
    }
}

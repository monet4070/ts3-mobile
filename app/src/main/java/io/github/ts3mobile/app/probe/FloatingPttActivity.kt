package io.github.ts3mobile.app.probe

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.TeamSpeakService
import io.github.ts3mobile.app.service.floatingPttTarget
import io.github.ts3mobile.app.ui.GuideDestinationTip
import io.github.ts3mobile.app.ui.GuideRoute
import io.github.ts3mobile.app.ui.guideRoute
import io.github.ts3mobile.app.ui.theme.ApplicationTheme

internal class FloatingPttActivity : ComponentActivity() {
    private var session by mutableStateOf<TeamSpeakService.SessionBinder?>(null)
    private var overlayAllowed by mutableStateOf(false)
    private var autoShow by mutableStateOf(false)
    private var recallPending = false
    private var bound = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                session = service as? TeamSpeakService.SessionBinder
                recallIfReady()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                session = null
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recallPending = savedInstanceState == null && intent.action == ACTION_RECALL
        enableEdgeToEdge()
        setContent {
            ApplicationTheme {
                val state = session?.state?.collectAsStateWithLifecycle()?.value
                GuideDestinationTip(intent.guideRoute(GuideRoute.FLOATING_SETUP, GuideRoute.FLOATING_USE)) {
                    FloatingPttSettingsScreen(
                        overlayAllowed = overlayAllowed,
                        channelReady = state?.floatingPttTarget() != null,
                        onBack = ::finish,
                        onPermissionSettings = ::openOverlaySettings,
                        onShow = ::requestShow,
                        autoShow = autoShow,
                        onAutoShowChanged = ::changeAutoShow,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, TeamSpeakService::class.java), connection, 0)
    }

    override fun onPostResume() {
        super.onPostResume()
        overlayAllowed = Settings.canDrawOverlays(this)
        autoShow = autoShowFloatingPtt
        recallIfReady()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recallPending = intent.action == ACTION_RECALL
        recallIfReady()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) recallIfReady()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (autoShow && missingPermissions().isEmpty()) armOverlay()
    }

    override fun onStop() {
        recallPending = false
        if (bound) unbindService(connection)
        bound = false
        session = null
        super.onStop()
    }

    private fun changeAutoShow(enabled: Boolean) {
        autoShowFloatingPtt = enabled
        autoShow = enabled
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.ptt_permission_settings_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestShow() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            permissions.launch(missing.toTypedArray())
        } else {
            showOverlay()
        }
    }

    private fun recallIfReady() {
        if (!recallPending || session == null || !hasWindowFocus() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        recallPending = false
        showOverlay()
    }

    private fun showOverlay() {
        val armed = missingPermissions().isEmpty() && armOverlay()
        if (armed && moveTaskToBack(true)) return
        if (armed) session?.connectedPttPreparation?.disarm()
        Toast.makeText(this, R.string.ptt_show_unavailable, Toast.LENGTH_SHORT).show()
    }

    private fun armOverlay(): Boolean {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        val binder = session ?: return false
        val preparation = binder.connectedPttPreparation ?: return false
        return ConnectedPttProbeHost(this, preparation, binder.state).arm()
    }

    private fun missingPermissions(): List<String> =
        buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

    companion object {
        const val ACTION_RECALL = "io.github.ts3mobile.app.action.RECALL_FLOATING_PTT"
    }
}

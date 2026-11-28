package io.github.ts3mobile.app.probe

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.TeamSpeakService

internal class FloatingPttProbeActivity : ComponentActivity() {
    private var lab: FloatingPttProbeService.LabBinder? = null
    private var bound = false
    private var connectedBound = false
    private var connected: TeamSpeakService.SessionBinder? = null
    private lateinit var metrics: TextView
    private lateinit var showOnDesktop: Button
    private val main = Handler(Looper.getMainLooper())
    private val refresh =
        object : Runnable {
            override fun run() {
                val phase = lab?.phase ?: ProbePhase.IDLE
                val connectedReady = connected?.connectedPttPreparation?.isValid == true
                val reading =
                    lab?.counters?.let {
                        getString(
                            R.string.ptt_probe_stats,
                            it.presses,
                            it.starts,
                            it.usable,
                            it.failures,
                            it.startTiming(),
                            it.releaseTiming(),
                        )
                    }
                showOnDesktop.isEnabled = connectedReady || phase == ProbePhase.READY
                metrics.text =
                    if (connectedReady) {
                        getString(R.string.ptt_connected_ready)
                    } else {
                        listOfNotNull(getString(phase.label()), reading).joinToString("\n")
                    }
                main.postDelayed(this, 250L)
            }
        }
    private val bindings =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                lab = service as? FloatingPttProbeService.LabBinder
            }

            override fun onServiceDisconnected(name: ComponentName) {
                lab = null
            }
        }
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val connectedBindings =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                connected = service as? TeamSpeakService.SessionBinder
            }

            override fun onServiceDisconnected(name: ComponentName) {
                connected = null
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { setText(R.string.ptt_probe_intro) })
                addView(action(R.string.ptt_probe_overlay, ::overlaySettings))
                addView(action(R.string.ptt_probe_prepare) { prepare(connectedMode = false) })
                addView(TextView(context).apply { setText(R.string.ptt_connected_intro) })
                addView(action(R.string.ptt_connected_prepare) { prepare(connectedMode = true) })
                addView(
                    action(R.string.ptt_probe_stop) {
                        lab?.stop()
                        connected?.connectedPttPreparation?.disarm()
                    },
                )
                metrics = TextView(context).also(::addView)
                showOnDesktop = action(R.string.ptt_show_on_desktop) { moveTaskToBack(true) }.also(::addView)
                showOnDesktop.isEnabled = false
            }
        val padding = (24 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(column) { view, windowInsets ->
            val system = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(padding + system.left, padding + system.top, padding + system.right, padding + system.bottom)
            windowInsets
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, FloatingPttProbeService::class.java), bindings, Context.BIND_AUTO_CREATE)
        connectedBound = bindService(Intent(this, TeamSpeakService::class.java), connectedBindings, 0)
        main.post(refresh)
    }

    override fun onStop() {
        main.removeCallbacks(refresh)
        if (bound) unbindService(bindings)
        if (connectedBound) unbindService(connectedBindings)
        bound = false
        connectedBound = false
        lab = null
        connected = null
        super.onStop()
    }

    private fun action(
        label: Int,
        click: () -> Unit,
    ): Button =
        Button(this).apply {
            setText(label)
            setOnClickListener { click() }
        }

    private fun overlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", packageName, null)))
        } catch (_: RuntimeException) {
            denied()
        }
    }

    private fun prepare(connectedMode: Boolean) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (!Settings.canDrawOverlays(this)) {
            denied(connectedMode)
            return
        }
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            requestPermissions.launch(missing.toTypedArray())
            return
        }
        if (connectedMode) {
            if (!prepareConnected()) denied(connectedMode = true)
            return
        }
        try {
            startService(Intent(this, FloatingPttProbeService::class.java).setAction(FloatingPttProbeService.ARM))
        } catch (_: RuntimeException) {
            denied()
        }
    }

    private fun prepareConnected(): Boolean {
        val binder = connected ?: return false
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

    private fun denied(connectedMode: Boolean = false) {
        Toast.makeText(this, if (connectedMode) R.string.ptt_connected_denied else R.string.ptt_probe_denied, Toast.LENGTH_LONG).show()
    }
}

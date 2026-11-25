package io.github.ts3mobile.app.probe

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.Ts3MobileApplication
import io.github.ts3mobile.app.identity.IdentityUseState
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class FloatingPttProbeService : Service() {
    private class Press(val token: Long, val down: Long) {
        val held = AtomicBoolean(true)
        var up: Long? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val recorder = ProbeRecorder()
    private val session = ProbePressSession(SystemClock::elapsedRealtime)
    private val journal = ProbeJournal()
    private val overlay by lazy {
        ProbeOverlay(this, ::press, ::release, stop = { disarm("user_disarmed") }, details = {
            ProbeOverlayDetails(
                getString(R.string.ptt_overlay_local_title),
                getString(R.string.ptt_overlay_local_notice),
                getString(R.string.ptt_overlay_attempts, journal.presses, journal.starts),
            )
        }, windowLost = { disarm("window_lost") }, holdLabel = R.string.ptt_probe_hold)
    }
    private val visibleActivities = mutableSetOf<Activity>()
    private var background = false
    private var destroyed = false
    private var foreground = false
    private var active: Press? = null
    private val tick =
        object : Runnable {
            override fun run() {
                if (!session.ready) return
                if (!canProbe()) {
                    disarm("eligibility_lost")
                } else if (session.expired()) {
                    disarm("expired")
                } else {
                    main.postDelayed(this, CHECK_MILLIS)
                }
            }
        }
    private val screenOff =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = disarm("screen_off")
        }
    private val activityVisibility =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                visibleActivities += activity
                background = false
                overlay.hide()
            }

            override fun onActivityStopped(activity: Activity) {
                visibleActivities -= activity
                if (visibleActivities.isEmpty() && !activity.isChangingConfigurations) {
                    background = true
                    if (session.ready && !overlay.show()) disarm("overlay_unavailable")
                }
            }

            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) = Unit

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle,
            ) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                visibleActivities -= activity
            }
        }

    inner class LabBinder : Binder() {
        val phase get() = session.phase
        val counters get() = journal

        fun stop() = disarm("user_disarmed")
    }

    override fun onCreate() {
        super.onCreate()
        application.registerActivityLifecycleCallbacks(activityVisibility)
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        val channel = NotificationChannel(CHANNEL, getString(R.string.ptt_probe_title), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent): Binder = LabBinder()

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action != ARM || background || !canProbe()) {
            disarm("prepare_denied")
            return START_NOT_STICKY
        }
        if (session.arm()) {
            try {
                ServiceCompat.startForeground(this, NOTICE, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                foreground = true
                val type = if (Build.VERSION.SDK_INT >= 29) foregroundServiceType else 0
                ProbeJournal.event("ready foreground_type=$type")
                main.postDelayed(tick, CHECK_MILLIS)
            } catch (_: RuntimeException) {
                disarm("foreground_denied")
            }
        }
        return START_NOT_STICKY
    }

    private fun canProbe(): Boolean =
        !destroyed && Settings.canDrawOverlays(this) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            (application as Ts3MobileApplication).identities.gate.state.value == IdentityUseState.IDLE

    private fun press(): Long? {
        if (!background || !canProbe()) return null
        val token = session.press() ?: return null
        val trigger = Press(token, SystemClock.elapsedRealtime())
        active = trigger
        journal.begin()
        render()
        worker.execute {
            val outcome =
                recorder.capture(trigger.held) { started ->
                    main.post {
                        if (session.started(token)) {
                            ProbeJournal.event("recording start_ms=${started - trigger.down}")
                            render()
                        }
                    }
                }
            main.post {
                journal.complete(outcome, trigger.down, trigger.up)
                active = null
                session.finished(token)
                if (outcome.failed && session.ready) disarm("capture_failed")
                if (!destroyed) render()
            }
        }
        return token
    }

    private fun release(token: Long) {
        val trigger = active?.takeIf { it.token == token } ?: return
        if (!session.release(token)) return
        trigger.held.set(false)
        trigger.up = SystemClock.elapsedRealtime()
        render()
    }

    private fun disarm(reason: String) {
        active?.let { release(it.token) }
        session.disarm()
        overlay.hide()
        main.removeCallbacks(tick)
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        if (!destroyed) stopSelf()
        ProbeJournal.event(reason)
    }

    override fun onDestroy() {
        destroyed = true
        disarm("destroyed")
        application.unregisterActivityLifecycleCallbacks(activityVisibility)
        unregisterReceiver(screenOff)
        worker.shutdown()
        super.onDestroy()
    }

    private fun notification() =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.ptt_probe_title))
            .setContentText(getString(session.phase.label()))
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NOTICE,
                    Intent(this, FloatingPttProbeActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build()

    private fun render() {
        val phase = session.phase
        overlay.renderLabel(
            when (phase) {
                ProbePhase.STARTING -> R.string.ptt_overlay_starting
                ProbePhase.SAMPLING -> R.string.ptt_overlay_sampling
                ProbePhase.RELEASING -> R.string.ptt_overlay_releasing
                else -> R.string.ptt_overlay_idle
            },
            activeTone = phase == ProbePhase.SAMPLING,
        )
        if (foreground) getSystemService(NotificationManager::class.java).notify(NOTICE, notification())
    }

    companion object {
        const val ARM = "io.github.ts3mobile.app.probe.ARM"
        private const val CHANNEL = "floating-ptt-probe"
        private const val NOTICE = 8012
        private const val CHECK_MILLIS = 100L
    }
}

internal fun ProbePhase.label(): Int =
    when (this) {
        ProbePhase.IDLE -> R.string.ptt_probe_idle
        ProbePhase.READY -> R.string.ptt_probe_ready
        ProbePhase.STARTING -> R.string.ptt_probe_starting
        ProbePhase.SAMPLING -> R.string.ptt_probe_sampling
        ProbePhase.RELEASING -> R.string.ptt_probe_releasing
    }

package io.github.ts3mobile.app.probe

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.ConnectedPttPreparation
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import kotlinx.coroutines.flow.StateFlow

internal class ConnectedPttProbeHost(
    private val owner: Activity,
    private val preparation: ConnectedPttPreparation,
    private val state: StateFlow<TeamSpeakServiceState>,
) : Application.ActivityLifecycleCallbacks {
    private data class Press(val token: Long, val started: Long, val initialFrames: Long)

    private val context = owner.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var active: Press? = null
    private var registered = false
    private var watchingScreen = false
    private val overlay =
        ProbeOverlay(
            context,
            ::press,
            ::release,
            stop = preparation::disarm,
            details = { context.connectedPttOverlayDetails(state.value, preparation.mode) },
            windowLost = { main.post { if (registered) preparation.disarm() } },
            holdLabel = R.string.ptt_connected_hold,
            selectMode = ::selectMode,
        )
    private val screenOff =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = preparation.disarm()
        }
    private val tick =
        object : Runnable {
            override fun run() {
                if (!canUseOverlay() || !preparation.isValid) {
                    preparation.disarm()
                    return
                }
                renderMicrophone()
                main.postDelayed(this, 100L)
            }
        }

    fun arm(leavingMainActivity: Boolean = false): Boolean {
        if (!owner.hasWindowFocus() || !canUseOverlay() || !preparation.arm(leavingMainActivity, ::close)) return false
        owner.application.registerActivityLifecycleCallbacks(this)
        registered = true
        return try {
            ContextCompat.registerReceiver(context, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            watchingScreen = true
            main.post(tick)
            Log.i(TAG, "armed")
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "receiver unavailable: ${error.javaClass.simpleName}")
            preparation.disarm()
            false
        }
    }

    override fun onActivityStarted(activity: Activity) = preparation.disarm()

    override fun onActivityStopped(activity: Activity) {
        if (activity !== owner) return
        if (owner.isChangingConfigurations || !canUseOverlay() || !preparation.isValid || !overlay.show()) preparation.disarm()
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity === owner) preparation.disarm()
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityResumed(activity: Activity) = preparation.disarm()

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    private fun canUseOverlay(): Boolean =
        power.isInteractive && !keyguard.isKeyguardLocked && Settings.canDrawOverlays(context) &&
            notifications.areNotificationsEnabled() && audio.mode != AudioManager.MODE_IN_CALL && audio.mode != AudioManager.MODE_RINGTONE

    private fun renderMicrophone() {
        val session = state.value
        val recording = session.actualAudioDevices.recording
        val requested = preparation.captureRequested
        val transmitting = requested && session.isTransmitting
        overlay.renderLabel(
            when {
                transmitting -> R.string.ptt_connected_frames
                recording && !requested -> R.string.ptt_overlay_releasing
                recording && preparation.mode == MicrophoneMode.VOLUME_GATE -> R.string.ptt_connected_listening
                recording -> R.string.ptt_connected_capture
                requested -> R.string.ptt_overlay_starting
                else -> R.string.ptt_overlay_idle
            },
            activeTone = recording || transmitting,
            transmitting = transmitting,
        )
    }

    private fun selectMode(mode: MicrophoneMode) {
        if (!canUseOverlay()) {
            preparation.disarm()
            return
        }
        if (preparation.mode == mode) return
        if (preparation.selectMode(mode)) renderMicrophone()
    }

    private fun press(): Long? {
        if (!canUseOverlay()) {
            preparation.disarm()
            return null
        }
        val initialFrames = preparation.framesProvided
        val token = preparation.press() ?: return null
        active = Press(token, SystemClock.elapsedRealtime(), initialFrames)
        Log.i(TAG, "press=$token")
        return token
    }

    private fun release(token: Long) {
        preparation.release(token)
        val press = active?.takeIf { it.token == token } ?: return
        active = null
        val frames = preparation.framesProvided - press.initialFrames
        val heldMs = SystemClock.elapsedRealtime() - press.started
        Log.i(TAG, "release=$token frames=$frames held_ms=$heldMs")
    }

    private fun close() {
        main.removeCallbacksAndMessages(null)
        overlay.hide()
        if (watchingScreen) context.unregisterReceiver(screenOff)
        if (registered) owner.application.unregisterActivityLifecycleCallbacks(this)
        registered = false
        Log.i(TAG, "disarmed")
    }

    private companion object {
        const val TAG = "TS3_ConnectedPtt"
    }
}

internal fun Context.connectedPttOverlayDetails(
    session: TeamSpeakServiceState,
    mode: MicrophoneMode?,
): ProbeOverlayDetails {
    val snapshot = session.snapshot
    val channelId = snapshot.currentChannelId
    val channel = snapshot.channels.firstOrNull { it.id == channelId }
    val members = snapshot.participants.filter { it.channelId == channelId }
    val speakers = members.filter { it.isTalking && it.id != snapshot.ownClientId }
    val visibleSpeakers = speakers.take(3).map { it.nickname.take(30) }
    return ProbeOverlayDetails(
        title = channel?.name?.take(96) ?: getString(R.string.ptt_overlay_channel),
        membersLabel = resources.getQuantityString(R.plurals.ptt_overlay_members, members.size, members.size),
        speakers = visibleSpeakers,
        moreSpeakers = speakers.size - visibleSpeakers.size,
        mode = mode,
    )
}

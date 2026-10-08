package io.github.ts3mobile.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Android power observation and user opt-in; all leases and release rules live in the controller. */
internal class BackgroundRuntimeController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<TeamSpeakServiceState>,
    private val networkAvailable: StateFlow<Boolean>,
    private val sessionGeneration: SessionGeneration,
    private val recordEvent: (DiagnosticEventKind, Long) -> Unit,
) : AutoCloseable {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val preferences = context.getSharedPreferences("background_voice", Context.MODE_PRIVATE)
    private val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ts3-mobile:voice-session")
    private var observeJob: Job? = null
    private var receiverRegistered = false
    private val powerController =
        SessionPowerController(
            scope = scope,
            cpuLock =
                object : SessionCpuLock {
                    override val isHeld: Boolean get() = wakeLock.isHeld

                    override fun acquire(timeoutMs: Long) = wakeLock.acquire(timeoutMs)

                    override fun release() = wakeLock.release()
                },
            clockMs = SystemClock::elapsedRealtime,
            isSessionActive = { !sessionGeneration.isDisconnectRequested },
            onHeldChanged = { held ->
                recordEvent(
                    if (held) DiagnosticEventKind.CPU_LOCK_ACQUIRED else DiagnosticEventKind.CPU_LOCK_RELEASED,
                    0L,
                )
                state.update { it.copy(backgroundRuntime = it.backgroundRuntime.copy(cpuLockHeld = held)) }
            },
        )
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) = refresh()
        }

    fun start() {
        wakeLock.setReferenceCounted(false)
        state.update {
            it.copy(backgroundRuntime = it.backgroundRuntime.copy(keepCpuAwake = preferences.getBoolean(KEEP_AWAKE_KEY, false)))
        }
        refresh()
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        observeJob =
            scope.launch {
                combine(
                    state.map { it.status.phase to it.backgroundRuntime.keepCpuAwake }.distinctUntilChanged(),
                    networkAvailable,
                ) { (phase, enabled), available -> Triple(phase, enabled, available) }
                    .distinctUntilChanged()
                    .collect { (phase, enabled, available) ->
                        powerController.update(enabled, phase, available && !sessionGeneration.isDisconnectRequested)
                    }
            }
    }

    fun setKeepCpuAwake(enabled: Boolean) {
        preferences.edit().putBoolean(KEEP_AWAKE_KEY, enabled).apply()
        state.update { it.copy(backgroundRuntime = it.backgroundRuntime.copy(keepCpuAwake = enabled)) }
    }

    fun releaseForDisconnect() {
        powerController.update(false, state.value.status.phase, false)
    }

    fun refresh() {
        val batteryExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        val powerSave = powerManager.isPowerSaveMode
        val idle = powerManager.isDeviceIdleMode
        state.update {
            it.copy(
                backgroundRuntime =
                    it.backgroundRuntime.copy(
                        batteryOptimizationExempt = batteryExempt,
                        powerSaveMode = powerSave,
                        deviceIdle = idle,
                    ),
            )
        }
        val flags =
            (if (batteryExempt) 1L else 0L) or
                (if (powerSave) 2L else 0L) or
                (if (idle) 4L else 0L) or
                (if (powerManager.isInteractive) 8L else 0L)
        recordEvent(DiagnosticEventKind.POWER_STATE, flags)
    }

    override fun close() {
        observeJob?.cancel()
        powerController.close()
        if (receiverRegistered) {
            context.unregisterReceiver(receiver)
            receiverRegistered = false
        }
    }

    companion object {
        private const val KEEP_AWAKE_KEY = "keep_cpu_awake"
    }
}

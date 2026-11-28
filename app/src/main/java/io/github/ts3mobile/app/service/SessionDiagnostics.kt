package io.github.ts3mobile.app.service

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class SessionDiagnostics(
    private val context: Context,
    private val scope: CoroutineScope,
    private val writer: SessionDiagnosticWriter,
) {
    private val lifecycle = Any()
    private var closed = false
    private var stateJob: Job? = null

    fun record(
        kind: DiagnosticEventKind,
        code: Long = 0L,
        timeMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(lifecycle) {
            if (!closed) writer.append(kind, code, timeMs)
        }
    }

    fun start(state: StateFlow<TeamSpeakServiceState>) {
        record(DiagnosticEventKind.SERVICE_CREATED)
        recordPreviousExit()
        stateJob =
            scope.launch {
                state.map { it.status.phase }.distinctUntilChanged().collect { phase ->
                    val kind =
                        when (phase) {
                            ConnectionPhase.CONNECTING -> DiagnosticEventKind.CONNECTING
                            ConnectionPhase.CONNECTED -> null // Explicit success hook records every accepted success.
                            ConnectionPhase.RECONNECTING -> DiagnosticEventKind.RECONNECTING
                            ConnectionPhase.DISCONNECTED -> DiagnosticEventKind.DISCONNECTED
                            ConnectionPhase.ERROR -> DiagnosticEventKind.ERROR
                            ConnectionPhase.DISCONNECTING -> null
                        }
                    kind?.let { record(it) }
                }
            }
    }

    fun recordFailure(status: ConnectionStatus) {
        // Classify locally; never persist the raw protocol/platform message.
        val code =
            when {
                status.detail?.contains("timeout", ignoreCase = true) == true ||
                    status.detail?.contains("timed out", ignoreCase = true) == true -> 1L
                status.detail?.contains("network", ignoreCase = true) == true -> 2L
                else -> 0L
            }
        record(
            if (status.retryable) DiagnosticEventKind.RETRYABLE_FAILURE else DiagnosticEventKind.TERMINAL_FAILURE,
            code,
        )
    }

    suspend fun awaitReady() = writer.awaitLoaded()

    fun export(
        snapshot: DiagnosticsSnapshot,
        audio: String? = null,
    ): String =
        "{\"schema\":\"ts3-mobile-report/v2\",\"session\":${snapshot.toRedactedJson()},\"history\":${writer.toRedactedJson()}" +
            (audio?.let { ",\"audio\":$it" } ?: "") + "}"

    fun close() {
        stateJob?.cancel()
        synchronized(lifecycle) {
            if (closed) return
            closed = true
            writer.append(DiagnosticEventKind.SERVICE_DESTROYED, 0L, System.currentTimeMillis())
        }
    }

    private fun recordPreviousExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val preferences = context.getSharedPreferences("diagnostic_exit", Context.MODE_PRIVATE)
        val lastTimestamp = preferences.getLong("timestamp", 0L)
        try {
            val manager = context.getSystemService(ActivityManager::class.java)
            val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 4)
            val exit = exits.firstOrNull { it.pid != Process.myPid() && it.timestamp > lastTimestamp } ?: return
            record(DiagnosticEventKind.PREVIOUS_PROCESS_EXIT, exit.reason.toLong(), exit.timestamp)
            preferences.edit().putLong("timestamp", exit.timestamp).apply()
        } catch (_: SecurityException) {
            // Some vendor systems restrict exit inspection; the journal remains usable.
            record(DiagnosticEventKind.PREVIOUS_PROCESS_EXIT, -1L)
        }
    }
}

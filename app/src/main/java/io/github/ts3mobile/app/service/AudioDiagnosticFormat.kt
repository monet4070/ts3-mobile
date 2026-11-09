package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.AudioReceiveCounter
import io.github.ts3mobile.audio.opus.AudioReceiveDiagnostics
import io.github.ts3mobile.audio.opus.AudioReceivePeak
import io.github.ts3mobile.audio.opus.AudioRouteKind
import io.github.ts3mobile.audio.opus.AudioRoutingErrorKind
import io.github.ts3mobile.audio.opus.PlaybackStatus
import io.github.ts3mobile.protocol.ConnectionPhase

/** Only enum names and validated numbers can cross the persistence/export boundary. */
internal object AudioDiagnosticFormat {
    const val HEADER = "TS3_AUDIO_DIAGNOSTICS_V1"

    fun session(value: AudioDiagnosticSession): String = "S\t${value.id}\t${value.startedMs}\t${value.endedMs}"

    fun event(value: AudioDiagnosticEntry): String = "E\t${value.timeMs}\t${value.session}\t${value.kind.name}\t${value.value}"

    fun sample(value: AudioDiagnosticSample): String =
        with(value) {
            val c = context
            listOf(
                "P", timeMs, elapsedMs, windowMs, session, snapshot.generation, snapshot.active,
                snapshot.actualRoute?.name ?: "NONE", c.phase.name, c.playback.name, c.selectedRoute.name,
                c.switching, c.safetyMuted, c.muted, c.routeError?.name ?: "NONE", c.network.name,
                c.networkGeneration, c.cpuLockHeld,
                AudioReceiveCounter.entries.joinToString(",") { "${it.name}=${snapshot.counters[it] ?: 0}" },
                AudioReceivePeak.entries.joinToString(",") { "${it.name}=${snapshot.peaks[it] ?: 0}" },
            ).joinToString("\t")
        }

    fun parseSession(fields: List<String>): AudioDiagnosticSession {
        require(fields.size == 4)
        return AudioDiagnosticSession(positive(fields[1]), positive(fields[2]), number(fields[3]))
    }

    fun parseEvent(fields: List<String>): AudioDiagnosticEntry {
        require(fields.size == 5)
        return AudioDiagnosticEntry(
            positive(fields[1]),
            positive(fields[2]),
            enumValueOf(fields[3]),
            fields[4].toLong().also {
                require(it in -1..Long.MAX_VALUE)
            },
        )
    }

    fun parseSample(f: List<String>): AudioDiagnosticSample {
        require(f.size == 20)
        val counters = f[18].split(',').associate { it.substringBefore('=') to number(it.substringAfter('=')) }
        val peaks = f[19].split(',').associate { it.substringBefore('=') to number(it.substringAfter('=')) }
        require(counters.keys.all { key -> AudioReceiveCounter.entries.any { it.name == key } })
        require(peaks.keys.all { key -> AudioReceivePeak.entries.any { it.name == key } })
        return AudioDiagnosticSample(
            positive(f[1]),
            number(f[2]),
            number(f[3]),
            positive(f[4]),
            AudioReceiveDiagnostics(
                positive(f[5]),
                f[6].toBooleanStrict(),
                counters.mapKeys { enumValueOf<AudioReceiveCounter>(it.key) },
                peaks.mapKeys { enumValueOf<AudioReceivePeak>(it.key) },
                f[7].takeUnless { it == "NONE" }?.let { enumValueOf<AudioRouteKind>(it) },
            ),
            AudioDiagnosticContext(
                enumValueOf<ConnectionPhase>(f[8]), enumValueOf<PlaybackStatus>(f[9]), enumValueOf<AudioRouteKind>(f[10]),
                f[11].toBooleanStrict(), f[12].toBooleanStrict(), f[13].toBooleanStrict(),
                f[14].takeUnless { it == "NONE" }?.let { enumValueOf<AudioRoutingErrorKind>(it) },
                enumValueOf<DiagnosticNetwork>(f[15]), number(f[16]), f[17].toBooleanStrict(),
            ),
        )
    }

    fun json(
        state: AudioDiagnosticArchiveState,
        nowMs: Long,
    ): String =
        buildString {
            append("{\"schema\":\"ts3-mobile-audio/v1\",\"sampleIntervalMs\":5000,\"maxSessions\":3,\"maxSamplesPerSession\":120")
            append(",\"exportedAtMs\":$nowMs,\"lastPersistedAtMs\":${state.persistedMs},\"failedWrites\":${state.failedWrites}")
            append(",\"invalidRecords\":${state.invalidRecords},\"trimmedSamples\":${state.trimmedSamples}")
            append(",\"counterSemantics\":\"cumulative_within_session_not_network_loss\",\"peakSemantics\":\"since_previous_sample\"")
            append(",\"counterKeys\":[")
            append(AudioReceiveCounter.entries.joinToString(",") { "\"${it.name}\"" })
            append("],\"peakKeys\":[")
            append(AudioReceivePeak.entries.joinToString(",") { "\"${it.name}\"" })
            append("],\"sessions\":[")
            append(state.sessions.joinToString(",") { "{\"id\":${it.id},\"startedAtMs\":${it.startedMs},\"endedAtMs\":${it.endedMs}}" })
            append("],\"events\":[")
            append(
                state.events.joinToString(",") {
                    "{\"timeMs\":${it.timeMs},\"session\":${it.session},\"kind\":\"${it.kind.name}\",\"value\":${it.value}}"
                },
            )
            append("],\"samples\":[")
            append(state.samples.joinToString(",", transform = ::sampleJson))
            append("]}")
        }

    private fun sampleJson(value: AudioDiagnosticSample): String =
        with(value) {
            val c = context
            val counts =
                AudioReceiveCounter.entries.joinToString(
                    ",",
                    "[",
                    "]",
                ) { (snapshot.counters[it] ?: 0).coerceAtLeast(0).toString() }
            val maxima = AudioReceivePeak.entries.joinToString(",", "[", "]") { (snapshot.peaks[it] ?: 0).coerceAtLeast(0).toString() }
            "{\"timeMs\":$timeMs,\"elapsedRealtimeMs\":$elapsedMs,\"windowMs\":$windowMs,\"session\":$session," +
                "\"generation\":${snapshot.generation},\"active\":${snapshot.active}," +
                "\"actualRoute\":\"${snapshot.actualRoute?.name ?: "UNKNOWN"}\"," +
                "\"phase\":\"${c.phase.name}\",\"playback\":\"${c.playback.name}\",\"selectedRoute\":\"${c.selectedRoute.name}\"," +
                "\"routeSwitching\":${c.switching},\"safetyMuted\":${c.safetyMuted},\"muted\":${c.muted}," +
                "\"routeError\":\"${c.routeError?.name ?: "NONE"}\",\"network\":\"${c.network.name}\"," +
                "\"networkGeneration\":${c.networkGeneration},\"cpuLockHeld\":${c.cpuLockHeld}," +
                "\"counters\":$counts,\"peaks\":$maxima}"
        }

    private fun number(value: String): Long = value.toLong().also { require(it >= 0) }

    private fun positive(value: String): Long = number(value).also { require(it > 0) }
}

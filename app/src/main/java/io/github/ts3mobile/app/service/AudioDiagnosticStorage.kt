package io.github.ts3mobile.app.service

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Small append checkpoints; compact atomically only on recovery/rotation, never per voice packet. */
internal class AudioDiagnosticStorage(private val file: File) {
    fun read(): AudioDiagnosticArchiveState {
        if (!file.exists()) return AudioDiagnosticArchiveState()
        if (file.length() > MAX_FILE_BYTES) return AudioDiagnosticArchiveState(invalidRecords = 1)
        val sessions = linkedMapOf<Long, AudioDiagnosticSession>()
        val samples = mutableListOf<AudioDiagnosticSample>()
        val events = mutableListOf<AudioDiagnosticEntry>()
        var invalid = 0L
        var failures = 0L
        var persisted = 0L
        var trimmed = 0L
        var pending = PendingCheckpoint()
        try {
            val payload = file.readText()
            val lastNewline = payload.lastIndexOf('\n')
            if (lastNewline < 0) return AudioDiagnosticArchiveState(invalidRecords = 1)
            if (lastNewline != payload.lastIndex) invalid++
            val lines = payload.substring(0, lastNewline).lineSequence().iterator()
            if (lines.next() != AudioDiagnosticFormat.HEADER) return AudioDiagnosticArchiveState(invalidRecords = 1)
            if (!lines.hasNext()) return AudioDiagnosticArchiveState(invalidRecords = 1)
            lines.forEach { line ->
                val fields = line.split('\t')
                if (fields.first() == "M") {
                    val metadata = parseMarker(fields)
                    if (metadata != null && !pending.corrupt) {
                        pending.sessions.forEach { sessions[it.id] = it }
                        samples.addAll(pending.samples)
                        events.addAll(pending.events)
                        failures = metadata[0]
                        invalid = maxOf(invalid, metadata[1])
                        persisted = metadata[2]
                        trimmed = metadata[3]
                    } else {
                        invalid += pending.rows + 1
                    }
                    pending = PendingCheckpoint()
                } else {
                    pending.add(fields)
                }
            }
        } catch (_: IOException) {
            invalid++
        } catch (_: SecurityException) {
            invalid++
        }
        invalid += pending.rows
        val retained = sessions.values.toList().takeLast(AudioDiagnosticArchive.MAX_SESSIONS)
        val ids = retained.map { it.id }.toSet()
        val sampleCounts = mutableMapOf<Long, Int>()
        val keptSamples =
            samples.asReversed().filter {
                val count = sampleCounts[it.session] ?: 0
                (it.session in ids && count < AudioDiagnosticArchive.MAX_SAMPLES_PER_SESSION).also {
                        keep ->
                    if (keep) sampleCounts[it.session] = count + 1
                }
            }.asReversed()
        val eventCounts = mutableMapOf<Pair<Long, Boolean>, Int>()
        val keptEvents =
            events.asReversed().filter {
                val key = it.session to (it.kind == AudioDiagnosticEvent.USER_MARK)
                val count = eventCounts[key] ?: 0
                (it.session in ids && count < AudioDiagnosticArchive.eventLimit(it.kind)).also { keep ->
                    if (keep) eventCounts[key] = count + 1
                }
            }.asReversed()
        return AudioDiagnosticArchiveState(retained, keptSamples, keptEvents, failures, invalid, persisted, trimmed)
    }

    fun write(
        checkpoint: AudioDiagnosticCheckpoint,
        nowMs: Long,
    ) {
        val state = checkpoint.state
        val marker = "M\t${state.failedWrites}\t${state.invalidRecords}\t$nowMs\t${state.trimmedSamples}\n"
        val append =
            (checkpoint.rows.joinToString("\n", postfix = if (checkpoint.rows.isEmpty()) "" else "\n") + marker).toByteArray(
                Charsets.UTF_8,
            )
        file.parentFile?.mkdirs()
        if (!checkpoint.rewrite && file.isFile && file.length() + append.size <= MAX_FILE_BYTES) {
            FileOutputStream(file, true).use {
                it.write(append)
                it.fd.sync()
            }
            return
        }
        val payload =
            buildString {
                append(AudioDiagnosticFormat.HEADER).append('\n')
                state.sessions.forEach { append(AudioDiagnosticFormat.session(it)).append('\n') }
                state.samples.forEach { append(AudioDiagnosticFormat.sample(it)).append('\n') }
                state.events.forEach { append(AudioDiagnosticFormat.event(it)).append('\n') }
                append(marker)
            }.toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_FILE_BYTES) throw IOException("Audio diagnostic archive exceeds its bound")
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use {
            it.write(payload)
            it.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun parseMarker(fields: List<String>): List<Long>? {
        if (fields.size != 5) return null
        return fields.drop(1).map { it.toLongOrNull()?.takeIf { value -> value >= 0 } ?: return null }
    }

    private class PendingCheckpoint {
        val sessions = mutableListOf<AudioDiagnosticSession>()
        val samples = mutableListOf<AudioDiagnosticSample>()
        val events = mutableListOf<AudioDiagnosticEntry>()
        var rows = 0L
            private set
        var corrupt = false
            private set

        fun add(fields: List<String>) {
            rows++
            try {
                when (fields.first()) {
                    "S" -> sessions.add(AudioDiagnosticFormat.parseSession(fields))
                    "P" -> samples.add(AudioDiagnosticFormat.parseSample(fields))
                    "E" -> events.add(AudioDiagnosticFormat.parseEvent(fields))
                    else -> corrupt = true
                }
            } catch (_: IllegalArgumentException) {
                corrupt = true
            }
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 1_048_576L
    }
}

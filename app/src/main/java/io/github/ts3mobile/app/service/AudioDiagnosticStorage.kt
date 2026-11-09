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
        try {
            file.useLines { lines ->
                val iterator = lines.iterator()
                if (!iterator.hasNext() || iterator.next() != AudioDiagnosticFormat.HEADER) {
                    return AudioDiagnosticArchiveState(invalidRecords = 1)
                }
                iterator.forEach { line ->
                    try {
                        val f = line.split('\t')
                        when (f.first()) {
                            "S" -> AudioDiagnosticFormat.parseSession(f).let { sessions[it.id] = it }
                            "P" -> samples.add(AudioDiagnosticFormat.parseSample(f))
                            "E" -> events.add(AudioDiagnosticFormat.parseEvent(f))
                            "M" -> {
                                require(f.size == 5)
                                failures = positiveOrZero(f[1])
                                invalid = maxOf(invalid, positiveOrZero(f[2]))
                                persisted = positiveOrZero(f[3])
                                trimmed = positiveOrZero(f[4])
                            }
                            else -> invalid++
                        }
                    } catch (_: IllegalArgumentException) {
                        invalid++
                    }
                }
            }
        } catch (_: IOException) {
            invalid++
        } catch (_: SecurityException) {
            invalid++
        }
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

    private fun positiveOrZero(value: String): Long = value.toLong().also { require(it >= 0) }

    companion object {
        const val MAX_FILE_BYTES = 1_048_576L
    }
}

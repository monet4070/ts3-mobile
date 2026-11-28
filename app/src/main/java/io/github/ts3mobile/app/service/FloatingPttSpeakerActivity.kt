package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.VoiceFrame

internal class FloatingPttSpeakerActivity(private val clockMs: () -> Long) {
    private val lastHeardAt = mutableMapOf<Int, Long>()
    private var observing = false

    @Synchronized
    fun setObserving(enabled: Boolean) {
        observing = enabled
        lastHeardAt.clear()
    }

    @Synchronized
    fun record(frame: VoiceFrame) {
        if (observing && !frame.isWhisper && frame.encodedData.isNotEmpty()) {
            lastHeardAt[frame.clientId] = clockMs()
        }
    }

    @Synchronized
    fun recentClientIds(): Set<Int> {
        if (lastHeardAt.isEmpty()) return emptySet()
        val now = clockMs()
        lastHeardAt.values.removeAll { now - it >= ACTIVE_MILLIS }
        return lastHeardAt.keys.toSet()
    }

    private companion object {
        const val ACTIVE_MILLIS = 1_000L
    }
}

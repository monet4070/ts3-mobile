package io.github.ts3mobile.audio.opus

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.protocol.VoiceCodec
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

internal data class OpusCompatibilityFixture(
    val producer: String,
    val name: String,
    val codec: VoiceCodec,
    val channels: Int,
    val samples: Int,
    val leftHz: Int,
    val rightHz: Int,
    val dtxPackets: Int,
    val packets: List<ByteArray>,
)

internal object OpusCompatibilityFixtures {
    fun load(): List<OpusCompatibilityFixture> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val hashes = JSONObject(assets.open("opus/sha256.json").bufferedReader().use { it.readText() })
        return hashes.keys().asSequence().sorted().flatMap { name ->
            val bytes = assets.open("opus/$name").use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(digest == hashes.getString(name)) { "Fixture checksum mismatch" }
            val corpus = JSONObject(bytes.toString(Charsets.UTF_8))
            check(corpus.getInt("schema") == 1 && corpus.getInt("sampleRate") == 48_000)
            val cases = corpus.getJSONArray("cases")
            (0 until cases.length()).map { index ->
                val scenario = cases.getJSONObject(index)
                val encoded = scenario.getJSONArray("packets")
                val packets =
                    (0 until encoded.length()).map { packetIndex ->
                        val hex = encoded.getString(packetIndex)
                        check(hex.isNotEmpty() && hex.length % 2 == 0)
                        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                    }
                OpusCompatibilityFixture(
                    corpus.getString("producer"), scenario.getString("name"),
                    VoiceCodec.valueOf(scenario.getString("codec")), scenario.getInt("channels"), scenario.getInt("samples"),
                    scenario.getInt("leftHz"), scenario.getInt("rightHz"), scenario.getInt("dtxPackets"), packets,
                )
            }.asSequence()
        }.toList()
    }
}

internal class OpusDecodedMetrics {
    private var sampleIndex = 0L
    private var measured = 0
    private var energy = 0.0
    private var real1000 = 0.0
    private var imaginary1000 = 0.0
    private var real2000 = 0.0
    private var imaginary2000 = 0.0
    val rms: Double get() = sqrt(energy / measured.coerceAtLeast(1))
    val tone1000: Double get() = 2 * hypot(real1000, imaginary1000) / measured.coerceAtLeast(1)
    val tone2000: Double get() = 2 * hypot(real2000, imaginary2000) / measured.coerceAtLeast(1)

    fun add(pcm: ShortArray) {
        pcm.forEach { sample ->
            val position = sampleIndex++
            if (position >= 4_800) {
                val phase = 2 * PI * 1000 * position / 48_000
                energy += sample.toDouble() * sample
                real1000 += sample * cos(phase)
                imaginary1000 += sample * sin(phase)
                real2000 += sample * cos(phase * 2)
                imaginary2000 += sample * sin(phase * 2)
                measured++
            }
        }
    }
}

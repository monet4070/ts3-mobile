package io.github.ts3mobile.audio.opus

internal interface ApmProcessor : AutoCloseable {
    fun reverse(pcm: FloatArray)

    fun capture(
        pcm: FloatArray,
        delayMs: Int,
    ): FloatArray

    fun reset()
}

internal enum class ApmRoute { BUILTIN, WIRED_USB, BLUETOOTH, UNKNOWN }

internal data class ApmExperimentPolicy(
    val route: ApmRoute,
    val sourceCalibrated: Boolean,
    val platformEffectsDisabled: Boolean,
) {
    val eligible: Boolean get() = route in setOf(ApmRoute.BUILTIN, ApmRoute.WIRED_USB) && sourceCalibrated && platformEffectsDisabled
}

internal enum class ApmFallbackReason { NONE, POLICY, CLOCK, REFERENCE, CPU, NATIVE, CLOSED }

internal data class ApmExperimentStatus(val active: Boolean, val reason: ApmFallbackReason)

package io.github.ts3mobile.audio.opus

const val SYSTEM_AUDIO_ROUTE_ID = -1

enum class AudioRouteKind {
    SYSTEM,
    EARPIECE,
    SPEAKER,
    WIRED,
    BLUETOOTH,
    USB,
    OTHER,
}

enum class AudioRoutingErrorKind {
    DEVICE_UNAVAILABLE,
    SWITCH_FAILED,
    SWITCH_DENIED,
    DEVICE_DISCONNECTED,
}

/**
 * A routing failure as data: this module owns device arbitration, not the
 * wording shown to the user, so the caller resolves [kind] against its own
 * resources. [cause] carries the platform detail for [SWITCH_FAILED] only.
 */
data class AudioRoutingError(
    val kind: AudioRoutingErrorKind,
    val cause: String? = null,
)

/**
 * A selectable output route. [deviceName] is the platform product name for
 * detachable devices and is null when the route has no name worth showing;
 * the caller combines it with its own localized name for [kind].
 */
data class AudioRouteOption(
    val id: Int,
    val kind: AudioRouteKind,
    val deviceName: String? = null,
)

data class AudioRoutingState(
    val routes: List<AudioRouteOption> = listOf(SystemRoute),
    val selectedRouteId: Int = SYSTEM_AUDIO_ROUTE_ID,
    val error: AudioRoutingError? = null,
) {
    val selectedRoute: AudioRouteOption
        get() = routes.firstOrNull { it.id == selectedRouteId } ?: SystemRoute

    companion object {
        val SystemRoute =
            AudioRouteOption(
                id = SYSTEM_AUDIO_ROUTE_ID,
                kind = AudioRouteKind.SYSTEM,
            )

        val Default = AudioRoutingState()
    }
}

internal fun resolveSelectedRouteId(
    requestedRouteId: Int,
    routes: List<AudioRouteOption>,
): Int =
    requestedRouteId.takeIf { requested -> routes.any { it.id == requested } }
        ?: SYSTEM_AUDIO_ROUTE_ID

package io.github.ts3mobile.app.service

/**
 * Fixed budgets and closed failure details for the post-switch liveness check.
 *
 * Details are constants, never formatted text, so the diagnostics layer can map
 * them to a closed category without ever storing protocol or network payloads.
 */
internal object ConnectionLivenessPolicy {
    /**
     * Upper bound for the single control round trip that decides whether a
     * session survived a default-network switch. The probe never runs longer
     * than this, and the coordinator discards its result if the listener was
     * replaced or the user disconnected first.
     */
    const val PROBE_TIMEOUT_MS = 5_000L

    /** The default network dropped while a session was established. */
    const val NETWORK_LOST_DETAIL = "network connection lost"

    /** The default network changed and the session failed the bounded liveness check. */
    const val NETWORK_SWITCH_UNVERIFIED_DETAIL = "network switched and the session did not respond"
}

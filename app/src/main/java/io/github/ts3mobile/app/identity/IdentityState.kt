package io.github.ts3mobile.app.identity

import io.github.ts3mobile.protocol.Ts3IdentitySummary

internal enum class IdentityFailure {
    BUSY,
    INVALID_MATERIAL,
    EMPTY,
    UNREADABLE,
    NO_ROLLBACK,
    STALE_CONFIRMATION,
    VERIFICATION_FAILED,
    STORAGE_FAILED,
}

/** No disk/provider exception, identity material or key may escape the boundary. */
internal class IdentityOperationException(val failure: IdentityFailure) : Exception("Identity operation: ${failure.name}")

internal data class IdentitySnapshot(
    val active: Ts3IdentitySummary?,
    val rollback: Ts3IdentitySummary?,
    val unreadable: Boolean,
    val revision: String,
) {
    override fun toString(): String = "IdentitySnapshot(active=${active != null}, rollback=${rollback != null}, unreadable=$unreadable)"
}

package io.github.ts3mobile.app.identity

import io.github.ts3mobile.app.identity.backup.IdentityBackupFailure
import io.github.ts3mobile.app.identity.documents.IdentityImportFailure
import io.github.ts3mobile.protocol.Ts3IdentitySummary

internal enum class IdentityRestoreStatus {
    READY,
    SELECTING,
    READING,
    PREVIEW,
    COMMITTING,
    DONE,
    ALREADY_CURRENT,
    CANCELLING,
    CANCELLED,
    PICKER_UNAVAILABLE,
    FAILED,
}

internal enum class IdentityImportSource { BACKUP, DESKTOP }

internal data class IdentityReplacementPreview(
    val previous: IdentitySnapshot,
    val proposed: Ts3IdentitySummary,
    val rollback: Boolean,
    val source: IdentityImportSource? = null,
) {
    val isAlreadyCurrent: Boolean get() =
        source == IdentityImportSource.DESKTOP && previous.active?.uid == proposed.uid && previous.active.keyOffset == proposed.keyOffset

    val isDesktopDowngrade: Boolean get() =
        source == IdentityImportSource.DESKTOP && previous.active?.uid == proposed.uid &&
            proposed.securityLevel < previous.active.securityLevel
}

internal data class IdentityRestoreState(
    val status: IdentityRestoreStatus = IdentityRestoreStatus.READY,
    val preview: IdentityReplacementPreview? = null,
    val pickerRequest: Long? = null,
    val pickerOutstanding: Boolean = false,
    val backupFailure: IdentityBackupFailure? = null,
    val importFailure: IdentityImportFailure? = null,
    val storeFailure: IdentityFailure? = null,
    val source: IdentityImportSource? = null,
) {
    val working: Boolean get() =
        status in
            setOf(
                IdentityRestoreStatus.READING, IdentityRestoreStatus.COMMITTING, IdentityRestoreStatus.CANCELLING,
            )
    val active: Boolean get() = working || pickerOutstanding || preview != null
}

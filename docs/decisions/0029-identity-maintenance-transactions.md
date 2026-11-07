# ADR-0029: Process identity ownership and recoverable local replacement

Date: 2026-11-07. Status: accepted for C2.1; offline/emulator acceptance recorded.

## Scope and boundaries

The Android Application owns one identity repository and use gate. Service and UI
instances share them. The gate counts every connection owner; replacing an epoch
does not discard an old owner's unfinished work. A connection lease tracks the
handshake, reconnect, terminal cleanup and exit jobs. It retires synchronously and
releases only when tracked jobs actually finish. A maintenance operation claims
the gate synchronously and keeps it through DataStore acknowledgement/readback,
including cancellation. UI phase is not qualification. No global mutable singleton.
A rejected startForegroundService request briefly promotes a truthful maintenance
notification and immediately removes it/stops that startId. A bound service can
survive stopSelfResult; stopping alone does not satisfy Android's promotion deadline.
A real API35 regression test found that crash and now waits 12 seconds to catch it.

ConnectionCoordinator (>1,000 lines) and TeamSpeakService (>600) already exceed
the preferred file size. Keep only lifecycle seams here; use a dedicated lease
class and repository/storage files. D2 will extract monitoring separately, with
unchanged policies. Do not combine that migration with identity replacement.

## Storage and failure contract

Preserve ts3_identity, encrypted_identity, ts3_mobile_identity_v1 and the v1 local
cipher envelope. Read-only legacy access does not add metadata or rewrite bytes.
Add rollback, pending SHA-256 and recovery evidence fields in the same Preferences
DataStore. Replacement atomically writes active + verified previous rollback +
pending; a second transaction validates readback and clears pending. Pending
recovery validates active/digest first, then rollback; it never creates an identity
when any identity state exists. A pre-existing zero-byte/empty identity Preferences
file is also recovery state, because DataStore otherwise treats it as empty and
would silently permit first creation. The Android file-existence adapter distinguishes
it from a genuinely new installation. Explicit verified import can populate that
readable empty store; a malformed protobuf still fails without a reset. Preserve unreadable originals as recovery evidence.
A valid local rollback is not an external backup. A physically unreadable
Preferences file fails explicitly without deletion; recovery on a fresh installation
from a verified external backup remains possible. Do not claim a two-file repair.

Initial creation is serialized by the same DataStore transaction and awaits its
acknowledgement even when its caller is cancelled. Confirmations carry an opaque
revision so a stale preview cannot replace a newer identity. No replacement
auto-connects. Coordinator caches are emptied when owners retire; connection
registration always loads identity afresh.

The repository exposes summaries, fixed error categories and gate state only.
`IdentityVault(Context).getOrCreate()` keeps its signature, material and storage
compatibility, but failure classes intentionally become `IdentityOperationException`
with fixed `UNREADABLE`/`STORAGE_FAILED` categories and no sensitive cause. Internal
consumers should match these categories instead of platform crypto exception types;
form/bookmark exception behavior is unchanged. Tests retain byte/key preservation
assertions. This is an error-reporting migration, not a ciphertext migration.
Private material is returned solely to connection/export operations, not public
observable state. SAF and UI follow in C2.2/C2.3.

## Validation required

Deterministic JVM races with delayed acknowledgement/cancellation; original
legacy bytes; malformed/missing-key state; failed atomic write; crash between
commit/readback/cleanup; stale confirmation; rollback; actual scratch DataStore
and Keystore on emulator; full formatting/static/JVM/Android/Release gates.

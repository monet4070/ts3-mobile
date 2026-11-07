# ADR-0031: Confirmed identity import and local rollback

Date: 2026-11-08. Status: accepted for C2.3; offline/emulator acceptance recorded.

Extend the private identity Activity with a separate restore section/controller and
candidate resource. Export's controller stays cohesive (<300 lines); MainActivity /
MainScreen keep only the menu callback. Both controllers share the Application
repository and qualification gate. Only one user operation is enabled across tabs.

OpenDocument streams are bounded by actual bytes, parsed/limited before KDF,
decrypted on IO, strictly decoded as UTF-8 and inspected for key correspondence.
The private candidate owns a ByteArray, never a public String/StateFlow/saved state
or plaintext file. Passphrase is consumed and cleared after preflight. No read or
cancelled picker generates an identity or connects. Existing physically corrupt
Preferences cannot be reset in place: preserve it and restore external backup on
a fresh installation; readable corrupt ciphertext or an existing empty store can
be replaced explicitly using C2.1's atomic transaction.

Preview shows current and proposed UID, offset and security level plus permission
impact. Confirmation carries the exact old store revision. BUSY or stale revision
retains a validated candidate but requires an explicit refresh and a NEW confirmation;
never silently retry a mutation. Rollback previews the latest verified previous
identity and uses the same qualification/revision/transaction. Local rollback is
not an external backup and cannot recover a lost installation key.

At most one import picker outstanding, private operation ids, orphan/late results
ignored. Preflight cancellation changes no active data. A worker owns candidate
cleanup once confirmation starts; UI destruction must not release repository
qualification while DataStore can still commit. On next entry read authoritative
repository state. No automatic connection after either replacement or rollback.

Validate invalid/auth/oversized/KDF/UTF-8/identity inputs leave active unchanged;
BUSY/stale and cancellation races; independent aliases/stores cross-install;
actual resolver/Compose/Activity and system picker; real minified file import /
confirmation / rollback and final R3 combined upgrade/crash evidence.

Cancellation while a preflight read is still settling publishes CANCELLING and
keeps new requests disabled until the actual job completion callback. This makes
the resource boundary explicit instead of accepting input that cannot start yet.

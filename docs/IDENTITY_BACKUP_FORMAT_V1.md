# TS3 Mobile portable identity backup v1

Status: C1 prototype; no file-import/export UI is connected in this stage.
The underlying plaintext is bounded UTF-8 ts3j INI. This is not a claim of
TeamSpeak desktop-format interoperability. See [ADR-0027](decisions/0027-portable-identity-backup.md).

## Binary layout

All integers are unsigned big-endian. The entire 56-byte header is GCM additional
authenticated data. The only algorithm combination accepted in v1 is AES-256-GCM
with PBKDF2-HMAC-SHA256. No default/fallback algorithm is inferred from malformed
or future files.

| Offset | Size | Value |
| --- | --- | --- |
| 0 | 16 | ASCII `TS3M-ID-BACKUP` followed by two NUL bytes |
| 16 | 2 | version 1 |
| 18 | 1 | cipher 1 = AES-256-GCM,128-bit tag |
| 19 | 1 | KDF 1 = PBKDF2-HMAC-SHA256,256-bit key |
| 20 | 4 | iterations: export 600,000; import 600,000–2,000,000 |
| 24 | 4 | plaintext UTF-8 byte count, 1–65,536 |
| 28 | 16 | fresh cryptographically random salt |
| 44 | 12 | fresh cryptographically random GCM nonce |
| 56 | payload count | encrypted identity bytes |
| 56 + payload count | 16 | authentication tag |

No trailing bytes are allowed. Actual stream reads stop at1 MiB plus one
lookahead byte; provider length metadata is not trusted. Header validation and
exact length arithmetic run before KDF or payload allocation. Input integer
values outside signed JVM representability are rejected as out of bounds; no
integer wrapping or negative allocation is permitted.

Every new export uses a new salt/nonce and independently derived key. The local
Android Keystore is not involved. Wrong passphrase, salt/nonce/body/tag changes
and otherwise valid modified parameters fail authentication without returning
plaintext. Unknown version/algorithm or invalid resource parameters may be
rejected earlier; those errors never trigger an expensive fallback.

## Passphrase and error boundary

Accept 12–1024 well-formed Unicode code points including at least one
non-whitespace code point; cap the UTF-16 input at 2048 units before scanning.
Do not trim, normalize, lowercase or silently truncate. The exact string's UTF-8
encoding is pinned by independent Node/OpenSSL, JVM and Android vectors with
CJK, combining and supplementary characters. Visually equal normalized variants
may be different passphrases. Confirmation must compare the exact sequence.
Length is not a claim of sufficient entropy; use a long, unique passphrase.

The key spec and derived encoded key array are cleared in finally. The caller
owns and clears input passphrase/plaintext buffers when no longer needed.
Java Strings and provider-internal copies cannot be guaranteed erased. Passwords,
private material and provider exception messages must not be logged or persisted
in UI saved state/notifications/diagnostics. The codec returns only fixed failure
categories: invalid passphrase, invalid format, unsupported version/algorithm,
resource limit, authentication failure or unavailable platform crypto.

The codec provides authenticated bytes, not an identity replacement permission.
Before showing a preview or storing them, decode UTF-8 strictly and use
`Ts3IdentityCodec.inspect`: bound INI length, validate private scalar range,
public-point validity and public/private correspondence, require a nonnegative
offset, and summarize UID/offset/security without changing any of them.

## Connection ownership and crash-recovery contract for C2

The tested C1 gate models ownership, not UI phase. A process-owned gate/repository
must survive Service/Activity replacement so an old commit cannot race a new
connection under a separate gate. Manual connection registration, handshakes,
reconnects and their exits retain an owner until actually settled. A replaced
connection's late release cannot release a newer connection. A maintenance lease
excludes new connection registration and other mutations; acquisition is quick
and never waits for disk I/O on the main thread.

Preflight file/KDF/identity validation may run before that lease. On confirmation,
claim maintenance and revalidate at the transaction boundary. Keep the lease
through the DataStore acknowledgement and read-after-write verification, even if
the UI is cancelled/destroyed after commit begins. A short commit owned by the
process/repository must finish or leave recoverable pending state; cancellation
cannot release the gate while an actor may still commit. Before commit, cancel
without touching active identity. Always clear the coordinator cache only after
a successful mutation, and do not auto-connect.

Persist active, previous rollback and pending verification in ONE transaction.
Keep the legacy `encrypted_identity` key populated for old readers. C0 freezes
all original aliases, payload format and storage names; no bulk re-encryption is
part of that prerequisite.

| Crash/failure point | Durable state and next action |
| --- | --- |
| Preflight / before lease / before transaction | Original active remains; no generated replacement |
| Atomic transaction fails | Original active/rollback remain; report write failure |
| Transaction commits before readback | New active + previous rollback + pending marker exist together; never an empty active |
| Restart with valid pending active | Validate UID/key correspondence/expected digest, then clear pending while retaining rollback |
| Pending active fails, rollback decrypts/validates | Atomically restore valid rollback; keep evidence/valid previous copy; do not generate |
| Both fail or Keystore is lost | Preserve original blobs and surface recovery failure; allow only explicitly verified external-backup recovery |
| Readback succeeds before pending cleanup/UI delivery | Restart can repeat validation idempotently; rollback remains available |
| UI disappears after commit | Transaction result is recovered from repository state on next entry; no late UI callback may overwrite newer navigation |

A local rollback is not an external backup and cannot repair a lost Keystore key.
A corrupt/missing-key original can be retained as evidence but cannot be advertised
as a usable rollback. Keep the latest verified rollback after success until the
next explicit replacement/cleanup. External backups must be written, closed,
reopened and authenticated before UI calls them verified; provider/cloud sync is
not implied by close returning successfully.

## Interoperability and review evidence

The shared synthetic vector lives only under
`app/src/testFixtures/resources/identity-backup/v1.properties`. Reproduce with:

```sh
node tools/check-identity-backup-vector.mjs
```

The script uses Node's independent OpenSSL primitives with fixed synthetic salt,
nonce, plaintext and Unicode passphrase, for both export and maximum-import
iterations. Its output is test material, never a user backup. Test resource
wiring includes it only in JVM/Android test artifacts; production APKs must not
include it. Android runtime and KDF evidence for this run is API 35 only; API26
support is documented by Android but not runtime-certified here. No independent
external security audit or low-end physical-device timing is claimed.

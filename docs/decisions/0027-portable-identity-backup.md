# ADR-0027: bounded portable identity backups

Date: 2026-11-07. Status: C1 offline prototype and implementation-side parameter review complete.

The first portable format is an application-owned encrypted container carrying
UTF-8 ts3j INI. Desktop identity-file compatibility is not inferred or promised.
Use platform AES-256-GCM, a 128-bit tag and PBKDF2-HMAC-SHA256 with 600,000
iterations for export. Import permits only 600,000 through 2,000,000 iterations.
Each export generates an independent 16-byte salt and 12-byte nonce. This does
not use the installation's Keystore, so an independently keyed installation can
recover it. A lost passphrase cannot be recovered.

The fixed 56-byte big-endian header is authenticated as GCM AAD: 16-byte magic
`TS3M-ID-BACKUP` plus two NUL bytes; uint16 version 1; uint8 cipher 1; uint8 KDF 1;
uint32 iterations; uint32 plaintext length; 16-byte salt; 12-byte nonce. Ciphertext
is exactly the declared plaintext length plus 16 tag bytes. Reject unknown
version/algorithm, malformed/excess lengths and KDF limits BEFORE key derivation
or payload allocation. File reads are bounded to 1 MiB and plaintext to 64 KiB;
no trailing data, fallback algorithm or unauthenticated plaintext is accepted.

Passphrases are 12–1024 Unicode code points, contain a non-whitespace character,
and must be well-formed UTF-16. No trimming or Unicode normalization is performed.
Cross-provider vectors pin exact UTF-8 semantics, including combining text and
supplementary characters. UI later asks for confirmation and explains that long,
unique passphrases matter; length is not an entropy guarantee. The key spec and
derived key byte array are cleared when possible; JVM/provider copies cannot be
guaranteed erased. Neither material nor passphrases enter diagnostics/logs,
clipboard, saved state, backup, notification or exception text.

`Ts3IdentityCodec.inspect` is an additive domain-facing validation/summary port.
It bounds INI input, verifies a positive in-range private scalar, a valid public
point and its correspondence to that scalar, rejects negative offsets, and
returns only UID, offset and security level. It never improves the offset as an
import side effect. Cipher/KDF/container code stays in the application's identity
infrastructure; the protocol module does not acquire a backup-crypto API.

C1 does not attach file selection or identity mutation to the application.
Before C2, review a shared connection/maintenance ownership gate: preflight KDF
may run while connected, but final commit must claim maintenance only after all
handshake/reconnect/exit ownership is idle. Connect registration synchronously
rejects while a maintenance lease exists; a displayed DISCONNECTED/ERROR phase
alone is insufficient. Token-specific release cannot release a newer owner.
Replacement clears the coordinator's cached material only after commit.

Plan C2 persistence as ONE DataStore transaction retaining `encrypted_identity`
for compatibility, plus rollback and pending-verification metadata. Never delete
active before replacement. On restart verify pending active, then a decryptable
rollback; no auto-generation to hide either failure. Retain the latest verified
rollback after success until a later explicit replacement/cleanup. A corrupt or
missing-key original is preserved, but cannot be called a recoverable rollback;
external verified backup is the recovery mechanism for Keystore loss.

Large-file assessment: C1 adds independent bounded format/KDF/passphrase files
and small codec inspection. ConnectionCoordinator (>1000), TeamSpeakService
(>600), MainActivity/MainScreen (>300) are READ for C2 ownership design but not
modified here. Future C2 wiring must use a separate controller/store and a small
gate rather than moving backup logic into these files. Existing Vault layouts,
aliases and getOrCreate remain unchanged; C0 must prove bidirectional legacy
compatibility before C2 mutation is connected.

Evidence required: JVM/Android cross-provider vectors, independent Node/OpenSSL
vector, random exports, independent keys, wrong passphrase/tamper/truncation,
resource/KDF rejection before derivation, identity correspondence, actual API35
KDF timing, and reviewed maintenance/crash timelines. Android documents the KDF
from API 26; API26 runtime/low-end-device timing is unverified this round. Record
that limit and the absence of an independent external security review; neither
is a production-release certificate.

References accessed 2026-11-07:

- [Android SecretKeyFactory algorithms](https://developer.android.com/reference/javax/crypto/SecretKeyFactory): PBKDF2withHmacSHA256 from API 26.
- [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html): PBKDF2-HMAC-SHA256 600,000-iteration reference. Password-storage advice informs the KDF floor; it does not audit this container design.

See [C1 evidence](../testing/IMPROVEMENT_C1_2026-11-07_ZH.md) and
[the v1 format/recovery contract](../IDENTITY_BACKUP_FORMAT_V1.md). Android35
measured roughly0.80–0.86s for600k and2.66–2.68s for2M; operations must stay off
the main thread. C0 precedes connecting C2 mutation. No external audit, API26
runtime or physical-device acceptance is claimed.

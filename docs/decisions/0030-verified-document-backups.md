# ADR-0030: Verified SAF identity exports

Date: 2026-11-07. Status: accepted for C2.2; offline/emulator acceptance recorded.

Use an independent, non-exported identity Activity from the main menu. MainActivity
and MainScreen exceed 300 lines; add only a menu callback and explicit Activity
navigation. Repository/ownership stays in the Application, document I/O and crypto
in small infrastructure files, operation tokens in a private controller. C2.3 will
extend this page with import/confirmation rather than add logic to MainScreen.

CreateDocument uses application/octet-stream and .ts3mbak. A valid original
identity is read under repository qualification, encrypted on IO, and written to
the selected new document. Close output, reopen input, enforce the real byte bound,
authenticate and compare the exact original plaintext digest. Only that result is
VERIFIED. A provider failure, denied reopen, bad/truncated/other authenticated
content or close failure is UNVERIFIED. Never claim cloud synchronization or delete
an arbitrary existing document to clean up a failure; advise retry to a new file.

Private buffers/passphrase live only in the controller's operation objects, never
StateFlow, SavedStateHandle, logs, clipboard, notification, diagnostics or disk.
Compose password fields use remember, not rememberSaveable. Clear CharArray/ByteArray
in finally; JVM/IME String erasure cannot be promised. The dedicated Activity uses
FLAG_SECURE to exclude screenshots/recents. No persistable URI permission is needed
for the immediate readback; no password hint or filename identity identifier.

At most one picker is outstanding per launcher. An opaque operation id prevents
late picker/I/O results from applying to a newer operation. Cancellation invalidates
results immediately, but a pending picker still must return before another launch.
Rotation keeps the ViewModel's private in-memory operation; process death drops it
and restored picker callbacks are ignored. Cancellation must not clear a buffer
while an in-flight blocking provider call still uses it; that worker owns cleanup.
No identity is created by visiting/exporting from the page, and export never connects.

Required: independent stream/close/reopen/authentication failures; cancellation
and late/duplicate picker/results; private-state checks; synthetic Android resolver
provider and UI lifecycle/system-picker cancellation; full gates and Release smoke.

Actual default-identity UI validation caught `Ts3IdentityCodec.inspect` using the
library Uid object's `toString` instead of its standard Base64 representation.
The summary now uses `toBase64`, exactly as the existing connection HWID path;
a regression checks 20 decoded bytes, 28 Base64 characters and offset stability.
This fixes the additive summary API presentation only; private material, UID bytes,
connection behavior, backup format and local stored files remain unchanged.

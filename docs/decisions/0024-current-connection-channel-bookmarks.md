# ADR-0024: current connection bookmarks and channel selection

Date: 2026-11-07. Status: implemented and offline validated.

Add an explicit Save current connection action and a name/path channel picker
for bookmarks matching the connected endpoint. Keep defaultChannelId, vault
layout and password opt-in unchanged. Ordinary Add continues to use the editable
form; the new action uses a password-free connection context owned by the
coordinator and never reads passwords from the form or active configuration.

An opaque manual-connection token accompanies name-picked channels. Validate the
token, normalized endpoint and channel existence when submitting and again
inside the bookmark DataStore edit, alongside existing revision validation.
Disconnect/replacement makes the captured context invalid. This linearizes the
save's context validation at the transaction mutation; a later disconnect does
not undo an already accepted save. Keep the editor/draft on failure. A deliberate
clear/manual numeric edit leaves the live selection path and stays usable offline.
No DNS-equivalence inference or privilege/password bypass is introduced.

Use a bounded local filter and iterative parent-path construction that handles
orphans/cycles and distinguishes duplicate paths with numeric IDs. Add a visible
Join action and a channel-row accessibility action, both using the same existing
password/join path as double tap. Revalidate session/channel before password
submission; disconnect, replacement or deletion cannot join a stale target.

Large-file assessment: ConnectionCoordinator (>1000 lines), TeamSpeakService
(>600), MainActivity (>300) and MainScreen (near300) receive only narrow context
fields/callbacks. Put selection validation/path policy and picker UI in dedicated
files. Bookmark storage/transactions remain in BookmarkVault; no coordinator
refactor or parallel persistence path is added. New defaulted state/callbacks
preserve source usage; binary compatibility for compiled UI callers is not
promised. Existing BookmarkStore methods remain available with a guarded default
method for adapters, and the real vault validates inside its transaction.

Test pure context/path policy, guard execution with a delayed transaction,
same-name channels, activity form mismatch, stale session/channel/revision,
password opt-in, numeric editing, and accessible/password Join. Device evidence
for this run is restricted to emulators per the maintainer's instruction.
See [A3 evidence](../testing/IMPROVEMENT_A3_2026-11-07_ZH.md). The complete
application suite passes154 instrumentation and263 JVM tests; public-server
permissions and physical-device behavior are outside this round's evidence.

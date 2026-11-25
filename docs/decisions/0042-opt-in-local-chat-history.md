# 0042: Explicitly enabled local chat history

**Status:** Implemented in the development worktree

**Date:** 2026-11-24

An offline viewer is useful after disconnecting. Merging disk history into live chat
would complicate read receipts, pending delivery, channel changes, and reconnects.
Keep the two views independent; do not replace the TS3j session or add network calls.

- Default off. Begin admission only after the opt-in write succeeds. Use the
  connection's configured address and port, locally derived own UID, and an exact
  channel ID or stable peer UID. Reject unresolved channel targets, attempt-only
  private targets, pending/rejected/unknown sends, system rows, drafts and pokes.
- Bound pending work and batch writes on the application IO scope. Expose a gap on
  overflow or write failure rather than block the protocol callback or report a save
  as successful. Messages not yet written can be lost on process exit.
- Keep one versioned AES-GCM archive in a dedicated DataStore with a separate
  Keystore alias, excluded from backup and transfer. Cap the complete encoded
  archive at 512 KiB and 500 records; expire records older than seven days on load
  or append. A backward clock adjustment does not expire future-dated records;
  the size limits still apply. Never overwrite unreadable content through an
  ordinary write. Explicit retry resumes writes only after successfully persisting
  the preserved archive again.
- Settings displays only the active identity, with a new read on identity change.
  Delete a single scoped conversation or clear all; stopping retention clears all
  stored content in the same write. Drain the old queue and invalidate old send
  tickets before edits. Clearing is possible for an unreadable ciphertext without decrypting
  it. No messages or identifiers are placed in saved Activity state or logs.

This does not prove server compatibility beyond the live-text paths already used.

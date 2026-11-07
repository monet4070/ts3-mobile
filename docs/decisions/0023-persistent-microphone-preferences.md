# ADR-0023: persistent microphone preferences

Date: 2026-11-07. Status: implemented and offline validated.

Extend ADR-0016 with an app-owned, versioned Preferences DataStore for the four
non-sensitive capture preferences. Defaults and validation remain in
MicrophoneSettings; no identity, endpoint, password or microphone mode is stored.
Opening/loading/resetting preferences never starts capture. A fresh service
continues to use PUSH_TO_TALK until the user chooses otherwise.

Use field-specific UI edits: a delayed read restores only untouched fields,
including when a user deliberately chooses the current default before loading.
An old whole-settings UI snapshot must not overwrite unrelated newly loaded
fields. Apply settings immediately through MicrophoneController.configure;
serialize and coalesce IO separately, and flush the newest revision on slider
completion/dismissal. Track durable revision separately from in-memory revision.
Loading, pending save, saved, read failure and write failure remain visible.

Unknown versions/corrupt stores block automatic writes and remain intact. Retry
re-reads after load failures or retries a failed write. An explicit Restore
defaults action may replace an unknown schema in this dedicated non-sensitive
preferences file; it must not clear identity/form/bookmark stores. A physically
corrupt DataStore that cannot be edited reports failure rather than deleting it.
Reset invalidates a late initial load so it cannot restore the discarded values.

Service destruction is not a guaranteed final write window. Only a completed
DataStore transaction means Saved; process death while Pending/Saving may lose
the last edit. Never block the UI/audio thread to promise otherwise.

Decomposition: TeamSpeakService (>300 lines), MainActivity (>300 lines) and
MicrophoneController (near300) only receive narrow wiring/callbacks. Storage,
merge/revision/IO policy and preference UI controls live in dedicated files.
Keep audio-opus independent of app storage; its DSP/lifecycle code is unchanged.
Binder/controller callbacks remain internal. MainScreen keeps its existing
whole-settings callback and appends defaulted field-edit/status callbacks for
source compatibility; no binary compatibility claim is made for precompiled UI
callers. Service posts all setting applications to its main handler in the
controller's publication order, avoiding a late IO application overtaking a UI
edit. Existing protocol and persisted formats remain unchanged. No new runtime
dependency is needed.

Verification includes field-level delayed loads, equal-default edits, reset/load
races, latest-write ordering, read/write failure, unknown version preservation,
DataStore reconstruction, and fake capture proving no automatic microphone start.
See [A2 evidence](../testing/IMPROVEMENT_A2_2026-11-07_ZH.md) for253 JVM tests,
141 app instrumentation tests and a real Activity/service process-restart check.

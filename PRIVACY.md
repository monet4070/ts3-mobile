# Privacy

Last updated: 2026-11-09 · [简体中文](PRIVACY_ZH.md)

TS3 Mobile is an unofficial client that connects directly from the Android
device to TeamSpeak servers selected by the user. The maintainers do not
operate an intermediary service for those connections.

## Data handled on the device

The app stores only the selected appearance mode (Light, Dark or System)
and its schema version in a separate local preferences file, excluded from cloud
backup and device transfer. It contains no session or audio data. Unset remains
Light; selection does not start capture or contact a server. Reset affects only
appearance.

- The app stores input gain, noise-suppression preference,
  device-AEC preference and gate threshold in a separate versioned local
  preferences file, excluded from cloud backup/device transfer. It contains no
  audio, identity, endpoint, PTT state or capture mode. Restore defaults affects
  only this tuning file; loading/saving does not start recording.

- The ten most recent successfully connected server addresses and ports are
  saved in app-private local storage for connection-page prefill and selection.
  Only successful connections enter this history. Each history address can be
  deleted from the connection page.
- Separately, the last edited connection form (address, port, nickname and server
  password, including unsubmitted/failed attempts) is saved locally with AES-GCM
  using a separate Android Keystore key. It prefills the next launch without
  connecting. It is excluded from cloud backup and device transfer. Clearing a
  field replaces its remembered value after saving; switching endpoints clears
  the previous password. Deleting a history entry does not clear the form snapshot.
  Save failures are shown in the connection form; edits killed before the short
  delayed save finishes may not survive.
- Up to 50 explicit server bookmarks are stored in a separate versioned AES-GCM
  snapshot with a separate Android Keystore key, excluded from backup and device
  transfer. It includes names, endpoints, nicknames, optional default channel IDs
  and opt-in server/channel passwords. No existing history or form passwords are
  migrated. Disabling password retention removes both bookmark passwords from
  that snapshot; deleting a bookmark does not erase independent form memory,
  history or identity. Loading bookmarks never connects automatically.
- Away status/message and input/output mute flags are sent to the selected server
  through clientupdate. Away messages stay in service memory and are not logged or
  saved. Local mute/stop still works if server synchronization is refused or uncertain.
- Search queries stay in the current dialog, search loaded channel/user snapshot
  data locally, and are not saved or logged. The app no longer exposes a channel
  details screen or an on-demand details request from that screen.
- The generated TeamSpeak identity is encrypted locally with AES-GCM using a key
  protected by Android Keystore.
- Server and channel passwords are used only for the requested connection or
  channel operation. A channel password needed for reconnection is retained in
  foreground-service memory and is discarded when that service process ends.
- Microphone audio is captured for push-to-talk while pressed, continuously in
  always-on mode, and throughout volume-gate listening, including silence. Gate
  mode sends activated audio plus a bounded 120 ms onset buffer and 300 ms tail
  directly to the connected server. Off stops capture and discards buffered audio.
  The microphone foreground-service type remains active during gate listening.
  Gain, suppression, echo and gate parameters are restored from saved tuning
  preferences; microphone mode and PTT state are not persisted and cannot start capture by themselves.
- Incoming voice traffic is decoded and played locally. The app does not record
  or archive voice traffic.
- A local journal retains at most 128 service, network, power, microphone and
  reconnect events. It stores timestamps, fixed categories, numeric system
  flags and Android process-exit reason codes, without addresses, identities,
  exception text or audio. Diagnostics leave the app only when the user chooses
  to copy, save or share them. The CPU-awake preference is also stored locally.
- The app additionally retains bounded numeric audio diagnostics
  in app-private, backup-excluded storage: the last three playback generations,
  up to 120 samples and 64 fixed events each, in a capped 1 MiB log. It includes
  timing, receive/playback counters, coarse route/network kinds and user time marks;
  no audio, payloads, participant/device names or IDs, endpoints or exception text.
  Records survive reopening until rotated or app data is removed. A sudden exit
  may lose the latest unpersisted interval. No diagnostic upload is automatic.
  An explicit Save file action writes JSON to the Android document provider chosen
  by the user, without an extra storage permission. One already-redacted pending
  report (at most 512,000 bytes) is prepared in backup-excluded private storage
  before opening the picker, survives recreation, and is cleared after saving or
  cancellation; an interrupted flow can leave it until the next export. No URI
  grant is persisted. Such saved reports are managed
  by that provider and are not deleted when this app is uninstalled.
- Per-user mute and volume settings stay in foreground-service memory, keyed by
  the participant's stable TeamSpeak identity when available; they are not saved
  across service process recreation.

Channel and private text are sent directly to the selected TeamSpeak server.
Received text, sender names and drafts stay in foreground-service memory with a
500-message / 2 MiB retained-text budget shared by at most 20 conversations and
bounded incoming events/drafts. Participant UIDs are in-memory conversation keys;
numeric conversation IDs and scroll metadata (position, last handled sequence and
follow intent) are the only saved conversation UI state.
Messages, names, UIDs and drafts are not stored in preferences, saved Activity
state, diagnostics or logs.
Ordinary reconnect keeps them in memory; a new manual connection, manual exit,
terminal session failure or process death removes them. Explicit long-press Copy
puts only the selected message body on Android's clipboard, subject to Android's
clipboard behavior. Server operators and participants may retain received text.
Closing a private conversation removes its in-memory messages and draft. A private
conversation without a stable UID is also removed when its connection attempt ends.
Poke sender names and optional reasons use the same in-memory private history and
retention limits. Unsubmitted reasons stay only in the current dialog's memory;
they are not saved across Activity recreation. The latest retained incoming poke
can show an in-app dialog, referencing its existing message rather than storing a
separate copy. Background pokes now use a dedicated Android notification channel
with only a generic "Someone poked you" message. Sender, server and reason are
excluded from notification content and intent extras, including on the lock
screen. The existing notification permission and Android channel/Do Not Disturb
settings control visibility and sound. Returning to the app, dismissing the
notice or ending the session clears the notification. No extra permission or
third-party delivery service is added.

Background ordinary private text uses a separate generic notification. No sender,
body, UID, nickname or endpoint enters its content or Intent. The explicit,
immutable PendingIntent URI contains only a random one-shot capability. Its
service-memory mapping holds at most 16 entries for five minutes, validates the
manual session/disconnect generation and conversation IDs, and is never persisted.
Disconnect, service destruction or closing the captured conversation makes old
clicks unusable. Returning clears the summary without clearing unread messages;
only actually visible messages advance read state. No third-party push is used.

The tuning level indicator retains one scalar snapshot only while subscribed.
It contains RMS, capture/processing/route revisions and gate/sending state; no
PCM, waveform or level history is stored or sent elsewhere. Observation cannot
start recording or ask for permission. The explicit Hold to talk inside tuning
is normal channel transmission, with the same permission and lifecycle rules.

Development builds report missing/unusable local encryption keys without
creating a replacement during a read or overwriting the original encrypted
identity/form/bookmark blob. This preserves failure evidence; it cannot recover
a lost key. Portable external-backup recovery is a separate workflow.

## Data not collected by this project

The app contains no analytics, advertising, telemetry, project-operated crash
reporting, or project-operated backend. The maintainers therefore do not
receive connection details, identities, audio, or usage events from the app.

The “Give the project a Star” menu entry opens the public GitHub repository
through Android's external HTTPS handler. The intent includes no session data
and does not perform a Star automatically. The browser/GitHub service may process
the visit under its own policies.

The TeamSpeak server operator, network provider, Android platform, device
vendor, and any services used to obtain a build may process data under their own
terms. Review the policies of the server and distribution channel you use.

## Permissions

The app requests network access to connect to servers, microphone access for
voice transmission, Bluetooth/audio-device permissions for route selection,
notification access for the foreground connection service, and the Android
permissions required to keep that service active. Denying optional permissions
may disable the related feature.
An optional CPU wake lock supports screen-off voice sessions and bounded
recovery while a usable network exists. It is off by default and does not
bypass Doze; the background runtime check links to Android battery settings.

## Deletion

Uninstalling the app removes its saved server history, local diagnostics,
preferences, encrypted bookmarks, encrypted connection form and encrypted identity
under normal Android behavior. Clearing the app's storage
has the same effect. The app provides passphrase-encrypted identity export/import and local rollback;
deleting this data can lose server permissions tied to the old identity.
Data already received by a TeamSpeak server or other
participants is outside the project's control.

Privacy concerns that are not sensitive may be filed as repository issues.
For sensitive reports, use the private process described in [SECURITY.md](SECURITY.md).

Identity maintenance may retain one encrypted verified previous identity and one
encrypted unreadable payload as recovery evidence in the same excluded local
identity store. Pending verification stores a SHA-256 digest; no plaintext private
key or passphrase is persisted. A local rollback shares the installation key and
cannot recover a lost Keystore. Unreadable Preferences files are preserved and
reported; they are never automatically cleared to create a replacement identity.


Explicit identity export writes only an authenticated passphrase-encrypted
`.ts3mbak` document to the user-selected SAF provider. The provider receives
ciphertext and a generic filename, never the passphrase or plaintext key. Successful
close is followed by bounded reread/authentication and exact identity verification;
this does not certify provider/cloud synchronization. Failed files are not
automatically deleted. Export buffers/passphrase remain in private memory and are
cleared best effort; no plaintext temporary file or persisted URI grant is used.
Passwords are not saved on rotation/process death or put in diagnostics/clipboard.
The identity page disables screenshots/recents capture. JVM/IME memory erasure
cannot be guaranteed. Forgetting the passphrase prevents recovery of the file.


Explicit import reads only the document selected through OpenDocument, enforces
byte/KDF limits, decrypts and validates on the device. Candidate plaintext stays in
private memory until confirmation/cancellation; only identity summaries and fixed
failure categories enter observable UI state. No plaintext file, password hint or
persisted URI grant. A current/proposed identity comparison and explicit confirmation
precede replacement/rollback. The same excluded local identity store retains a
verified previous encrypted identity and bounded recovery evidence (one payload).
Connection ownership blocks mutation until actual work settles; import/rollback
never auto-connect. Forgetting the passphrase or losing all external backups is
not recoverable by the project. Existing unusable aliases and physically corrupt
Preferences are preserved rather than automatically deleted to make recovery pass.

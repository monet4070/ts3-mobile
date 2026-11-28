# Privacy

Last updated: 2026-11-28 · [简体中文](PRIVACY_ZH.md) · [User Guide](docs/USER_GUIDE.md)

TS3 Mobile is an unofficial Android client that connects directly from your device to the TeamSpeak servers you choose. The maintainers do not operate any voice or messaging relay, and the app contains no ads, analytics, telemetry, or project-operated crash reporting backend.

This policy covers **TS3 Mobile 1.3.0 (versionCode 17)**.

---

## Data handled on the device

| Category | Storage and purpose |
| --- | --- |
| TeamSpeak identity | Encrypted locally with AES-GCM backed by Android Keystore for server identification; excluded from cloud backup and device transfer |
| Connection form | Address, port, nickname, and server password encrypted separately with AES-GCM (including unsent/failed edits); prefills on launch without auto-connecting; excluded from backup and transfer |
| Recent endpoints | Up to 10 previously successful server addresses and ports in app-private storage; individually deletable without clearing the form or bookmarks |
| Server bookmarks | Up to 50 entries in a separate versioned AES-GCM vault with its own Keystore key (name, endpoint, nickname, optional default channel, and opt-in passwords); excluded from backup and transfer |
| Offline channel lists | Created only when you tap **Save channel list for offline use**; stores up to 100 channel IDs and paths per endpoint (max 6 endpoints) encrypted with a separate Keystore key, with no passwords, members, or messages; forgettable anytime |
| Microphone tuning & appearance | Stored in separate versioned local preference files (gain, RNNoise, AEC, gate threshold; Light/Dark/System); contain no audio or session data; excluded from backup and transfer |
| Playback, updates & floating PTT preferences | Playback profile, update prerelease preference, floating PTT position/style/auto-show flag, and CPU-awake preference are stored locally; playback and update DataStores are excluded from Android backup rules; capture mode, PTT state, temporary audio device IDs, and Bluetooth call selections are never persisted, and raw hardware addresses are never displayed |
| Local chat history (off by default) | When explicitly enabled, saves eligible new text encrypted with a dedicated Keystore key (newest 7 days, max 500 messages / 512 KiB encoded total); excluded from backup and transfer |
| Local & audio diagnostics | Up to 128 fixed-category service events plus up to 3 audio sessions (120 samples and 64 events each, capped at 1 MiB), excluded from backup; contains no audio, endpoints, passwords, identities, or participant names |

Additional details:

- **Form and bookmark isolation:** Switching endpoints in the form clears the previous password; bookmarks never auto-copy form passwords. Editing a bookmark's host or port clears the draft's old default channel and both passwords; editing only the channel clears only the channel password. Turning off **Save passwords** removes only that bookmark's passwords, never the independent form.
- **Invites and clipboard:** Invite previews live in transient memory and never write to form, bookmark, or recent-server storage. Clipboard access happens only when you choose to paste or read from clipboard. External link passwords and tokens are ignored; sharing includes only address, voice port, and optional channel ID.
- **Local microphone test:** Displays input type and live measurements in memory only—never encodes, transmits, stores audio, or writes device names/measurements into diagnostics.
- **Missing local key protection:** When reading existing local ciphertext, a missing or unusable Keystore key reports an explicit failure and preserves the original ciphertext instead of silently generating a replacement key or overwriting data.
- **Diagnostic export:** Diagnostics are never uploaded automatically. Tapping **Save file** stages one redacted report (up to 512,000 bytes) in backup-excluded private storage, writes it through Android's document picker without extra storage permissions or persisted URI grants, and deletes the staged copy afterward. Exported copies are managed by the chosen document provider and remain if the app is uninstalled.

---

## Service memory and local chat history

### Live session memory

Channel and private text, sender names, drafts, poke history, participant identity mappings, and per-user mute/volume settings stay in foreground-service memory (up to 20 conversations sharing a 500-message / 2 MiB budget). Only numeric conversation IDs and scroll metadata enter saved UI state. Ordinary reconnect keeps live messages; manual disconnect, a new manual session, terminal failure, or process death clears them. Closing a private chat clears its messages and draft immediately. Search queries and unsent poke reasons stay in the current dialog's memory. Away messages and mute flags go only to the active server and are never logged or saved; channel passwords needed for reconnect stay in service memory until process exit.

### Opt-in local chat history (off by default)

When enabled under **Settings → Local chat history**, eligible new text messages (including while connected in the background) are saved locally using a separate Android Keystore key, excluded from backup and device transfer. Stored fields include server address/port, your own TeamSpeak UID, channel ID or stable peer UID, timestamp, direction, sender name (when present), and message body.

- **Never archived:** Drafts, pokes, unconfirmed sends, private keys, passwords, ambiguous recipients, and attachment file contents.
- **Retention and deletion:** Offline browsing displays only the current identity's records. On load or write, the archive prunes entries older than 7 days or beyond 500 messages / 512 KiB encoded total. Delete one conversation, clear all records, or turn the feature off and delete all records without affecting live chat or server copies. Nothing is uploaded or cloud-synced.

---

## Voice, floating PTT, files and notifications

- **Voice capture and playback:** Hold captures while pressed, Always on captures continuously, and Volume gate captures continuously including silence (sending activated speech plus a 120 ms onset and 300 ms tail buffer). Off stops capture and discards buffered audio. The microphone foreground service type stays active while capturing. Voice goes directly to the selected server; incoming voice is decoded and played locally with no recording archive. Tuning level meters hold one scalar snapshot only while observed and never store PCM or history.
- **Floating PTT:** Requires explicit Android **Display over other apps** permission to show the current channel, member count, and active speakers over other apps. Opening, recalling, or auto-showing the overlay never starts recording; only pressing Hold or explicitly choosing Always on/Gate transmits over the normal TeamSpeak voice path. Manage or revoke overlay permission anytime in Floating PTT settings. Standard release builds exclude the Debug local recorder lab.
- **Channel files, chat attachments, and server images:** Downloading channel files or supported same-server chat attachments requires explicit user action and uses only the active server connection—never arbitrary external URLs. Bytes are staged briefly in app-private cache, length-verified, and saved through Android's document picker (exported copies are managed by the chosen provider). Server channel icons and visible avatars come from the connected server and are cached in memory for the current session only.
- **Generic background notifications:** Background pokes and ordinary private messages use separate Android notification channels whose content and Intent extras **never include sender, server, message body, or poke reason** (including on the lock screen). Private-message taps use an in-memory random one-shot capability token (max 16 entries, 5-minute expiry, never persisted) invalidated on disconnect or conversation close. No third-party push service is used.
- **On-demand feature help:** Stores no reading progress, sends no analytics, and never connects, requests permissions, or opens the microphone. Any legacy onboarding flag remains excluded from backup and transfer.

---

## Identity backup and restore

- **Local maintenance and rollback:** The backup-excluded local identity store may keep one verified encrypted previous identity and one unreadable ciphertext as recovery evidence (pending checks store only a SHA-256 digest, never a plaintext key or passphrase). Physically unreadable Preferences files are preserved and reported rather than automatically wiped.
- **Explicit export (`.ts3mbak`):** Writes only an authenticated passphrase-encrypted `.ts3mbak` file with a generic filename to the document provider you choose (never sending your passphrase or plaintext key). After closing, TS3 reopens, authenticates, and checks exact identity equality before confirming success; failed files are not automatically deleted. Passphrases and buffers stay in private memory and are cleared best-effort without entering SavedState, clipboard, or diagnostics, and the identity screen disables screenshots/recents capture. **Forgotten passphrases cannot be recovered.**
- **Explicit restore and desktop INI boundary:** Restore reads only the OpenDocument file you pick, enforces byte/KDF limits, decrypts on-device, displays a current vs proposed comparison, and requires explicit confirmation without auto-connecting. Standard release builds keep desktop TS3 `.ini` import disabled; Debug and opt-in QA builds offer a separate preview entry accepting a single `.ini` up to 64 KiB without saving plaintext copies, retaining URIs, or importing display fields.

---

## Permissions and external links

- **Android permissions:** Network access connects to servers; microphone permission enables voice transmission; Bluetooth/audio permissions support route selection; notification and foreground-service permissions support active sessions and alerts; overlay permission enables optional floating PTT; optional CPU wake lock is off by default and does not bypass Doze or all OEM power policies. Denying optional permissions disables only the related feature.
- **External links:** **Give the project a Star** opens the public GitHub repository through Android's HTTPS handler with no session data and never stars automatically. Chat links show the target URL for confirmation before opening. Your TeamSpeak server operator, network provider, Android/OEM platform, browser, and document providers handle data under their own policies.

---

## Data not collected by this project

TS3 Mobile contains no analytics, advertising, telemetry, project-operated crash reporting, or project-operated backend. The maintainers never automatically receive connection details, identities, audio, or usage events.

## Deletion and contact

- **Deleting local data:** Uninstalling the app or clearing its storage normally deletes saved server history, local chat history, diagnostics, preferences, offline channel lists, encrypted bookmarks, encrypted connection form, and encrypted identity (without a verified `.ts3mbak` backup, identity-bound server permissions may be lost). Deleting local data cannot remove content already received by servers or other participants; files exported via Android's document picker stay with the chosen provider.
- **Contact:** File non-sensitive privacy questions as [GitHub Issues](https://github.com/monet4070/ts3-mobile/issues). Report sensitive or security issues privately following [SECURITY.md](SECURITY.md).

# TS3 Mobile 1.3.0 Release Notes

**versionCode 17 · Requires Android 8.0 (API 26)+ · Compared with 1.2.0 (versionCode 16)**

[简体中文](RELEASE_NOTES_ZH.md) · [User Guide](../../USER_GUIDE.md) · [Install & Upgrade](../../INSTALLATION.md#english) · [Compatibility](../../COMPATIBILITY.md#english) · [Verification Status](../CANDIDATE_STATUS_2026-11-25.md)

TS3 Mobile 1.3.0 builds on 1.2.0 with **connected floating PTT**, **separate input and output audio device selection (including LE Audio and explicit Bluetooth call headset mic)**, **read-only channel files and chat attachments**, **opt-in local chat history**, **bookmark credential isolation and offline channel lists**, and **on-demand feature help**, alongside major reliability hardening for **Wi-Fi ↔ mobile data handoff**, **Bluetooth safety muting**, and **session exit/reconnect concurrency**.

---

## New Features Since 1.2.0

### Connected Floating PTT

- **Talk and see active speakers over other apps:** While joined to a channel, open **More options → Settings → Floating PTT**, grant Android's "Display over other apps" permission, and show the overlay manually—or recall it from the active connection notification (**Show PTT**).
- **Standard and Minimal styles:** Pick **Standard overlay** (default; shows one active speaker's nickname while collapsed) or **Minimal overlay** (compact two-icon collapsed bar) via preview cards.
- **Compact speaker strip:** Drag the left handle to move the overlay, or tap it to expand a fixed-height row showing up to three active speakers (plus `+N` overflow) and microphone mode switches without shifting buttons when idle.
- **Optional auto-show when leaving the app:** Enable **Auto-show when leaving the app** (off by default) to display the overlay when going Home or using Back on the main screen (Android 12+) while joined to a channel with the microphone idle. Showing or recalling the overlay never starts recording.
- **Safety boundaries:** Only **Hold** mode transmits when pressing the right-hand talk target and stops on release; in other modes that tile only reflects live microphone state. Locking the screen, leaving the channel, or returning to the app ends floating capture, and an ongoing local microphone test blocks concurrent floating capture.

### Read-Only Channel Files, Same-Server Chat Attachments & Server Images

- **Browse and download channel files:** Join a channel and open **More options → Channel files** to browse and explicitly download files; tap **Download attachment** on supported same-server attachments in chat.
- **Verified save workflow:** Downloads are capped at **20 MiB per file** and always use the active connected server peer. After verifying the exact received byte length, choose **Save verified file…** to pick a destination in Android's file picker; verified downloads survive screen recreation.
- **Avatar and channel icon previews:** Tap an available avatar in the **Users** list or a custom channel icon where displayed to preview it. Channel member rows retain their voice status indicators. Images are cached in memory for the current session only.
- **Permissions and limits:** Listing and downloading depend on server permissions. Uploads, renaming, deletion, automatic downloads, and cross-server retrieval are not supported.

### Opt-In Local Chat History & Rich Chat Display

- **Off-by-default encrypted local archive:** Enable **More options → Settings → Local chat history** to save eligible new text messages locally (including while connected in the background) using a dedicated Android Keystore key excluded from cloud backup and device transfer. Use **Browse saved messages** to read the current identity's records offline.
- **Bounded retention and full user control:** Prunes on load or write to the newest 7 days and up to 500 messages / 512 KiB encoded total. Delete individual conversations, clear all records, or turn the feature off and delete all records. Never backfills old messages, saves drafts/pokes/unconfirmed sends/passwords/attachment bytes, resends messages, or syncs to the cloud.
- **Basic BBCode and link safety:** Displays basic TeamSpeak BBCode (bold, italic, code, and explicit HTTP(S) links), preserves raw text when copying, and shows the target destination for confirmation before opening external links without fetching previews.

### Bookmark Credential Isolation & Offline Channel Lists

- **Scoped credential clearing:** Editing a bookmark's host or port clears the old default channel and both saved passwords from the draft; editing or clearing only the default channel clears only the channel password while keeping the server password. Canceling preserves the original bookmark; unchanged bookmarks and the independent connection form are untouched.
- **Safe Connect action:** Bookmark **Connect now** will not tear down or replace an active session.
- **Offline channel lists:** Under bookmark **More settings**, when connected to the matching server, choose **Save channel list for offline use** (up to 100 channel IDs and paths per endpoint, up to 6 endpoints, separately encrypted) and select from or forget saved channels offline.

### Separate Input & Output Devices and Bluetooth Headset Mic Options

- **Independent microphone and playback selection:** Added **More options → Settings → Input and output devices** to choose physical microphones (labeling built-in Bottom/Rear/Front/Top positions when reported and numbering duplicate names without exposing raw addresses) separately from playback devices, paired with the read-only **Audio devices in use** panel (now distinguishing Bluetooth media A2DP, Bluetooth call SCO, and **Bluetooth LE Audio**).
- **Bluetooth LE Audio input and output:** Where supported by Android and the headset, **LE Audio headset microphone** and **Bluetooth LE Audio** playback can be selected independently without entering communication mode, requesting audio focus, or starting SCO.
- **Explicit Bluetooth call (use headset microphone):** When Android exposes a classic Bluetooth communication device, Playback devices offers an explicit **Bluetooth call (use headset microphone)** choice. It is never auto-selected or persisted across sessions; while active, Android pairs SCO playback with the headset microphone (audio quality may drop in classic call mode), waits for SCO playback without transmitting from a fallback mic, and releases the call request when switching back to regular Bluetooth output, disconnecting, or encountering output failure.
- **Hot-plug and safety boundaries:** Device IDs are session-temporary and never persisted. Unplugging a selected microphone returns to **Automatic microphone** with a notice; Bluetooth output loss still triggers the listening safety mute.

### On-Demand Feature Help & Settings Polish

- **Feature help:** Available under **More options → Settings → Help and about → Feature help**, covering setup, invites, bookmarks, identity backups, mic testing, playback routing, floating PTT, chat, files, and diagnostics. Works offline with control highlights, never opens automatically on launch, and never acts on your behalf.
- **Voice playback defaults & actual-device panel:** **Settings → Voice playback** now defaults to **Stability first** for unset installs (existing saved profiles are preserved), and the read-only **Audio devices in use** panel distinguishes selected output, confirmed actual output (Bluetooth media A2DP, Bluetooth call SCO, and Bluetooth LE Audio), actual recording input, and system microphone mute (idle capture is never misreported as muted).
- **Unified Settings & Identity cards:** **Identity and backups** presents direct **Create encrypted backup** and **Restore from backup** actions (**Restore previous identity** appears only when a local rollback copy exists). Standardized switches, status readouts, and action layouts across Settings improve narrow-screen and large-font reflow. Standard release builds keep desktop TS3 `.ini` preview import disabled (Debug / opt-in QA builds only).

---

## Fixes & Reliability Hardening Since 1.2.0

### Network Handoff & Reconnect Recovery

- **Wi-Fi ↔ mobile data session preservation:** During brief default-network drops (up to 2 seconds unless explicitly blocked by Android), the app keeps the existing server session, channel snapshot, and audio. If a usable network returns in time, it probes the original session and continues without reconnecting when the server responds (voice may pause briefly; otherwise normal recovery applies).
- **Accurate clone-limit (`521`) handling across handoffs:** Fixed a handshake race where a server rejection during initialization could be masked as a generic retryable interrupt. Explicit `521` responses are now classified accurately; if a confirmed network switch hits a clone-limit refusal during reconnect, the app waits 60 seconds and retries once cleanly instead of failing in a fast loop (manual connects and same-network clone refusals still stop immediately with wait guidance).
- **Handshake width retry mitigation:** Added bounded retries for recognized handshake unsigned-width errors while keeping the pinned `ts3mobile-1` protocol dependency.

### Bluetooth & Playback Safeguards

- **Continuous detection of unnotified Bluetooth fallbacks:** Checks the live playback route at output boundaries to trigger safety muting even when Android omits a route-change callback.
- **Clean delayed-track retirement:** Prevents leaked idle Bluetooth tracks when speech ends while delayed Bluetooth readiness is still completing.
- **Bounded stale-packet eviction (360 ms):** Evicts expired media beyond 360 ms and distinguishes expired voice frames from stalled audio outputs.
- **Off-main-thread cleanup and I/O:** Moved local microphone trial teardown and audio diagnostics reads off the main thread.

### Session Lifecycle, Storage & Diagnostics Hardening

- **Session exit/connect concurrency:** User disconnects no longer wipe microphone tuning or diagnostic state; bounded exit reclamation prevents stale failures or late callbacks from closing replacement sessions, stopping new audio, or leaving the UI stuck connecting.
- **Reconnect and channel operation bounds:** Preserves a second disconnect during reconnect handoff, bounds channel subscription and channel move waits, avoids silently repeating unconfirmed moves, and ensures capture-release failures still complete playback and route cleanup.
- **Archive, bookmark, and export integrity:** Local chat history survives device clock rollbacks and immediately frees queue capacity when cleared; bookmarks keep the existing archive if a save exceeds its size limit; incomplete document writes warn and allow saving to another destination; diagnostic exports isolate per-operation state.
- **Diagnostics, notifications, and backup rules:** Recovers only complete committed diagnostic checkpoints, records missed playback generations, avoids blocking service teardown on disk writes, adds redacted timing buckets and device categories (exact UDP timestamps remain opt-in local QA only), reconciles final notification actions after rapid updates, prevents retired services from restarting playback, and adds playback and update DataStores to Android backup exclusions.

---

## Upgrading from 1.2.0

- **In-place upgrade:** Keeps the official application ID (`io.github.ts3mobile.app`) and public signing certificate for upgrading from 1.2.0 (code 16) to 1.3.0 (code 17).
- **Before installing:** Disconnect cleanly from the server in the app or notification first. **Do not uninstall or clear app storage**, or your local TeamSpeak identity, bookmarks, and connection form will be lost. See [Installation & Upgrades](../../INSTALLATION.md#english) for SHA-256 verification steps.

## Verification Status & Known Limits

- The final APK uses the existing public certificate and passed a 1.2.0 in-place upgrade, synthetic-file retention, and cold launch on an API 36 emulator. Live bidirectional handoff, physical Bluetooth combinations, and real-user-data upgrades still have coverage gaps on the final APK. See [1.3.0 Verification Record](../CANDIDATE_STATUS_2026-11-25.md) and [Compatibility & Validation](../../COMPATIBILITY.md#english).
- Channel files and chat attachments are read-only downloads (20 MiB per file; no uploads). Desktop `.ini` import and the local recorder lab are excluded from standard release builds. TS3 Mobile maintains one active server connection at a time.

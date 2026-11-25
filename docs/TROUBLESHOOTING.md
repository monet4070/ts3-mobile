# Troubleshooting

[简体中文](TROUBLESHOOTING_ZH.md) · [User guide](USER_GUIDE.md) · [Install and upgrade](INSTALLATION.md)

## Installation and connection

| Symptom | What to check |
| --- | --- |
| Install failure / package conflict | Android version, APK integrity, application ID and certificate. Keep the existing app; do not uninstall before reading the upgrade guide |
| TS3 Mobile and TS3 Mobile UI both installed | UI is a separate test package with separate identity/bookmarks; data does not migrate into the release |
| Cannot connect | Confirm voice port, password, server availability and UDP reachability; ServerQuery is not the voice endpoint |
| Identity connection-limit refusal | End other sessions and follow the suggested minimum 60-second wait before manual retry. Server cleanup may take longer; repeated attempts can trigger a ban |
| Still shown on server after swiping task | A vendor may kill the process before normal exit; disconnect in-app or through its notification and allow an old session to time out |
| Missing search result | Search only covers the loaded server snapshot, subject to visibility permissions |
| Bookmark Connect disabled | Disconnect the current session and wait for bookmark reads/writes to finish |

## Audio and background sessions

| Symptom | What to check |
| --- | --- |
| Others cannot hear you | Microphone mode/permission, channel talk permission and input gain. Hold needs the talk button pressed; try a lower gate threshold |
| Quiet gate still shows microphone capture | Expected: gate detection requires continuous capture. Choose Off to stop |
| No output / wrong device | Listening/per-user mute, participant volume, Android media volume and selected route. Bluetooth loss/removal applies a safety mute. Check the route and unmute manually after reconnecting |
| Bluetooth headset microphone is not used | TS3 prefers the phone microphone with Bluetooth output so it does not need to switch to the headset call profile |
| Speaker echo | Prefer headphones, lower speaker volume and inspect device AEC state. Noise suppression and echo cancellation serve different purposes |
| Screen-off disconnect | Inspect Background voice check and vendor battery policy. Optional CPU-awake cannot guarantee survival on every device |
| Microphone settings reset or capture mode changed | In 1.1.0, gain, noise suppression, echo cancellation and gate threshold persist after saving. Check the save status and retry if needed. Capture mode is not saved; a new service starts in Hold without capturing |
| Floating PTT does not appear on Home in the candidate | Under Settings → Floating PTT, grant overlay access and enable automatic display. Join a channel and press Home while the mic is idle. The session notification can also show the overlay without opening the mic |

For a listening interruption, see the [1.1.0 diagnostics screen and export steps](USER_GUIDE.md#offline-audio-diagnostics).

## Messages and notifications

| Symptom | What to check |
| --- | --- |
| No incoming poke popup/sound | In foreground check the in-app notice. In background check notification permission, app notifications, the poke channel and DND. An in-app notice does not promise a notification sound |
| Xiaomi puts the connection card in More notifications | The system can filter the card independently of its channel importance. After connecting, open More notifications and gently swipe the TS3 Mobile card left. Choose **Mark as important** if your version offers it. Do not turn off app notifications |
| Poke does not open Chat | Expected: submission keeps your page and gives brief feedback; history remains in the corresponding private chat |
| Server accepted but no read receipt | Acceptance is not a delivery/read receipt; check peer availability, permissions and identity |
| Unknown send result | No automatic retry; verify with the peer before resending to avoid duplicates |
| Chat gone after restart | Live chat resets and there is no cloud sync. The candidate can retain new, eligible text only after you opt in under Settings → Local chat history; it does not backfill old messages |
| Form still remembers a removed bookmark password | Form memory and bookmarks are separate. Clear that form field and allow it to save too |

## Candidate features

Look up feature-specific tips under Settings → Help and about → Feature help.
Channel files are read-only: the candidate can browse and download, but not upload.
If a transfer fails, check the channel permissions and file-transfer port.
See [candidate status](release/CANDIDATE_STATUS_2026-11-25.md) for differences from the public stable release.

For unresolved problems, share version, device, reproduction steps and sanitized
diagnostics as described in [support](../SUPPORT.md). Never post real endpoints,
nicknames, passwords or identity files. Report vulnerabilities
[privately](../SECURITY.md).

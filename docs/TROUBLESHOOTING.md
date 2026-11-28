# Troubleshooting

[简体中文](TROUBLESHOOTING_ZH.md) · [User Guide](USER_GUIDE.md) · [Install & Upgrade](INSTALLATION.md#english) · [1.3.0 Release Notes](release/1.3.0/RELEASE_NOTES.md)

This page covers **TS3 Mobile 1.3.0 (versionCode 17)**. Earlier releases (such as 1.2.0) do not include 1.3.0 features like floating PTT, channel files/attachments, local chat history, or on-demand feature help.

## Installation and connection

| Symptom | What to check |
| --- | --- |
| Install failure or package conflict | Verify Android version (8.0+), APK SHA-256, application ID (`io.github.ts3mobile.app`), and signing certificate. **Keep the existing app** and read [Installation & Upgrades](INSTALLATION.md#english) before taking action—do not uninstall |
| Both TS3 Mobile and TS3 Mobile UI are installed | `TS3 Mobile UI` is a separate test package with separate identity and bookmarks; data never migrates into the release package |
| Cannot connect | Confirm the voice port (usually `9987`), server password, server status, and UDP reachability; ServerQuery is not the voice port |
| Identity connection-limit refusal (error `521`) | End other sessions and wait at least the suggested 60 seconds before manually retrying (server cleanup may take longer and repeated attempts can cause temporary bans). When this happens during a confirmed network-switch reconnect, 1.3.0 waits 60 seconds and retries once automatically |
| Still listed on server after swiping the app away | OEM task killers can bypass exit callbacks; always disconnect via the app or connection notification and allow any stale session to time out |
| Missing search result | Search covers only the currently loaded server snapshot, subject to server visibility permissions |
| Bookmark **Connect now** is disabled | In 1.3.0, **Connect now** does not tear down an active session; disconnect first and wait for bookmark reads/writes to settle |
| Saved bookmark passwords disappeared after editing | Expected 1.3.0 credential isolation: editing a bookmark's host or port clears the old default channel and both passwords from the draft; editing only the default channel clears only its password. Re-enter passwords for the new target, or cancel the edit to keep the original bookmark |

## Audio, Bluetooth, network handoff, and floating PTT

| Symptom | What to check |
| --- | --- |
| Others cannot hear you | Check microphone mode, mic permission, channel talk permission, and input gain. **Hold** requires keeping the talk button pressed; for Gate, lower the threshold and test with **Settings → Microphone settings → Test microphone** |
| Quiet gate still shows microphone capture | Expected: volume gate must capture continuously to detect speech. Choose **Off** to stop capture completely |
| No output or wrong audio device | Check global listening mute, per-user mute/volume, Android media volume, and **Audio devices in use** under **Settings → Input and output devices** (or **Voice playback**). Bluetooth loss or auto-route fallback latches a safety mute—confirm the route and unmute manually after reconnecting |
| Bluetooth headset microphone is not used | In default media mixing mode, **Automatic microphone** prefers the phone mic to preserve Bluetooth media quality. To use a headset mic, open **Settings → Input and output devices**: pick **LE Audio headset microphone** if supported, or manually select **Bluetooth call (use headset microphone)** under Playback device for classic headsets (classic call mode may reduce audio quality and is not persisted across sessions) |
| Audio quality dropped after choosing Bluetooth call, or it reset on reconnect | Lower audio quality is a standard classic Bluetooth SCO call-mode trade-off; select the regular Bluetooth output to return to media mode. Call selection is intentionally not saved for future sessions |
| Speaker echo | Prefer headphones, lower speaker volume, and inspect device AEC status under **Settings → Microphone settings** (noise suppression and echo cancellation serve different purposes, and real AEC quality varies by phone) |
| Brief voice pause when switching Wi-Fi ↔ mobile data | During brief default-network drops (up to 2 seconds), 1.3.0 keeps and probes the original server session to avoid reconnecting; voice may pause briefly, and normal recovery runs if the session cannot be kept |
| Screen-off disconnect | Check **Settings → Background voice check** and OEM battery settings (for example, Unrestricted). Optional CPU-awake helps but cannot guarantee indefinite survival on every ROM |
| Capture mode resets to Hold after restart | Expected safety behavior: gain, RNNoise, AEC, and gate threshold persist once saved, but capture mode is never persisted—a new service always starts in **Hold** without recording |
| Floating PTT does not auto-show on leaving the app | Under **Settings → Floating PTT**, grant "Display over other apps" and enable **Auto-show when leaving the app**. Join a channel with the mic idle (Back gesture trigger requires Android 12+), or tap **Show PTT** in the connection notification to recall manually |
| Floating PTT won't transmit when pressed | Only **Hold** mode transmits on press; in other modes the right tile only displays live status. Stop any active **Test microphone** trial first so input is released |

For listening dropouts or Bluetooth glitches, follow the [offline audio diagnostics steps](USER_GUIDE.md#offline-audio-diagnostics) to mark interruptions and export JSON.

## Messages and notifications

| Symptom | What to check |
| --- | --- |
| No incoming poke popup or sound | In foreground check the in-app notice; in background check notification permission, app notifications, the **Poke alerts** channel, and Do Not Disturb |
| Xiaomi places the connection card in "More notifications" | System notification filtering is independent of channel importance. While connected, open "More notifications", gently swipe the TS3 Mobile card left, and choose **Mark as important** if available (never disable app notifications) |
| Poke does not switch to Chat | Expected: submitting keeps your active tab with brief feedback; poke history stays in the corresponding private chat |
| "Server accepted" shown, but peer didn't read it | Server acceptance is not a delivery or read receipt; verify peer availability, permissions, and identity |
| Unknown send result | Never retried automatically; check with the recipient before resending to avoid duplicates |
| Copying a message includes `[b]` / `[url]` markup | Expected: 1.3.0 renders basic BBCode visually while Copy always preserves the original raw text; external links ask for destination confirmation before opening |
| Chat gone after restart | Live chat is memory-only with no cloud sync. Opt in under **Settings → Local chat history** beforehand to save eligible new text offline (never backfills earlier messages) |
| Form still remembers a deleted bookmark password | Form memory and bookmarks are stored separately; clear that field in the connection form and wait for it to save |

## Files, images, and feature help

| Symptom | What to check |
| --- | --- |
| Channel file list or download fails | Check channel permissions and ask the administrator to verify file-transfer port reachability. Reopen the browser after switching channels; if the app explicitly asks you to reconnect after a listing timeout, disconnect and reconnect first |
| Chat attachment cannot be downloaded | Only supported attachments on the currently connected server can be retrieved, up to **20 MiB per file**. Uploads, automatic downloads, and cross-server retrieval are not supported |
| Warning that a saved file may be incomplete | Inspect the chosen destination; if the verified download is still staged, retry to a different file, otherwise download again |
| Not sure where a feature lives | Open **Settings → Help and about → Feature help** (works offline with in-app control highlights), or see [Files and server images](USER_GUIDE.md#files-and-server-images) |

---

For unresolved issues, follow [Support](../SUPPORT.md#english) and include version, device model, Android version, steps, and sanitized diagnostics. Never post real server addresses, nicknames, passwords, or identity files. Report suspected security vulnerabilities [privately](../SECURITY.md).
